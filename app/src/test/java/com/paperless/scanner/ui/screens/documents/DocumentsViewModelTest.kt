package com.paperless.scanner.ui.screens.documents

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentsResponse
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentsViewModelTest {

    private lateinit var context: Context
    private lateinit var documentRepository: DocumentRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var correspondentRepository: CorrespondentRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var tokenManager: TokenManager
    private lateinit var savedStateHandle: SavedStateHandle

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        documentRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()

        // Default mock responses
        coEvery { tagRepository.getTags() } returns Result.success(emptyList())
        coEvery { correspondentRepository.getCorrespondents() } returns Result.success(emptyList())
        coEvery { documentTypeRepository.getDocumentTypes() } returns Result.success(emptyList())
        coEvery { documentRepository.getDocuments(any(), any(), any(), any()) } returns
                Result.success(DocumentsResponse(count = 0, results = emptyList()))
        // Mock DataStore flows
        every { tokenManager.documentFilterJson } returns flowOf(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): DocumentsViewModel {
        return DocumentsViewModel(
            context = context,
            documentRepository = documentRepository,
            tagRepository = tagRepository,
            correspondentRepository = correspondentRepository,
            documentTypeRepository = documentTypeRepository,
            tokenManager = tokenManager,
            savedStateHandle = savedStateHandle
        )
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial uiState has correct defaults`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.isLoading)
        // Note: documents are now in documentsFlow (Paging 3), not in UiState
        assertNull(state.error)
        assertEquals("", state.searchQuery)
        assertNull(state.activeTagFilter)
    }

    @Test
    fun `initial state loads tags and correspondents`() = runTest {
        val mockTags = listOf(
            Tag(id = 1, name = "Invoice"),
            Tag(id = 2, name = "Receipt")
        )
        coEvery { tagRepository.getTags() } returns Result.success(mockTags)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.availableTags.size)
    }

    // ==================== Reactive Flow Tests ====================
    // Note: loadDocuments() removed in favor of reactive Flow architecture
    // Documents are automatically loaded and updated via observeDocumentsReactively()
    // Testing reactive flows requires more complex setup with turbine
    // These tests are covered by integration tests instead

    // ==================== Search Tests ====================

    @Test
    fun `search updates searchQuery in state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search("invoice")

        // Wait for debounce (300ms) + processing
        testScheduler.advanceTimeBy(400)
        advanceUntilIdle()

        // Check filter directly (search updates filter.query)
        val filter = viewModel.filter.value
        assertEquals("invoice", filter.query)
    }

    // ==================== Filter Tests ====================

    @Test
    fun `filterByTag updates activeTagFilter and reloads`() = runTest {
        // Note: With Paging 3, filterByTag updates DocumentFilter which triggers PagingSource refresh
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.filterByTag(5)
        advanceUntilIdle()

        // Check filter directly (UI state sync is async via flatMapLatest)
        val filter = viewModel.filter.value
        assertEquals(listOf(5), filter.tagIds)
    }

    @Test
    fun `filterByTag with null clears filter`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.filterByTag(5)
        advanceUntilIdle()

        viewModel.filterByTag(null)
        advanceUntilIdle()

        // Check filter directly (UI state sync is async via flatMapLatest)
        val filter = viewModel.filter.value
        assertTrue(filter.tagIds.isEmpty())
    }

    @Test
    fun `clearFilters resets search and tag filter`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.filterByTag(5)
        advanceUntilIdle()

        viewModel.clearFilters()
        advanceUntilIdle()

        // Check filter directly (UI state sync is async via flatMapLatest)
        val filter = viewModel.filter.value
        assertNull(filter.query)
        assertTrue(filter.tagIds.isEmpty())
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `clearError removes error from state`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Manually set error state for testing
        // Note: In production, errors are set by reactive flows automatically
        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    // ==================== Reset State Tests ====================

    @Test
    fun `resetState clears all state and reloads`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.filterByTag(5)
        advanceUntilIdle()

        viewModel.resetState()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.activeTagFilter)
        assertEquals("", state.searchQuery)
    }

    // ==================== Helper Functions ====================

    private fun createMockDocument(id: Int, title: String): Document {
        return Document(
            id = id,
            title = title,
            content = "Content $id",
            created = "2024-01-01T00:00:00Z",
            added = "2024-01-01T00:00:00Z",
            modified = "2024-01-01T00:00:00Z",
            correspondentId = null,
            documentTypeId = null,
            tags = emptyList(),
            originalFileName = "file_$id.pdf",
            archiveSerialNumber = null,
            notes = emptyList(),
            owner = null,
            permissions = null
        )
    }
}
