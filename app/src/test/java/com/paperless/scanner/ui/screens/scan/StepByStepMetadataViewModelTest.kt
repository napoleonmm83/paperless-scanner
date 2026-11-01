package com.paperless.scanner.ui.screens.scan

import androidx.lifecycle.SavedStateHandle
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.ui.screens.upload.CreateCorrespondentState
import com.paperless.scanner.ui.screens.upload.usecase.CreateCorrespondentResult
import com.paperless.scanner.ui.screens.upload.usecase.UploadMetadataUseCase
import app.cash.turbine.test
import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred
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
    private lateinit var uploadMetadataUseCase: UploadMetadataUseCase

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tagRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)
        uploadMetadataUseCase = mockk(relaxed = true)

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
        uploadMetadataUseCase = uploadMetadataUseCase,
    )

    @Test
    fun `createCorrespondent goes Creating then Success`() = runTest {
        val created = Correspondent(id = 5, name = "ACME AG")
        val gate = CompletableDeferred<Unit>()
        coEvery { uploadMetadataUseCase.createCorrespondent("ACME AG") } coAnswers {
            gate.await() // hold the call open so the Creating state is observable
            CreateCorrespondentResult.Success(created)
        }
        val viewModel = createViewModel()

        viewModel.createCorrespondentState.test {
            assertEquals(CreateCorrespondentState.Idle, awaitItem())
            viewModel.createCorrespondent("ACME AG")
            assertEquals(CreateCorrespondentState.Creating, awaitItem())
            gate.complete(Unit)
            assertEquals(CreateCorrespondentState.Success(created), awaitItem())
            viewModel.resetCreateCorrespondentState()
            assertEquals(CreateCorrespondentState.Idle, awaitItem())
        }
    }

    @Test
    fun `page change clears a correspondent creation error`() = runTest {
        coEvery { uploadMetadataUseCase.createCorrespondent(any()) } returns
            CreateCorrespondentResult.Failure("No internet connection")
        val viewModel = createViewModel()
        viewModel.initializePages(listOf(mockk(relaxed = true), mockk(relaxed = true)))
        viewModel.createCorrespondent("ACME AG")
        runCurrent()

        viewModel.nextPage()

        assertEquals(CreateCorrespondentState.Idle, viewModel.createCorrespondentState.value)
    }

    @Test
    fun `createCorrespondent failure exposes the user-facing message`() = runTest {
        coEvery { uploadMetadataUseCase.createCorrespondent(any()) } returns
            CreateCorrespondentResult.Failure("No internet connection")
        val viewModel = createViewModel()

        viewModel.createCorrespondent("ACME AG")
        runCurrent()

        assertEquals(
            CreateCorrespondentState.Error("No internet connection"),
            viewModel.createCorrespondentState.value,
        )
    }

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
