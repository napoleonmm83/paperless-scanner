package com.paperless.scanner.ui.screens.labels

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.Tag
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
class LabelsViewModelTest {

    private lateinit var context: Context
    private lateinit var tagRepository: TagRepository

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)

        // Default mock responses
        coEvery { tagRepository.getTags(any()) } returns Result.success(emptyList())
        every { tagRepository.observeTags() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): LabelsViewModel {
        return LabelsViewModel(
            context = context,
            tagRepository = tagRepository
        )
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial uiState has correct defaults`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.isLoading)
        assertTrue(state.labels.isEmpty())
        assertNull(state.error)
        assertEquals("", state.searchQuery)
    }

    @Test
    fun `initial state loads labels from repository`() = runTest {
        val mockTags = listOf(
            Tag(id = 1, name = "Invoice", color = "#FF0000", documentCount = 5),
            Tag(id = 2, name = "Receipt", color = "#00FF00", documentCount = 3)
        )
        coEvery { tagRepository.getTags(any()) } returns Result.success(mockTags)
        every { tagRepository.observeTags() } returns flowOf(mockTags)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.labels.size)
        assertEquals("Invoice", state.labels[0].name)
        assertEquals("Receipt", state.labels[1].name)
    }

    // ==================== Refresh Labels Tests ====================

    @Test
    fun `refresh success updates state with labels`() = runTest {
        val mockTags = listOf(
            Tag(id = 1, name = "Tag1", color = "#AABBCC", documentCount = 10)
        )
        coEvery { tagRepository.getTags(forceRefresh = true) } returns Result.success(mockTags)
        every { tagRepository.observeTags() } returns flowOf(mockTags)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.labels.size)
        assertEquals(10, state.labels[0].documentCount)
    }

    @Test
    fun `refresh failure updates state with error`() = runTest {
        coEvery { tagRepository.getTags(forceRefresh = true) } returns Result.failure(Exception("Network error"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.error != null)
    }

    // ==================== Search Tests ====================

    @Test
    fun `search filters labels by name`() = runTest {
        val mockTags = listOf(
            Tag(id = 1, name = "Invoice", documentCount = 5),
            Tag(id = 2, name = "Receipt", documentCount = 3),
            Tag(id = 3, name = "Important", documentCount = 2)
        )
        coEvery { tagRepository.getTags(any()) } returns Result.success(mockTags)
        every { tagRepository.observeTags() } returns flowOf(mockTags)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search("inv")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("inv", state.searchQuery)
        assertEquals(1, state.labels.size)
        assertEquals("Invoice", state.labels[0].name)
    }

    @Test
    fun `search with empty query shows all labels`() = runTest {
        val mockTags = listOf(
            Tag(id = 1, name = "Invoice", documentCount = 5),
            Tag(id = 2, name = "Receipt", documentCount = 3)
        )
        coEvery { tagRepository.getTags(any()) } returns Result.success(mockTags)
        every { tagRepository.observeTags() } returns flowOf(mockTags)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search("inv")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.labels.size)

        viewModel.search("")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.labels.size)
    }

    @Test
    fun `clearSearch resets search and shows all labels`() = runTest {
        val mockTags = listOf(
            Tag(id = 1, name = "Invoice", documentCount = 5),
            Tag(id = 2, name = "Receipt", documentCount = 3)
        )
        coEvery { tagRepository.getTags(any()) } returns Result.success(mockTags)
        every { tagRepository.observeTags() } returns flowOf(mockTags)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search("inv")
        advanceUntilIdle()

        viewModel.clearSearch()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("", state.searchQuery)
        assertEquals(2, state.labels.size)
    }

    // ==================== Create Label Tests ====================

    @Test
    fun `createLabel calls repository with correct parameters`() = runTest {
        coEvery { tagRepository.createTag(any(), any()) } returns Result.success(
            Tag(id = 99, name = "New Tag", color = "#FF5500")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.createLabel("New Tag", Color(0xFFFF5500))
        advanceUntilIdle()

        coVerify { tagRepository.createTag("New Tag", "#FF5500") }
    }

    @Test
    fun `createLabel failure sets error`() = runTest {
        coEvery { tagRepository.createTag(any(), any()) } returns Result.failure(Exception("Create failed"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.createLabel("Test", Color.Red)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error != null)
    }

    // ==================== Update Label Tests ====================

    @Test
    fun `updateLabel calls repository with correct parameters`() = runTest {
        coEvery { tagRepository.updateTag(any(), any(), any()) } returns Result.success(
            Tag(id = 1, name = "Updated", color = "#00FF00")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateLabel(1, "Updated", Color(0xFF00FF00))
        advanceUntilIdle()

        coVerify { tagRepository.updateTag(1, "Updated", "#00FF00") }
    }

    @Test
    fun `updateLabel failure sets error`() = runTest {
        coEvery { tagRepository.updateTag(any(), any(), any()) } returns Result.failure(Exception("Update failed"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateLabel(1, "Test", Color.Blue)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error != null)
    }

    // ==================== Delete Label Tests ====================

    @Test
    fun `deleteLabel calls repository with correct id`() = runTest {
        coEvery { tagRepository.deleteTag(any()) } returns Result.success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteLabel(42)
        advanceUntilIdle()

        coVerify { tagRepository.deleteTag(42) }
    }

    @Test
    fun `deleteLabel failure sets error`() = runTest {
        coEvery { tagRepository.deleteTag(any()) } returns Result.failure(Exception("Delete failed"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteLabel(1)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error != null)
    }

    // ==================== Load Documents for Label Tests ====================

    @Test
    fun `loadDocumentsForLabel success updates state`() = runTest {
        val mockDocuments = listOf(
            createMockDocument(1, "Doc 1"),
            createMockDocument(2, "Doc 2")
        )
        coEvery { tagRepository.getDocumentsForTag(5) } returns Result.success(mockDocuments)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadDocumentsForLabel(5)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoadingDocuments)
        assertEquals(2, state.documentsForLabel.size)
        assertEquals("Doc 1", state.documentsForLabel[0].title)
    }

    @Test
    fun `loadDocumentsForLabel failure clears documents`() = runTest {
        coEvery { tagRepository.getDocumentsForTag(any()) } returns Result.failure(Exception("Load failed"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadDocumentsForLabel(1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoadingDocuments)
        assertTrue(state.documentsForLabel.isEmpty())
    }

    @Test
    fun `clearDocumentsForLabel empties the list`() = runTest {
        val mockDocuments = listOf(createMockDocument(1, "Doc"))
        coEvery { tagRepository.getDocumentsForTag(any()) } returns Result.success(mockDocuments)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadDocumentsForLabel(1)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.documentsForLabel.size)

        viewModel.clearDocumentsForLabel()

        assertTrue(viewModel.uiState.value.documentsForLabel.isEmpty())
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `clearError removes error from state`() = runTest {
        coEvery { tagRepository.getTags(any()) } returns Result.failure(Exception("Error"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error != null)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    // ==================== Reset State Tests ====================

    @Test
    fun `resetState clears all state and reloads`() = runTest {
        val mockTags = listOf(Tag(id = 1, name = "Test", documentCount = 1))
        coEvery { tagRepository.getTags(any()) } returns Result.success(mockTags)
        every { tagRepository.observeTags() } returns flowOf(mockTags)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search("test")
        advanceUntilIdle()

        viewModel.resetState()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("", state.searchQuery)
    }

    // ==================== Reactive Flow Tests ====================

    @Test
    fun `observeTagsReactively updates UI when tags change`() = runTest {
        val initialTags = listOf(Tag(id = 1, name = "Initial", documentCount = 1))
        val updatedTags = listOf(
            Tag(id = 1, name = "Initial", documentCount = 1),
            Tag(id = 2, name = "New Tag", documentCount = 0)
        )

        coEvery { tagRepository.getTags(any()) } returns Result.success(initialTags)
        every { tagRepository.observeTags() } returns flowOf(updatedTags)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Flow should have updated with new tags
        assertEquals(2, viewModel.uiState.value.labels.size)
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
