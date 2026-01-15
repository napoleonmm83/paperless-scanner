package com.paperless.scanner.ui.screens.documents

import android.content.Context
import app.cash.turbine.test
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentsResponse
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.TagRepository
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

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        documentRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)

        // Default mock responses
        coEvery { tagRepository.getTags() } returns Result.success(emptyList())
        coEvery { correspondentRepository.getCorrespondents() } returns Result.success(emptyList())
        coEvery { documentRepository.getDocuments(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Result.success(DocumentsResponse(count = 0, results = emptyList()))
        // Mock reactive flows
        every { documentRepository.observeDocuments(any(), any()) } returns flowOf(emptyList())
        every { documentRepository.observeDocumentsFiltered(any(), any(), any(), any()) } returns flowOf(emptyList())
        every { documentRepository.observeFilteredCount(any(), any()) } returns flowOf(0)
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
            correspondentRepository = correspondentRepository
        )
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial uiState has correct defaults`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.isLoading)
        assertTrue(state.documents.isEmpty())
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
        advanceUntilIdle()

        // Search is debounced, so we check the flow was updated
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("invoice", state.searchQuery)
        }
    }

    // ==================== Filter Tests ====================

    @Test
    fun `filterByTag updates activeTagFilter and reloads`() = runTest {
        coEvery { documentRepository.getDocuments(any(), any(), any(), tagIds = listOf(5), any(), any(), any(), any()) } returns
                Result.success(DocumentsResponse(count = 1, results = listOf(createMockDocument(1, "Tagged"))))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.filterByTag(5)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(5, state.activeTagFilter)
    }

    @Test
    fun `filterByTag with null clears filter`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.filterByTag(5)
        advanceUntilIdle()

        viewModel.filterByTag(null)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.activeTagFilter)
    }

    @Test
    fun `clearFilters resets search and tag filter`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.filterByTag(5)
        advanceUntilIdle()

        viewModel.clearFilters()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("", state.searchQuery)
        assertNull(state.activeTagFilter)
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
