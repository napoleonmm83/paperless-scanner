package com.paperless.scanner.ui.screens.scan

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.google.gson.Gson
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.AuthRepository
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.util.AppLockManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [ScanViewModel].
 *
 * Coverage focus (per #157 acceptance criteria):
 * - removePage / undoRemovePage / clearLastRemovedPage / clearPages / movePage
 * - SavedStateHandle restoration (process-death survival)
 * - syncPagesToSavedState (verified via savedStateHandle keys after mutations)
 * - Tag selection: toggleTag / clearSelectedTags / getSelectedTagIds
 * - Page accessors: getPageUris / getPages
 * - setProcessing / setUploadAsSingleDocument
 *
 * Out of scope (Dispatchers.Default coupling — would require production
 * dispatcher injection refactor):
 * - addPages (uses withContext(Dispatchers.Default), advanceUntilIdle does
 *   not drive Default)
 *
 * Out of scope (file I/O / AI heavy paths):
 * - rotatePage, cropPage, getRotatedPageUris (Bitmap I/O)
 * - analyzeFirstPage, createTag (network + AI orchestrator coupling)
 *
 * Workaround for the addPages limitation: tests that need pre-populated
 * pages set them via SavedStateHandle before constructing the VM —
 * `restorePagesFromSavedState()` runs synchronously in init.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ScanViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var authRepository: AuthRepository
    private lateinit var analyticsService: AnalyticsService
    private lateinit var tagRepository: TagRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var correspondentRepository: CorrespondentRepository
    private lateinit var suggestionOrchestrator: SuggestionOrchestrator
    private lateinit var aiUsageRepository: AiUsageRepository
    private lateinit var premiumFeatureManager: PremiumFeatureManager
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var tokenManager: TokenManager
    private lateinit var appLockManager: AppLockManager
    private lateinit var gson: Gson

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = RuntimeEnvironment.getApplication()
        savedStateHandle = SavedStateHandle()
        authRepository = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)
        suggestionOrchestrator = mockk(relaxed = true)
        aiUsageRepository = mockk(relaxed = true)
        premiumFeatureManager = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        appLockManager = mockk(relaxed = true)
        gson = Gson()

        // Default flow stubs — init block in the VM subscribes to these.
        every { tagRepository.observeTags() } returns flowOf(emptyList())
        every { documentTypeRepository.observeDocumentTypes() } returns flowOf(emptyList())
        every { correspondentRepository.observeCorrespondents() } returns flowOf(emptyList())
        coEvery { aiUsageRepository.observeCurrentMonthCallCount() } returns flowOf(0)
        every { networkMonitor.isWifiConnected } returns MutableStateFlow(false)
        every { tokenManager.serverUsesCloudflare } returns flowOf(false)
        every { premiumFeatureManager.isFeatureAvailable(any()) } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ScanViewModel = ScanViewModel(
        savedStateHandle = savedStateHandle,
        authRepository = authRepository,
        analyticsService = analyticsService,
        tagRepository = tagRepository,
        documentTypeRepository = documentTypeRepository,
        correspondentRepository = correspondentRepository,
        suggestionOrchestrator = suggestionOrchestrator,
        aiUsageRepository = aiUsageRepository,
        premiumFeatureManager = premiumFeatureManager,
        networkMonitor = networkMonitor,
        tokenManager = tokenManager,
        appLockManager = appLockManager,
        context = context,
        gson = gson
    )

    /**
     * Pre-populate SavedStateHandle with N pages and construct the VM.
     * Triggers `restorePagesFromSavedState()` in init synchronously.
     */
    private fun viewModelWithPages(uris: List<String>): ScanViewModel {
        savedStateHandle[ScanViewModel.KEY_PAGE_URIS] =
            uris.joinToString("|") { "file:///tmp/$it.jpg" }
        savedStateHandle["pageIds"] = uris.indices.joinToString("|") { "id-$it" }
        return createViewModel()
    }

    // ==================== Initial State ====================

    @Test
    fun `initial uiState has no pages`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.pageCount)
        assertFalse(state.hasPages)
        assertFalse(state.isProcessing)
        assertNull(state.lastRemovedPage)
    }

    @Test
    fun `uploadAsSingleDocument defaults to false`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uploadAsSingleDocument.value)
    }

    // ==================== Process-Death Restoration ====================

    @Test
    fun `pages restore from SavedStateHandle on construction`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b"))
        advanceUntilIdle()

        val pages = viewModel.uiState.value.pages
        assertEquals(2, pages.size)
        assertEquals("file:///tmp/a.jpg", pages[0].uri.toString())
        assertEquals("id-0", pages[0].id)
        assertEquals(1, pages[0].pageNumber)
        assertEquals(2, pages[1].pageNumber)
    }

    @Test
    fun `restoration is a no-op when SavedStateHandle is empty`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.pageCount)
    }

    // ==================== removePage / undoRemovePage ====================

    @Test
    fun `removePage drops the page and renumbers the rest`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b", "c"))
        advanceUntilIdle()

        val midId = viewModel.uiState.value.pages[1].id
        viewModel.removePage(midId)

        val pages = viewModel.uiState.value.pages
        assertEquals(2, pages.size)
        assertEquals(1, pages[0].pageNumber)
        assertEquals(2, pages[1].pageNumber)
        assertNotNull(viewModel.uiState.value.lastRemovedPage)
    }

    @Test
    fun `removePage with unknown id is a no-op`() = runTest {
        val viewModel = viewModelWithPages(listOf("a"))
        advanceUntilIdle()

        viewModel.removePage("does-not-exist")

        assertEquals(1, viewModel.uiState.value.pageCount)
    }

    @Test
    fun `undoRemovePage restores page at original index`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b", "c"))
        advanceUntilIdle()
        val midId = viewModel.uiState.value.pages[1].id
        viewModel.removePage(midId)

        viewModel.undoRemovePage()

        val pages = viewModel.uiState.value.pages
        assertEquals(3, pages.size)
        assertEquals(midId, pages[1].id)
        assertNull(viewModel.uiState.value.lastRemovedPage)
    }

    @Test
    fun `undoRemovePage is a no-op when no removal pending`() = runTest {
        val viewModel = viewModelWithPages(listOf("a"))
        advanceUntilIdle()

        viewModel.undoRemovePage()

        assertEquals(1, viewModel.uiState.value.pageCount)
    }

    @Test
    fun `clearLastRemovedPage clears the snackbar trigger`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b"))
        advanceUntilIdle()
        val firstId = viewModel.uiState.value.pages[0].id
        viewModel.removePage(firstId)
        assertNotNull(viewModel.uiState.value.lastRemovedPage)

        viewModel.clearLastRemovedPage()

        assertNull(viewModel.uiState.value.lastRemovedPage)
    }

    // ==================== removePage syncs to SavedStateHandle ====================

    @Test
    fun `removePage updates SavedStateHandle pageUris`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b", "c"))
        advanceUntilIdle()
        val midId = viewModel.uiState.value.pages[1].id

        viewModel.removePage(midId)

        val pageUris = savedStateHandle.get<String>(ScanViewModel.KEY_PAGE_URIS)
        assertNotNull(pageUris)
        assertTrue(pageUris!!.contains("a.jpg"))
        assertFalse(pageUris.contains("b.jpg"))
        assertTrue(pageUris.contains("c.jpg"))
    }

    @Test
    fun `undoRemovePage updates SavedStateHandle pageUris with restored page`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b", "c"))
        advanceUntilIdle()

        // Capture the original three URIs in the SavedStateHandle.
        val urisBefore = savedStateHandle.get<String>(ScanViewModel.KEY_PAGE_URIS)
        assertNotNull(urisBefore)

        // Remove the middle page.
        val midId = viewModel.uiState.value.pages[1].id
        viewModel.removePage(midId)
        advanceUntilIdle()

        val urisAfterRemove = savedStateHandle.get<String>(ScanViewModel.KEY_PAGE_URIS)
        assertNotNull(urisAfterRemove)
        // After removal, two URIs remain.
        assertEquals(2, urisAfterRemove!!.split("|").size)

        // Undo the removal.
        viewModel.undoRemovePage()
        advanceUntilIdle()

        val urisAfterUndo = savedStateHandle.get<String>(ScanViewModel.KEY_PAGE_URIS)
        assertNotNull(urisAfterUndo)
        // After undo, three URIs are back.
        assertEquals(3, urisAfterUndo!!.split("|").size)
    }

    // ==================== movePage ====================

    @Test
    fun `movePage reorders and renumbers pages`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b", "c"))
        advanceUntilIdle()

        viewModel.movePage(0, 2)

        val pages = viewModel.uiState.value.pages
        assertEquals("file:///tmp/b.jpg", pages[0].uri.toString())
        assertEquals("file:///tmp/c.jpg", pages[1].uri.toString())
        assertEquals("file:///tmp/a.jpg", pages[2].uri.toString())
        assertEquals(1, pages[0].pageNumber)
        assertEquals(2, pages[1].pageNumber)
        assertEquals(3, pages[2].pageNumber)
    }

    @Test
    fun `movePage with out-of-bounds indices is a no-op`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b"))
        advanceUntilIdle()

        viewModel.movePage(0, 10)
        viewModel.movePage(-1, 0)

        val pages = viewModel.uiState.value.pages
        assertEquals("file:///tmp/a.jpg", pages[0].uri.toString())
        assertEquals("file:///tmp/b.jpg", pages[1].uri.toString())
    }

    @Test
    fun `movePage out-of-bounds does not fire analytics`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b"))
        advanceUntilIdle()

        viewModel.movePage(fromIndex = 0, toIndex = 10)
        advanceUntilIdle()

        io.mockk.verify(exactly = 0) {
            analyticsService.trackEvent(any())
        }
    }

    // ==================== clearPages ====================

    @Test
    fun `clearPages empties uiState and SavedStateHandle`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b"))
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.pageCount)

        viewModel.clearPages()

        assertEquals(0, viewModel.uiState.value.pageCount)
        assertNull(savedStateHandle.get<String>(ScanViewModel.KEY_PAGE_URIS))
    }

    // ==================== setProcessing ====================

    @Test
    fun `setProcessing toggles isProcessing flag`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setProcessing(true)
        assertTrue(viewModel.uiState.value.isProcessing)

        viewModel.setProcessing(false)
        assertFalse(viewModel.uiState.value.isProcessing)
    }

    // ==================== setUploadAsSingleDocument ====================

    @Test
    fun `setUploadAsSingleDocument persists into SavedStateHandle`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setUploadAsSingleDocument(true)
        assertTrue(viewModel.uploadAsSingleDocument.value)
        // Belt-and-braces: assert the underlying SavedStateHandle key directly
        // (StateFlow above is built from the same key, but explicit is better).
        assertEquals(true, savedStateHandle.get<Boolean>("uploadAsSingleDocument"))

        viewModel.setUploadAsSingleDocument(false)
        assertFalse(viewModel.uploadAsSingleDocument.value)
        assertEquals(false, savedStateHandle.get<Boolean>("uploadAsSingleDocument"))
    }

    // ==================== Tag Selection ====================

    @Test
    fun `toggleTag adds tag id when not selected`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleTag(42)

        assertEquals(listOf(42), viewModel.getSelectedTagIds())
    }

    @Test
    fun `toggleTag removes tag id when already selected`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleTag(42)
        viewModel.toggleTag(42)

        assertEquals(emptyList<Int>(), viewModel.getSelectedTagIds())
    }

    @Test
    fun `clearSelectedTags removes all selections`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.toggleTag(1)
        viewModel.toggleTag(2)
        viewModel.toggleTag(3)
        assertEquals(3, viewModel.getSelectedTagIds().size)

        viewModel.clearSelectedTags()

        assertEquals(emptyList<Int>(), viewModel.getSelectedTagIds())
    }

    // ==================== Page accessors ====================

    @Test
    fun `getPageUris returns URIs in order`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b", "c"))
        advanceUntilIdle()

        val uris = viewModel.getPageUris()
        assertEquals(3, uris.size)
        assertEquals("file:///tmp/a.jpg", uris[0].toString())
    }

    @Test
    fun `getPages returns the same list as uiState pages`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b"))
        advanceUntilIdle()

        assertEquals(viewModel.uiState.value.pages, viewModel.getPages())
    }
}
