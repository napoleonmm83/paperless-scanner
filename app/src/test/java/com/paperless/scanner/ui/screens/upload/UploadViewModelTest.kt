package com.paperless.scanner.ui.screens.upload

import android.net.Uri
import app.cash.turbine.test
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.util.NetworkUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadViewModelTest {

    private lateinit var viewModel: UploadViewModel
    private lateinit var documentRepository: DocumentRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var correspondentRepository: CorrespondentRepository
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var networkUtils: NetworkUtils

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        documentRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)
        uploadQueueRepository = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        networkUtils = mockk()

        every { networkMonitor.isOnline } returns MutableStateFlow(true)

        viewModel = UploadViewModel(
            documentRepository = documentRepository,
            tagRepository = tagRepository,
            documentTypeRepository = documentTypeRepository,
            correspondentRepository = correspondentRepository,
            uploadQueueRepository = uploadQueueRepository,
            networkMonitor = networkMonitor,
            networkUtils = networkUtils
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== Load Tags Tests ====================

    @Test
    fun `loadTags success updates tags state sorted alphabetically`() = runTest {
        val unsortedTags = listOf(
            Tag(id = 1, name = "Zebra"),
            Tag(id = 2, name = "Alpha"),
            Tag(id = 3, name = "beta")
        )
        coEvery { tagRepository.getTags() } returns Result.success(unsortedTags)

        viewModel.loadTags()
        runCurrent()

        viewModel.tags.test {
            val tags = awaitItem()
            assertEquals(3, tags.size)
            assertEquals("Alpha", tags[0].name)
            assertEquals("beta", tags[1].name)
            assertEquals("Zebra", tags[2].name)
        }
    }

    @Test
    fun `loadTags failure keeps tags empty`() = runTest {
        coEvery { tagRepository.getTags() } returns Result.failure(Exception("Network error"))

        viewModel.loadTags()
        runCurrent()

        viewModel.tags.test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    // ==================== Load Document Types Tests ====================

    @Test
    fun `loadDocumentTypes success updates documentTypes state sorted alphabetically`() = runTest {
        val unsortedTypes = listOf(
            DocumentType(id = 1, name = "Receipt"),
            DocumentType(id = 2, name = "Contract"),
            DocumentType(id = 3, name = "invoice")
        )
        coEvery { documentTypeRepository.getDocumentTypes() } returns Result.success(unsortedTypes)

        viewModel.loadDocumentTypes()
        runCurrent()

        viewModel.documentTypes.test {
            val types = awaitItem()
            assertEquals(3, types.size)
            assertEquals("Contract", types[0].name)
            assertEquals("invoice", types[1].name)
            assertEquals("Receipt", types[2].name)
        }
    }

    @Test
    fun `loadDocumentTypes failure keeps documentTypes empty`() = runTest {
        coEvery { documentTypeRepository.getDocumentTypes() } returns Result.failure(Exception("Error"))

        viewModel.loadDocumentTypes()
        runCurrent()

        viewModel.documentTypes.test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    // ==================== Load Correspondents Tests ====================

    @Test
    fun `loadCorrespondents success updates correspondents state sorted alphabetically`() = runTest {
        val unsortedCorrespondents = listOf(
            Correspondent(id = 1, name = "Vodafone"),
            Correspondent(id = 2, name = "Amazon"),
            Correspondent(id = 3, name = "bank")
        )
        coEvery { correspondentRepository.getCorrespondents() } returns Result.success(unsortedCorrespondents)

        viewModel.loadCorrespondents()
        runCurrent()

        viewModel.correspondents.test {
            val correspondents = awaitItem()
            assertEquals(3, correspondents.size)
            assertEquals("Amazon", correspondents[0].name)
            assertEquals("bank", correspondents[1].name)
            assertEquals("Vodafone", correspondents[2].name)
        }
    }

    @Test
    fun `loadCorrespondents failure keeps correspondents empty`() = runTest {
        coEvery { correspondentRepository.getCorrespondents() } returns Result.failure(Exception("Error"))

        viewModel.loadCorrespondents()
        runCurrent()

        viewModel.correspondents.test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    // ==================== Upload Document Tests ====================

    @Test
    fun `uploadDocument with no network shows error`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkUtils.isNetworkAvailable() } returns false

        viewModel.uploadDocument(uri = mockUri)
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UploadUiState.Error)
            assertEquals("Keine Netzwerkverbindung", (state as UploadUiState.Error).message)
        }
    }

    @Test
    fun `uploadDocument success updates state to Success`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkUtils.isNetworkAvailable() } returns true
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
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UploadUiState.Success)
            assertEquals("task-123", (state as UploadUiState.Success).taskId)
        }
    }

    @Test
    fun `uploadDocument failure updates state to Error`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkUtils.isNetworkAvailable() } returns true
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
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UploadUiState.Error)
            assertEquals("Server error", (state as UploadUiState.Error).message)
        }
    }

    @Test
    fun `uploadDocument calls repository with correct parameters`() = runTest {
        val mockUri = mockk<Uri>()
        val tagIds = listOf(1, 2, 3)
        every { networkUtils.isNetworkAvailable() } returns true
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
        runCurrent()

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
    fun `uploadMultiPageDocument with no network shows error`() = runTest {
        val mockUris = listOf(mockk<Uri>(), mockk<Uri>())
        every { networkUtils.isNetworkAvailable() } returns false

        viewModel.uploadMultiPageDocument(uris = mockUris)
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UploadUiState.Error)
            assertEquals("Keine Netzwerkverbindung", (state as UploadUiState.Error).message)
        }
    }

    @Test
    fun `uploadMultiPageDocument success updates state to Success`() = runTest {
        val mockUris = listOf(mockk<Uri>(), mockk<Uri>())
        every { networkUtils.isNetworkAvailable() } returns true
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
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UploadUiState.Success)
            assertEquals("task-456", (state as UploadUiState.Success).taskId)
        }
    }

    @Test
    fun `uploadMultiPageDocument failure updates state to Error`() = runTest {
        val mockUris = listOf(mockk<Uri>(), mockk<Uri>())
        every { networkUtils.isNetworkAvailable() } returns true
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
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UploadUiState.Error)
            assertEquals("PDF conversion failed", (state as UploadUiState.Error).message)
        }
    }

    // ==================== Retry Tests ====================

    @Test
    fun `canRetry returns false initially`() {
        assertFalse(viewModel.canRetry())
    }

    @Test
    fun `canRetry returns true after failed single upload`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkUtils.isNetworkAvailable() } returns false

        viewModel.uploadDocument(uri = mockUri)
        runCurrent()

        assertTrue(viewModel.canRetry())
    }

    @Test
    fun `canRetry returns true after failed multi-page upload`() = runTest {
        val mockUris = listOf(mockk<Uri>())
        every { networkUtils.isNetworkAvailable() } returns false

        viewModel.uploadMultiPageDocument(uris = mockUris)
        runCurrent()

        assertTrue(viewModel.canRetry())
    }

    @Test
    fun `canRetry returns false after successful upload`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkUtils.isNetworkAvailable() } returns true
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

        viewModel.uploadDocument(uri = mockUri)
        runCurrent()

        assertFalse(viewModel.canRetry())
    }

    @Test
    fun `retry triggers upload with stored parameters`() = runTest {
        val mockUri = mockk<Uri>()
        val tagIds = listOf(1, 2)
        every { networkUtils.isNetworkAvailable() } returns false

        // First attempt fails
        viewModel.uploadDocument(
            uri = mockUri,
            title = "Test",
            tagIds = tagIds,
            documentTypeId = 3,
            correspondentId = 4
        )
        runCurrent()

        // Retry with network available
        every { networkUtils.isNetworkAvailable() } returns true
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-retry")

        viewModel.retry()
        runCurrent()

        coVerify {
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
    fun `resetState sets uiState to Idle`() = runTest {
        val mockUri = mockk<Uri>()
        every { networkUtils.isNetworkAvailable() } returns false

        viewModel.uploadDocument(uri = mockUri)
        runCurrent()

        // Verify we're in error state
        assertTrue(viewModel.uiState.value is UploadUiState.Error)

        // Reset
        viewModel.resetState()

        viewModel.uiState.test {
            assertEquals(UploadUiState.Idle, awaitItem())
        }
    }

    // ==================== Create Tag Tests ====================

    @Test
    fun `createTag success adds tag to list and updates state`() = runTest {
        val newTag = Tag(id = 10, name = "NewTag", color = "#FF0000")
        coEvery { tagRepository.createTag(name = "NewTag", color = "#FF0000") } returns Result.success(newTag)

        viewModel.createTag(name = "NewTag", color = "#FF0000")
        runCurrent()

        viewModel.createTagState.test {
            val state = awaitItem()
            assertTrue(state is CreateTagState.Success)
            assertEquals(newTag, (state as CreateTagState.Success).tag)
        }

        viewModel.tags.test {
            val tags = awaitItem()
            assertTrue(tags.any { it.id == 10 && it.name == "NewTag" })
        }
    }

    @Test
    fun `createTag failure updates state to Error`() = runTest {
        coEvery { tagRepository.createTag(name = any(), color = any()) } returns
                Result.failure(Exception("Tag already exists"))

        viewModel.createTag(name = "Duplicate")
        runCurrent()

        viewModel.createTagState.test {
            val state = awaitItem()
            assertTrue(state is CreateTagState.Error)
            assertEquals("Tag already exists", (state as CreateTagState.Error).message)
        }
    }

    @Test
    fun `createTag adds tag in sorted position`() = runTest {
        val existingTags = listOf(
            Tag(id = 1, name = "Alpha"),
            Tag(id = 2, name = "Zulu")
        )
        coEvery { tagRepository.getTags() } returns Result.success(existingTags)

        viewModel.loadTags()
        runCurrent()

        val newTag = Tag(id = 3, name = "Middle")
        coEvery { tagRepository.createTag(name = "Middle", color = null) } returns Result.success(newTag)

        viewModel.createTag(name = "Middle")
        runCurrent()

        viewModel.tags.test {
            val tags = awaitItem()
            assertEquals(3, tags.size)
            assertEquals("Alpha", tags[0].name)
            assertEquals("Middle", tags[1].name)
            assertEquals("Zulu", tags[2].name)
        }
    }

    @Test
    fun `resetCreateTagState sets createTagState to Idle`() = runTest {
        coEvery { tagRepository.createTag(name = any(), color = any()) } returns
                Result.failure(Exception("Error"))

        viewModel.createTag(name = "Test")
        runCurrent()

        // Verify we're in error state
        assertTrue(viewModel.createTagState.value is CreateTagState.Error)

        // Reset
        viewModel.resetCreateTagState()

        viewModel.createTagState.test {
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
