package com.paperless.scanner.ui.screens.home

import android.content.Context
import app.cash.turbine.test
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.DocumentListRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.TrashRepository
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentsResponse
import com.paperless.scanner.domain.model.Tag
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecentDocumentsViewModelTest {

    private lateinit var context: Context
    private lateinit var documentListRepository: DocumentListRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var trashRepository: TrashRepository
    private lateinit var tokenManager: TokenManager
    private lateinit var analyticsService: AnalyticsService

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        documentListRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        trashRepository = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)

        every { tagRepository.observeTags() } returns MutableStateFlow(emptyList())
        every { documentListRepository.observeDocuments(page = 1, pageSize = 5) } returns
                MutableStateFlow(emptyList())
        every { tokenManager.serverUrl } returns MutableStateFlow(null)
        every { tokenManager.showThumbnails } returns MutableStateFlow(true)
        coEvery {
            documentListRepository.getDocuments(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(DocumentsResponse(count = 0, results = emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): RecentDocumentsViewModel = RecentDocumentsViewModel(
        context = context,
        documentListRepository = documentListRepository,
        tagRepository = tagRepository,
        trashRepository = trashRepository,
        tokenManager = tokenManager,
        analyticsService = analyticsService,
    )

    private fun doc(id: Int, title: String = "doc-$id", tags: List<Int> = emptyList()) = Document(
        id = id,
        title = title,
        created = "2026-01-01",
        modified = "2026-01-01",
        added = "2026-01-01",
        tags = tags,
    )

    @Test
    fun `initial uiState has loading true and empty lists`() = runTest {
        val vm = createViewModel()

        val initial = vm.uiState.value
        assertTrue(initial.isLoading)
        assertTrue(initial.recentDocuments.isEmpty())
        assertNull(initial.deletedDocument)
        assertNull(vm.error.value)
    }

    @Test
    fun `init triggers server refresh of recent documents`() = runTest {
        createViewModel()
        advanceUntilIdle()

        // refreshRecentDocuments() is called from init for cold-start hydration.
        coVerify(atLeast = 1) {
            documentListRepository.getDocuments(
                page = 1,
                pageSize = 10,
                ordering = "-added",
                forceRefresh = true,
            )
        }
    }

    /**
     * Regression for Issue #68: tagMap was previously a `var Map` written
     * from one collector and read from another. The combine() of docs + _tagMap
     * makes a late-arriving tag emission propagate into already-emitted
     * documents instead of being lost in a partial-map race.
     */
    @Test
    fun `tag updates propagate into recent documents reactively`() = runTest {
        val tagFlow = MutableStateFlow<List<Tag>>(emptyList())
        val docsFlow = MutableStateFlow<List<Document>>(emptyList())

        every { tagRepository.observeTags() } returns tagFlow
        every { documentListRepository.observeDocuments(page = 1, pageSize = 5) } returns docsFlow

        val vm = createViewModel()
        runCurrent()

        // Document arrives first, referring to a tag the cache does not yet know.
        docsFlow.value = listOf(doc(id = 1, title = "Invoice", tags = listOf(5)))
        advanceUntilIdle()

        assertNull(vm.uiState.value.recentDocuments.firstOrNull()?.tagName)

        // Tag arrives later — combine() must re-emit with the tag resolved.
        tagFlow.value = listOf(Tag(id = 5, name = "Bills"))
        advanceUntilIdle()

        assertEquals("Bills", vm.uiState.value.recentDocuments.first().tagName)

        // A tag rename must also propagate without a fresh document emission.
        tagFlow.value = listOf(Tag(id = 5, name = "Renamed"))
        advanceUntilIdle()

        assertEquals("Renamed", vm.uiState.value.recentDocuments.first().tagName)
    }

    @Test
    fun `observeTags sets LoadFailed on error when tags observer throws`() = runTest {
        every { tagRepository.observeTags() } returns
                flow { throw RuntimeException("tags DB error") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.error.value
        assertNotNull(error)
        assertTrue(error is RecentDocumentsError.LoadFailed)
        if (error is RecentDocumentsError.LoadFailed) {
            assertEquals("tags", error.source)
        }
    }

    @Test
    fun `observeRecentDocuments sets LoadFailed on error and clears isLoading`() = runTest {
        every { documentListRepository.observeDocuments(page = 1, pageSize = 5) } returns
                flow { throw RuntimeException("docs DB error") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.error.value
        assertNotNull(error)
        assertTrue(error is RecentDocumentsError.LoadFailed)
        if (error is RecentDocumentsError.LoadFailed) {
            assertEquals("recentDocuments", error.source)
        }
        // Failure path must still clear isLoading so the empty state can render.
        assertEquals(false, vm.uiState.value.isLoading)
    }

    @Test
    fun `deleteRecentDocument sets deletedDocument optimistically and clears on failure`() = runTest {
        coEvery { trashRepository.deleteDocument(any()) } returns
                Result.failure(RuntimeException("network error"))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            // Drop the initial state emission.
            awaitItem()

            vm.deleteRecentDocument(documentId = 1, documentTitle = "Test Doc")

            // Optimistic update — deletedDocument set before the network call resolves.
            val optimistic = awaitItem()
            assertEquals(1, optimistic.deletedDocument?.id)
            assertEquals("Test Doc", optimistic.deletedDocument?.title)

            // Failure path clears deletedDocument so the snackbar dismisses.
            val cleared = awaitItem()
            assertNull(cleared.deletedDocument)

            cancelAndIgnoreRemainingEvents()
        }

        val error = vm.error.value
        assertNotNull(error)
        assertTrue(error is RecentDocumentsError.ActionFailed)
        if (error is RecentDocumentsError.ActionFailed) {
            assertEquals("deleteDocument", error.action)
        }
    }

    @Test
    fun `deleteRecentDocument keeps deletedDocument set on success for undo affordance`() = runTest {
        coEvery { trashRepository.deleteDocument(any()) } returns Result.success(Unit)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteRecentDocument(documentId = 42, documentTitle = "Receipt")
        advanceUntilIdle()

        // On success the snackbar should stay visible — the reactive flow
        // removes the document from the list, but deletedDocument stays so the
        // user can still tap undo.
        assertEquals(42, vm.uiState.value.deletedDocument?.id)
        assertNull(vm.error.value)
    }

    @Test
    fun `undoDelete is a no-op when no document is pending`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.undoDelete()
        advanceUntilIdle()

        coVerify(exactly = 0) { trashRepository.restoreDocument(any()) }
        assertNull(vm.error.value)
    }

    @Test
    fun `undoDelete clears deletedDocument and calls restoreDocument`() = runTest {
        coEvery { trashRepository.deleteDocument(any()) } returns Result.success(Unit)
        coEvery { trashRepository.restoreDocument(any()) } returns Result.success(Unit)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteRecentDocument(documentId = 7, documentTitle = "Doc 7")
        advanceUntilIdle()
        assertEquals(7, vm.uiState.value.deletedDocument?.id)

        vm.undoDelete()
        advanceUntilIdle()

        assertNull(vm.uiState.value.deletedDocument)
        coVerify(exactly = 1) { trashRepository.restoreDocument(7) }
    }

    @Test
    fun `undoDelete surfaces ActionFailed on restore failure`() = runTest {
        coEvery { trashRepository.deleteDocument(any()) } returns Result.success(Unit)
        coEvery { trashRepository.restoreDocument(any()) } returns
                Result.failure(RuntimeException("restore failed"))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteRecentDocument(documentId = 99, documentTitle = "Doc 99")
        advanceUntilIdle()

        vm.undoDelete()
        advanceUntilIdle()

        val error = vm.error.value
        assertNotNull(error)
        assertTrue(error is RecentDocumentsError.ActionFailed)
        if (error is RecentDocumentsError.ActionFailed) {
            assertEquals("restoreDocument", error.action)
        }
    }

    @Test
    fun `clearDeletedDocument resets deletedDocument to null`() = runTest {
        coEvery { trashRepository.deleteDocument(any()) } returns Result.success(Unit)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteRecentDocument(documentId = 5, documentTitle = "Doc")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.deletedDocument)

        vm.clearDeletedDocument()
        assertNull(vm.uiState.value.deletedDocument)
    }

    @Test
    fun `clearError resets error state to null`() = runTest {
        every { tagRepository.observeTags() } returns
                flow { throw RuntimeException("boom") }

        val vm = createViewModel()
        advanceUntilIdle()
        assertNotNull(vm.error.value)

        vm.clearError()
        assertNull(vm.error.value)
    }

    @Test
    fun `refreshRecentDocuments forces server reload`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        // Reset the per-init call by verifying total count rather than a clean
        // baseline (mockk doesn't expose a reset-and-verify helper here).
        vm.refreshRecentDocuments()
        advanceUntilIdle()

        coVerify(atLeast = 2) {
            documentListRepository.getDocuments(
                page = 1,
                pageSize = 10,
                ordering = "-added",
                forceRefresh = true,
            )
        }
    }
}
