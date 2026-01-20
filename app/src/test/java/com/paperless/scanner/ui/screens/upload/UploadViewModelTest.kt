package com.paperless.scanner.ui.screens.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import app.cash.turbine.test
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.TaskRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.ai.paperlessgpt.PaperlessGptRepository
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.util.FileUtils
import com.paperless.scanner.util.NetworkUtils
import com.paperless.scanner.utils.StorageUtil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * BEST PRACTICE: Updated tests for reactive Flow-based UploadViewModel.
 *
 * Key Changes:
 * - Removed obsolete load*() method tests (now using reactive Flows)
 * - Updated network checks to use NetworkMonitor instead of NetworkUtils
 * - Mocking reactive Flows (observeTags, observeDocumentTypes, observeCorrespondents)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadViewModelTest {

    private lateinit var context: Context
    private lateinit var viewModel: UploadViewModel
    private lateinit var documentRepository: DocumentRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var correspondentRepository: CorrespondentRepository
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var networkUtils: NetworkUtils
    private lateinit var analyticsService: AnalyticsService
    private lateinit var suggestionOrchestrator: SuggestionOrchestrator
    private lateinit var aiUsageRepository: AiUsageRepository
    private lateinit var premiumFeatureManager: PremiumFeatureManager
    private lateinit var paperlessGptRepository: PaperlessGptRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var tokenManager: TokenManager
    private lateinit var savedStateHandle: androidx.lifecycle.SavedStateHandle

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock SavedStateHandle
        savedStateHandle = mockk(relaxed = true)

        // Mock android.util.Log to prevent UnsatisfiedLinkError in unit tests
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any()) } returns 0

        // Mock FileUtils for offline queueing tests
        // FileUtils copies content URIs to local storage - mock to return the same URI
        mockkObject(FileUtils)
        every { FileUtils.isLocalFileUri(any()) } returns true
        every { FileUtils.copyToLocalStorage(any(), any()) } answers { secondArg() }
        every { FileUtils.deleteLocalCopy(any()) } returns true

        // Mock StorageUtil to prevent Android framework calls in unit tests
        mockkObject(StorageUtil)
        every { StorageUtil.checkStorageForUpload(any(), any()) } returns StorageUtil.StorageCheckResult(
            hasEnoughSpace = true,
            availableBytes = 1000000000L,  // 1GB
            requiredBytes = 0L,
            message = "Speicherplatz OK"
        )
        every { StorageUtil.hasSufficientSpace(any()) } returns true
        every { StorageUtil.validateFileSize(any(), any()) } returns Result.success(1000000L)  // 1MB

        context = mockk(relaxed = true)
        documentRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)
        uploadQueueRepository = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        networkUtils = mockk()
        analyticsService = mockk(relaxed = true)
        suggestionOrchestrator = mockk(relaxed = true)
        aiUsageRepository = mockk(relaxed = true)
        premiumFeatureManager = mockk(relaxed = true)
        paperlessGptRepository = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)

        // BEST PRACTICE: Mock reactive Flows for tags, documentTypes, correspondents
        every { tagRepository.observeTags() } returns flowOf(emptyList())
        every { documentTypeRepository.observeDocumentTypes() } returns flowOf(emptyList())
        every { correspondentRepository.observeCorrespondents() } returns flowOf(emptyList())

        // Mock AI usage limits Flow
        every { aiUsageRepository.observeCurrentMonthCallCount() } returns flowOf(0)

        // Mock TokenManager aiNewTagsEnabled Flow
        every { tokenManager.aiNewTagsEnabled } returns flowOf(true)

        every { networkMonitor.checkOnlineStatus() } returns true

        viewModel = UploadViewModel(
            context = context,
            savedStateHandle = savedStateHandle,
            documentRepository = documentRepository,
            tagRepository = tagRepository,
            documentTypeRepository = documentTypeRepository,
            correspondentRepository = correspondentRepository,
            networkUtils = networkUtils,
            uploadQueueRepository = uploadQueueRepository,
            networkMonitor = networkMonitor,
            analyticsService = analyticsService,
            suggestionOrchestrator = suggestionOrchestrator,
            aiUsageRepository = aiUsageRepository,
            premiumFeatureManager = premiumFeatureManager,
            paperlessGptRepository = paperlessGptRepository,
            taskRepository = taskRepository,
            tokenManager = tokenManager,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
        unmockkObject(FileUtils)
        unmockkObject(StorageUtil)
    }

    // ==================== Reactive Flow Tests ====================

    @Test
    fun `reactive tags flow automatically populates tags state`() = runTest {
        val mockTags = listOf(
            Tag(id = 1, name = "Zebra"),
            Tag(id = 2, name = "Alpha")
        )
        every { tagRepository.observeTags() } returns flowOf(mockTags)

        val newViewModel = UploadViewModel(
            context = context,
            savedStateHandle = savedStateHandle,
            documentRepository = documentRepository,
            tagRepository = tagRepository,
            documentTypeRepository = documentTypeRepository,
            correspondentRepository = correspondentRepository,
            networkUtils = networkUtils,
            uploadQueueRepository = uploadQueueRepository,
            networkMonitor = networkMonitor,
            analyticsService = analyticsService,
            suggestionOrchestrator = suggestionOrchestrator,
            aiUsageRepository = aiUsageRepository,
            premiumFeatureManager = premiumFeatureManager,
            paperlessGptRepository = paperlessGptRepository,
            taskRepository = taskRepository,
            tokenManager = tokenManager,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        newViewModel.tags.test {
            val tags = awaitItem()
            assertEquals(2, tags.size)
            // Sorted alphabetically (lowercase)
            assertEquals("Alpha", tags[0].name)
            assertEquals("Zebra", tags[1].name)
        }
    }

    @Test
    fun `reactive documentTypes flow automatically populates state`() = runTest {
        val mockTypes = listOf(
            DocumentType(id = 1, name = "Receipt"),
            DocumentType(id = 2, name = "Contract")
        )
        every { documentTypeRepository.observeDocumentTypes() } returns flowOf(mockTypes)

        val newViewModel = UploadViewModel(
            context = context,
            savedStateHandle = savedStateHandle,
            documentRepository = documentRepository,
            tagRepository = tagRepository,
            documentTypeRepository = documentTypeRepository,
            correspondentRepository = correspondentRepository,
            networkUtils = networkUtils,
            uploadQueueRepository = uploadQueueRepository,
            networkMonitor = networkMonitor,
            analyticsService = analyticsService,
            suggestionOrchestrator = suggestionOrchestrator,
            aiUsageRepository = aiUsageRepository,
            premiumFeatureManager = premiumFeatureManager,
            paperlessGptRepository = paperlessGptRepository,
            taskRepository = taskRepository,
            tokenManager = tokenManager,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        newViewModel.documentTypes.test {
            val types = awaitItem()
            assertEquals(2, types.size)
            assertEquals("Contract", types[0].name)
            assertEquals("Receipt", types[1].name)
        }
    }

    // ==================== Upload Document Tests ====================

    @Test
    fun `uploadDocument when offline queues upload`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkMonitor.checkOnlineStatus() } returns false

        viewModel.uiState.test {
            assertEquals(UploadUiState.Idle, awaitItem()) // Initial state

            viewModel.uploadDocument(uri = mockUri, title = "Test")
            advanceUntilIdle()

            assertEquals(UploadUiState.Queued, awaitItem())
        }

        coVerify {
            uploadQueueRepository.queueUpload(
                uri = mockUri,
                title = "Test",
                tagIds = emptyList(),
                documentTypeId = null,
                correspondentId = null
            )
        }
    }

    @Test
    fun `uploadDocument success updates state to Success`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-123")

        viewModel.uploadDocument(uri = mockUri, title = "Test Document")
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UploadUiState.Success)
            assertEquals("task-123", (state as UploadUiState.Success).taskId)
        }
    }

    @Test
    fun `uploadDocument failure updates state to Error`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.failure(Exception("Server error"))

        viewModel.uploadDocument(uri = mockUri)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UploadUiState.Error)
            assertEquals("Server error", (state as UploadUiState.Error).userMessage)
        }
    }

    @Test
    fun `uploadDocument calls repository with correct parameters`() = runTest {
        val mockUri = mockk<Uri>()
        val tagIds = listOf(1, 2, 3)
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-123")

        viewModel.uploadDocument(
            uri = mockUri,
            title = "My Document",
            tagIds = tagIds,
            documentTypeId = 5,
            correspondentId = 10
        )
        advanceUntilIdle()

        coVerify {
            documentRepository.uploadDocument(
                uri = mockUri,
                title = "My Document",
                tagIds = tagIds,
                documentTypeId = 5,
                correspondentId = 10,
                onProgress = any()
            )
        }
    }

    // ==================== Upload Multi-Page Document Tests ====================

    @Test
    fun `uploadMultiPageDocument when offline queues upload`() = runTest {
        val mockUris = listOf(mockk<Uri>(), mockk<Uri>())
        every { networkMonitor.checkOnlineStatus() } returns false

        viewModel.uiState.test {
            assertEquals(UploadUiState.Idle, awaitItem()) // Initial state

            viewModel.uploadMultiPageDocument(uris = mockUris)
            advanceUntilIdle()

            assertEquals(UploadUiState.Queued, awaitItem())
        }

        coVerify {
            uploadQueueRepository.queueMultiPageUpload(
                uris = mockUris,
                title = null,
                tagIds = emptyList(),
                documentTypeId = null,
                correspondentId = null
            )
        }
    }

    @Test
    fun `uploadMultiPageDocument success updates state to Success`() = runTest {
        val mockUris = listOf(mockk<Uri>(), mockk<Uri>())
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery {
            documentRepository.uploadMultiPageDocument(
                uris = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-456")

        viewModel.uploadMultiPageDocument(uris = mockUris)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UploadUiState.Success)
            assertEquals("task-456", (state as UploadUiState.Success).taskId)
        }
    }

    @Test
    fun `uploadMultiPageDocument failure updates state to Error`() = runTest {
        val mockUris = listOf(mockk<Uri>(), mockk<Uri>())
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery {
            documentRepository.uploadMultiPageDocument(
                uris = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.failure(Exception("PDF conversion failed"))

        viewModel.uploadMultiPageDocument(uris = mockUris)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UploadUiState.Error)
            assertEquals("PDF conversion failed", (state as UploadUiState.Error).userMessage)
        }
    }

    // ==================== Retry Tests ====================

    @Test
    fun `canRetry returns false initially`() {
        assertFalse(viewModel.canRetry())
    }

    @Test
    fun `canRetry returns true after failed upload stores params`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery {
            documentRepository.uploadDocument(any(), any(), any(), any(), any(), any())
        } returns Result.failure(Exception("Error"))

        viewModel.uploadDocument(uri = mockUri)
        advanceUntilIdle()

        assertTrue(viewModel.canRetry())
    }

    @Test
    fun `canRetry returns false after successful upload`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery {
            documentRepository.uploadDocument(any(), any(), any(), any(), any(), any())
        } returns Result.success("task-123")

        viewModel.uploadDocument(uri = mockUri)
        advanceUntilIdle()

        assertFalse(viewModel.canRetry())
    }

    @Test
    fun `retry triggers upload with stored parameters`() = runTest {
        val mockUri = mockk<Uri>()
        val tagIds = listOf(1, 2)

        // First attempt fails
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery {
            documentRepository.uploadDocument(any(), any(), any(), any(), any(), any())
        } returns Result.failure(Exception("Error"))

        viewModel.uploadDocument(
            uri = mockUri,
            title = "Test",
            tagIds = tagIds,
            documentTypeId = 3,
            correspondentId = 4
        )
        advanceUntilIdle()

        // Retry succeeds
        coEvery {
            documentRepository.uploadDocument(any(), any(), any(), any(), any(), any())
        } returns Result.success("task-retry")

        viewModel.retry()
        advanceUntilIdle()

        coVerify(exactly = 2) {
            documentRepository.uploadDocument(
                uri = mockUri,
                title = "Test",
                tagIds = tagIds,
                documentTypeId = 3,
                correspondentId = 4,
                onProgress = any()
            )
        }
    }

    // ==================== Reset State Tests ====================

    @Test
    fun `resetState sets uiState to Idle and clears retry params`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery {
            documentRepository.uploadDocument(any(), any(), any(), any(), any(), any())
        } returns Result.failure(Exception("Error"))

        viewModel.uploadDocument(uri = mockUri)
        advanceUntilIdle()

        // Verify we're in error state and can retry
        assertTrue(viewModel.uiState.value is UploadUiState.Error)
        assertTrue(viewModel.canRetry())

        // Reset
        viewModel.resetState()

        assertEquals(UploadUiState.Idle, viewModel.uiState.value)
        assertFalse(viewModel.canRetry())
    }

    // ==================== Create Tag Tests ====================

    @Test
    fun `createTag success updates state to Success`() = runTest {
        val newTag = Tag(id = 10, name = "NewTag", color = "#FF0000")
        coEvery { tagRepository.createTag(name = "NewTag", color = "#FF0000") } returns Result.success(newTag)

        viewModel.createTag(name = "NewTag", color = "#FF0000")
        advanceUntilIdle()

        viewModel.createTagState.test {
            val state = awaitItem()
            assertTrue("Expected CreateTagState.Success but got ${state::class.simpleName}", state is CreateTagState.Success)
            assertEquals(newTag, (state as CreateTagState.Success).tag)
        }
    }

    @Test
    fun `createTag failure updates state to Error`() = runTest {
        // Create a non-relaxed mock to ensure our stub is used
        val strictTagRepository = mockk<TagRepository>()
        every { strictTagRepository.observeTags() } returns flowOf(emptyList())
        coEvery { strictTagRepository.createTag(name = any(), color = any()) } returns
            Result.failure(Exception("Network error"))

        val testViewModel = UploadViewModel(
            context = context,
            savedStateHandle = savedStateHandle,
            documentRepository = documentRepository,
            tagRepository = strictTagRepository,
            documentTypeRepository = documentTypeRepository,
            correspondentRepository = correspondentRepository,
            networkUtils = networkUtils,
            uploadQueueRepository = uploadQueueRepository,
            networkMonitor = networkMonitor,
            analyticsService = analyticsService,
            suggestionOrchestrator = suggestionOrchestrator,
            aiUsageRepository = aiUsageRepository,
            premiumFeatureManager = premiumFeatureManager,
            paperlessGptRepository = paperlessGptRepository,
            taskRepository = taskRepository,
            tokenManager = tokenManager,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        testViewModel.createTag(name = "NewTag")
        advanceUntilIdle()

        testViewModel.createTagState.test {
            val state = awaitItem()
            assertTrue("Expected CreateTagState.Error but got ${state::class.simpleName}", state is CreateTagState.Error)
            assertEquals("Network error", (state as CreateTagState.Error).message)
        }
    }

    @Test
    fun `createTag recovers existing tag when duplicate error occurs`() = runTest {
        // Create a non-relaxed mock to ensure our stub is used
        val existingTag = Tag(id = 42, name = "Existing", color = "#FF0000", match = null)
        val strictTagRepository = mockk<TagRepository>()
        every { strictTagRepository.observeTags() } returns flowOf(listOf(existingTag))
        coEvery { strictTagRepository.getTags(forceRefresh = true) } returns Result.success(listOf(existingTag))
        coEvery { strictTagRepository.createTag(name = any(), color = any()) } returns
            Result.failure(Exception("Object violates owner / name unique constraint"))

        val testViewModel = UploadViewModel(
            context = context,
            savedStateHandle = savedStateHandle,
            documentRepository = documentRepository,
            tagRepository = strictTagRepository,
            documentTypeRepository = documentTypeRepository,
            correspondentRepository = correspondentRepository,
            networkUtils = networkUtils,
            uploadQueueRepository = uploadQueueRepository,
            networkMonitor = networkMonitor,
            analyticsService = analyticsService,
            suggestionOrchestrator = suggestionOrchestrator,
            aiUsageRepository = aiUsageRepository,
            premiumFeatureManager = premiumFeatureManager,
            paperlessGptRepository = paperlessGptRepository,
            taskRepository = taskRepository,
            tokenManager = tokenManager,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        testViewModel.createTag(name = "Existing")
        advanceUntilIdle()

        testViewModel.createTagState.test {
            val state = awaitItem()
            assertTrue("Expected CreateTagState.Success but got ${state::class.simpleName}", state is CreateTagState.Success)
            assertEquals(existingTag, (state as CreateTagState.Success).tag)
        }
    }

    @Test
    fun `resetCreateTagState sets createTagState to Idle`() = runTest {
        // Create a non-relaxed mock to ensure our stub is used
        val strictTagRepository = mockk<TagRepository>()
        every { strictTagRepository.observeTags() } returns flowOf(emptyList())
        coEvery { strictTagRepository.createTag(name = any(), color = any()) } returns
            Result.failure(Exception("Error"))

        val testViewModel = UploadViewModel(
            context = context,
            savedStateHandle = savedStateHandle,
            documentRepository = documentRepository,
            tagRepository = strictTagRepository,
            documentTypeRepository = documentTypeRepository,
            correspondentRepository = correspondentRepository,
            networkUtils = networkUtils,
            uploadQueueRepository = uploadQueueRepository,
            networkMonitor = networkMonitor,
            analyticsService = analyticsService,
            suggestionOrchestrator = suggestionOrchestrator,
            aiUsageRepository = aiUsageRepository,
            premiumFeatureManager = premiumFeatureManager,
            paperlessGptRepository = paperlessGptRepository,
            taskRepository = taskRepository,
            tokenManager = tokenManager,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        // Trigger error state
        testViewModel.createTag(name = "Test")
        advanceUntilIdle()

        // Use Turbine to wait for error state, then reset
        testViewModel.createTagState.test {
            val state = awaitItem()
            assertTrue(state is CreateTagState.Error)

            // Reset
            testViewModel.resetCreateTagState()

            assertEquals(CreateTagState.Idle, awaitItem())
        }
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial uiState is Idle`() {
        assertEquals(UploadUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `initial createTagState is Idle`() {
        assertEquals(CreateTagState.Idle, viewModel.createTagState.value)
    }

    @Test
    fun `initial tags list is empty`() {
        assertTrue(viewModel.tags.value.isEmpty())
    }

    @Test
    fun `initial documentTypes list is empty`() {
        assertTrue(viewModel.documentTypes.value.isEmpty())
    }

    @Test
    fun `initial correspondents list is empty`() {
        assertTrue(viewModel.correspondents.value.isEmpty())
    }
}
