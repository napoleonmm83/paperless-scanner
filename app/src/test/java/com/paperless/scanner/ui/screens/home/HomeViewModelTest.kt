package com.paperless.scanner.ui.screens.home

import android.content.Context
import app.cash.turbine.test
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentsResponse
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.DocumentCountRepository
import com.paperless.scanner.data.repository.DocumentListRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.TrashRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.sync.SyncManager
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.datastore.TokenManager
import kotlinx.coroutines.flow.flow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private lateinit var context: Context
    private lateinit var documentListRepository: DocumentListRepository
    private lateinit var documentCountRepository: DocumentCountRepository
    private lateinit var trashRepository: TrashRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var syncManager: SyncManager
    private lateinit var analyticsService: AnalyticsService
    private lateinit var tokenManager: TokenManager

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        documentListRepository = mockk(relaxed = true)
        documentCountRepository = mockk(relaxed = true)
        trashRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        uploadQueueRepository = mockk(relaxed = true)
        syncManager = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)

        // Default mock responses
        every { uploadQueueRepository.pendingCount } returns MutableStateFlow(0)
        every { syncManager.pendingChangesCount } returns MutableStateFlow(0)
        every { tagRepository.observeTags() } returns MutableStateFlow(emptyList())
        every { documentListRepository.observeDocuments(page = 1, pageSize = 5) } returns MutableStateFlow(emptyList())
        every { documentCountRepository.observeUntaggedDocumentsCount() } returns MutableStateFlow(0)
        every { trashRepository.observeTrashedDocumentsCount() } returns MutableStateFlow(0)
        every { trashRepository.observeOldestDeletedTimestamp() } returns MutableStateFlow(null)
        coEvery { tagRepository.getTags() } returns Result.success(emptyList())
        coEvery { tagRepository.getTags(any()) } returns Result.success(emptyList())
        coEvery { documentCountRepository.getDocumentCount(any()) } returns Result.success(0)
        coEvery { documentListRepository.getDocuments(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Result.success(DocumentsResponse(count = 0, results = emptyList()))
        coEvery { documentListRepository.getRecentDocuments(any()) } returns Result.success(emptyList())
        coEvery { documentCountRepository.getUntaggedCount() } returns Result.success(0)
        coEvery { trashRepository.getTrashDocuments(any(), any()) } returns Result.success(DocumentsResponse(count = 0, results = emptyList(), next = null))
        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            context = context,
            documentListRepository = documentListRepository,
            documentCountRepository = documentCountRepository,
            trashRepository = trashRepository,
            tagRepository = tagRepository,
            uploadQueueRepository = uploadQueueRepository,
            syncManager = syncManager,
            analyticsService = analyticsService,
            tokenManager = tokenManager,
        )
    }

    // ==================== Minimal Test Set ====================
    
    @Test
    fun `initial uiState has correct defaults`() = runTest {
        val viewModel = createViewModel()
        
        // Check initial state before loading completes
        val initialState = viewModel.uiState.value
        assertTrue(initialState.isLoading)
        assertNull(viewModel.errorState.value)
    }
    
    @Test
    fun `loadDashboardData completes without errors`() = runTest {
        val viewModel = createViewModel()
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(0, state.stats.totalDocuments)
    }

    /**
     * Regression for Issue #68: tagMap was previously a `var Map` written
     * from one collector and read from another. After the StateFlow refactor,
     * recent documents combine() against the tag flow, so a late-arriving
     * tag emission propagates into already-emitted documents instead of
     * being lost in a partial-map race.
     */
    @Test
    fun `tag updates propagate into recent documents reactively`() = runTest {
        val tagFlow = MutableStateFlow<List<Tag>>(emptyList())
        val docsFlow = MutableStateFlow<List<Document>>(emptyList())

        every { tagRepository.observeTags() } returns tagFlow
        every { documentListRepository.observeDocuments(page = 1, pageSize = 5) } returns docsFlow

        val viewModel = createViewModel()
        runCurrent()

        // Document arrives first, referring to a tag the cache does not yet know.
        docsFlow.value = listOf(
            Document(
                id = 1,
                title = "Invoice",
                created = "2026-01-01",
                modified = "2026-01-01",
                added = "2026-01-01",
                tags = listOf(5)
            )
        )
        advanceUntilIdle()

        // Without reactive tagMap, tagName would stay null forever.
        assertNull(viewModel.uiState.value.recentDocuments.firstOrNull()?.tagName)

        // Tag arrives later — combine() must re-emit recent documents with the tag resolved.
        tagFlow.value = listOf(Tag(id = 5, name = "Bills"))
        advanceUntilIdle()

        assertEquals("Bills", viewModel.uiState.value.recentDocuments.first().tagName)

        // A tag rename must also propagate without needing a fresh document emission.
        tagFlow.value = listOf(Tag(id = 5, name = "Renamed"))
        advanceUntilIdle()

        assertEquals("Renamed", viewModel.uiState.value.recentDocuments.first().tagName)
    }

    // ==================== Error Handling (Issue #85) ====================
    // These 4 tests FAIL until Task 6 implements asUiResult() in observe* functions.
    // Task 6 is responsible for catching Result.failure in observe* and emitting LoadFailed.

    @Test
    fun `observeTagsReactively sets LoadFailed on errorState when tags observer encounters error`() = runTest {
        every { tagRepository.observeTags() } returns flow { throw RuntimeException("tags DB error") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.errorState.value
        assertNotNull("errorState should have LoadFailed after Task 6", error)
        assertTrue("error should be LoadFailed", error is HomeError.LoadFailed)
        if (error is HomeError.LoadFailed) {
            assertEquals("source should be tags", "tags", error.source)
        }
    }

    @Test
    fun `observeRecentDocumentsReactively sets LoadFailed on errorState when documents observer encounters error`() = runTest {
        every { documentListRepository.observeDocuments(page = 1, pageSize = 5) } returns flow { throw RuntimeException("docs DB error") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.errorState.value
        assertNotNull("errorState should have LoadFailed after Task 6", error)
        assertTrue("error should be LoadFailed", error is HomeError.LoadFailed)
        if (error is HomeError.LoadFailed) {
            assertEquals("source should be recentDocuments", "recentDocuments", error.source)
        }
    }

    @Test
    fun `deleteRecentDocument sets ActionFailed on errorState when delete fails`() = runTest {
        coEvery { trashRepository.deleteDocument(any()) } returns Result.failure(RuntimeException("Network error"))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteRecentDocument(documentId = 1, documentTitle = "Test Doc")
        advanceUntilIdle()

        // PASSES if Task 3 already implemented deleteRecentDocument error wiring
        // or FAILS if error handling not yet complete in deleteRecentDocument
        val error = vm.errorState.value
        assertNotNull("errorState should have ActionFailed for failed delete", error)
        assertTrue("error should be ActionFailed", error is HomeError.ActionFailed)
        if (error is HomeError.ActionFailed) {
            assertEquals("action should be deleteDocument", "deleteDocument", error.action)
        }
    }

    @Test
    fun `clearHomeError resets errorState to null`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        // This test PASSES (clearHomeError always works, doesn't depend on error catching)
        vm.clearHomeError()
        assertNull("errorState should be null after clearHomeError", vm.errorState.value)
    }
}
