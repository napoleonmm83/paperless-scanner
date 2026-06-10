package com.paperless.scanner.ui.screens.scan

import androidx.lifecycle.SavedStateHandle
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Tag
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #296: covers the inline tag-creation dialog state in [StepByStepMetadataViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StepByStepMetadataViewModelTest {

    private lateinit var tagRepository: TagRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var correspondentRepository: CorrespondentRepository

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tagRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)

        every { tagRepository.observeTags() } returns MutableStateFlow(emptyList())
        every { documentTypeRepository.observeDocumentTypes() } returns MutableStateFlow(emptyList())
        every { correspondentRepository.observeCorrespondents() } returns MutableStateFlow(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = StepByStepMetadataViewModel(
        savedStateHandle = SavedStateHandle(),
        tagRepository = tagRepository,
        documentTypeRepository = documentTypeRepository,
        correspondentRepository = correspondentRepository,
    )

    @Test
    fun `openCreateTagDialog shows the dialog and resets the error`() = runTest {
        val viewModel = createViewModel()

        viewModel.openCreateTagDialog()

        assertTrue(viewModel.uiState.value.showCreateTagDialog)
        assertFalse(viewModel.uiState.value.createTagFailed)
    }

    @Test
    fun `closeCreateTagDialog hides the dialog`() = runTest {
        val viewModel = createViewModel()
        viewModel.openCreateTagDialog()

        viewModel.closeCreateTagDialog()

        assertFalse(viewModel.uiState.value.showCreateTagDialog)
    }

    @Test
    fun `createTag closes the dialog on success`() = runTest {
        coEvery { tagRepository.createTag(any()) } returns
            Result.success(Tag(id = 7, name = "New", color = "#FFFFFF", documentCount = 0))
        val viewModel = createViewModel()
        viewModel.openCreateTagDialog()

        viewModel.createTag("New")
        runCurrent()

        assertFalse(viewModel.uiState.value.isCreatingTag)
        assertFalse(viewModel.uiState.value.showCreateTagDialog)
        assertFalse(viewModel.uiState.value.createTagFailed)
    }

    @Test
    fun `createTag keeps the dialog open and flags the failure`() = runTest {
        coEvery { tagRepository.createTag(any()) } returns Result.failure(Exception("boom"))
        val viewModel = createViewModel()
        viewModel.openCreateTagDialog()

        viewModel.createTag("New")
        runCurrent()

        assertFalse(viewModel.uiState.value.isCreatingTag)
        assertTrue(viewModel.uiState.value.showCreateTagDialog)
        assertTrue(viewModel.uiState.value.createTagFailed)
    }

    @Test
    fun `created tag arrives in tags via the reactive flow`() = runTest {
        val tagFlow = MutableStateFlow<List<Tag>>(emptyList())
        every { tagRepository.observeTags() } returns tagFlow
        val viewModel = createViewModel()
        runCurrent()
        assertEquals(0, viewModel.tags.value.size)

        // Simulate the Room flow re-emitting after a successful insert.
        tagFlow.value = listOf(Tag(id = 7, name = "New", color = "#FFFFFF", documentCount = 0))
        runCurrent()

        assertEquals(listOf(7), viewModel.tags.value.map { it.id })
    }
}
