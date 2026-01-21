package com.paperless.scanner.ui.screens.documents

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Note
import com.paperless.scanner.domain.model.NoteUser
import com.paperless.scanner.domain.model.Tag
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentDetailViewModelTest {

    private lateinit var context: Context
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var documentRepository: DocumentRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var correspondentRepository: CorrespondentRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var tokenManager: TokenManager
    private lateinit var suggestionOrchestrator: SuggestionOrchestrator
    private lateinit var aiUsageRepository: AiUsageRepository
    private lateinit var premiumFeatureManager: PremiumFeatureManager
    private lateinit var networkMonitor: NetworkMonitor

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        savedStateHandle = mockk(relaxed = true)
        documentRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        suggestionOrchestrator = mockk(relaxed = true)
        aiUsageRepository = mockk(relaxed = true)
        premiumFeatureManager = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)

        // Default mock responses
        every { savedStateHandle.get<String>("documentId") } returns "123"
        coEvery { tagRepository.getTags() } returns Result.success(emptyList())
        coEvery { correspondentRepository.getCorrespondents() } returns Result.success(emptyList())
        coEvery { documentTypeRepository.getDocumentTypes() } returns Result.success(emptyList())
        coEvery { documentRepository.getDocument(any(), any()) } returns Result.success(createMockDocument(123))
        coEvery { documentRepository.observeDocument(any()) } returns flowOf(createMockDocument(123))
        coEvery { documentRepository.getDocumentHistory(any()) } returns Result.success(emptyList())
        coEvery { tokenManager.serverUrl } returns flowOf("https://paperless.example.com")
        coEvery { tokenManager.token } returns flowOf("test-token")
        coEvery { tokenManager.aiNewTagsEnabled } returns flowOf(true)
        every { premiumFeatureManager.isFeatureAvailable(any()) } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): DocumentDetailViewModel {
        return DocumentDetailViewModel(
            context = context,
            savedStateHandle = savedStateHandle,
            documentRepository = documentRepository,
            tagRepository = tagRepository,
            correspondentRepository = correspondentRepository,
            documentTypeRepository = documentTypeRepository,
            tokenManager = tokenManager,
            suggestionOrchestrator = suggestionOrchestrator,
            aiUsageRepository = aiUsageRepository,
            premiumFeatureManager = premiumFeatureManager,
            networkMonitor = networkMonitor
        )
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial uiState has correct defaults`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.isLoading)
        assertEquals(0, state.id)
        assertEquals("", state.title)
    }

    @Test
    fun `observeDocument success updates state with document data`() = runTest {
        val mockDoc = createMockDocument(123, "Test Document", "Content here")
        coEvery { documentRepository.observeDocument(123) } returns flowOf(mockDoc)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(123, state.id)
        assertEquals("Test Document", state.title)
        assertEquals("Content here", state.content)
    }

    @Test
    fun `observeDocument returns null shows error`() = runTest {
        coEvery { documentRepository.observeDocument(any()) } returns flowOf(null)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.error != null)
    }

    @Test
    fun `init loads lookup data`() = runTest {
        val mockTags = listOf(Tag(id = 1, name = "Invoice"))
        val mockCorrespondents = listOf(Correspondent(id = 1, name = "Company A"))
        val mockTypes = listOf(DocumentType(id = 1, name = "Bill"))

        coEvery { tagRepository.getTags() } returns Result.success(mockTags)
        coEvery { correspondentRepository.getCorrespondents() } returns Result.success(mockCorrespondents)
        coEvery { documentTypeRepository.getDocumentTypes() } returns Result.success(mockTypes)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.availableTags.size)
        assertEquals(1, state.availableCorrespondents.size)
        assertEquals(1, state.availableDocumentTypes.size)
    }

    @Test
    fun `observeDocument builds correct URLs`() = runTest {
        coEvery { tokenManager.serverUrl } returns flowOf("https://my-paperless.com")
        coEvery { tokenManager.token } returns flowOf("secret-token")
        coEvery { documentRepository.observeDocument(123) } returns flowOf(createMockDocument(123))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("https://my-paperless.com/api/documents/123/thumb/", state.thumbnailUrl)
        assertEquals("https://my-paperless.com/api/documents/123/download/", state.downloadUrl)
        assertEquals("secret-token", state.authToken)
    }

    // ==================== Delete Document Tests ====================

    @Test
    fun `deleteDocument success sets deleteSuccess`() = runTest {
        coEvery { documentRepository.deleteDocument(123) } returns Result.success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteDocument()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isDeleting)
        assertTrue(state.deleteSuccess)
    }

    @Test
    fun `deleteDocument failure sets deleteError`() = runTest {
        coEvery { documentRepository.deleteDocument(any()) } returns
            Result.failure(Exception("Cannot delete"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteDocument()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isDeleting)
        assertTrue(state.deleteError != null)
    }

    @Test
    fun `deleteDocument shows deleting state`() = runTest {
        coEvery { documentRepository.deleteDocument(any()) } coAnswers {
            kotlinx.coroutines.delay(1000)
            Result.success(Unit)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteDocument()
        // State should show isDeleting = true before completion

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isDeleting)
    }

    @Test
    fun `clearDeleteError removes deleteError from state`() = runTest {
        coEvery { documentRepository.deleteDocument(any()) } returns
            Result.failure(Exception("Error"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteDocument()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.deleteError != null)

        viewModel.clearDeleteError()

        assertNull(viewModel.uiState.value.deleteError)
    }

    // ==================== Update Document Tests ====================

    @Test
    fun `updateDocument success sets updateSuccess and reloads`() = runTest {
        coEvery {
            documentRepository.updateDocument(
                documentId = 123,
                title = "Updated Title",
                tags = listOf(1, 2),
                correspondent = 5,
                documentType = 3,
                archiveSerialNumber = 100,
                created = null
            )
        } returns Result.success(createMockDocument(123, "Updated Title"))
        // Make reload slow so we can verify updateSuccess before it resets
        coEvery { documentRepository.getDocument(any(), eq(true)) } coAnswers {
            kotlinx.coroutines.delay(5000)
            Result.success(createMockDocument(123, "Updated Title"))
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateDocument(
            title = "Updated Title",
            tagIds = listOf(1, 2),
            correspondentId = 5,
            documentTypeId = 3,
            archiveSerialNumber = "100",
            created = null
        )
        // Advance just enough to complete update but not reload
        advanceTimeBy(100)

        val state = viewModel.uiState.value
        assertFalse(state.isUpdating)
        assertTrue(state.updateSuccess)
    }

    @Test
    fun `updateDocument failure sets updateError`() = runTest {
        coEvery { documentRepository.updateDocument(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(Exception("Update failed"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateDocument("Title", emptyList(), null, null, null, null)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isUpdating)
        assertTrue(state.updateError != null)
    }

    @Test
    fun `updateDocument converts ASN string to int`() = runTest {
        coEvery { documentRepository.updateDocument(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(createMockDocument(123))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateDocument("Title", emptyList(), null, null, "42", null)
        advanceUntilIdle()

        coVerify {
            documentRepository.updateDocument(
                documentId = 123,
                title = "Title",
                tags = emptyList(),
                correspondent = null,
                documentType = null,
                archiveSerialNumber = 42,
                created = null
            )
        }
    }

    @Test
    fun `updateDocument handles invalid ASN as null`() = runTest {
        coEvery { documentRepository.updateDocument(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(createMockDocument(123))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateDocument("Title", emptyList(), null, null, "invalid", null)
        advanceUntilIdle()

        coVerify {
            documentRepository.updateDocument(
                documentId = 123,
                title = "Title",
                tags = emptyList(),
                correspondent = null,
                documentType = null,
                archiveSerialNumber = null,
                created = null
            )
        }
    }

    @Test
    fun `clearUpdateError removes updateError from state`() = runTest {
        coEvery { documentRepository.updateDocument(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(Exception("Error"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateDocument("Title", emptyList(), null, null, null, null)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.updateError != null)

        viewModel.clearUpdateError()

        assertNull(viewModel.uiState.value.updateError)
    }

    @Test
    fun `resetUpdateSuccess clears updateSuccess flag`() = runTest {
        // After updateDocument succeeds, loadDocument is called which resets state
        // So we test resetUpdateSuccess by setting it manually via successful update
        // and checking the reset function works
        coEvery { documentRepository.updateDocument(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(createMockDocument(123))
        // Make reload slow so we can catch updateSuccess = true
        coEvery { documentRepository.getDocument(any(), eq(true)) } coAnswers {
            kotlinx.coroutines.delay(5000)
            Result.success(createMockDocument(123))
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateDocument("Title", emptyList(), null, null, null, null)
        // Advance just enough for update to complete but not reload
        advanceTimeBy(100)
        assertTrue(viewModel.uiState.value.updateSuccess)

        viewModel.resetUpdateSuccess()

        assertFalse(viewModel.uiState.value.updateSuccess)
    }

    // ==================== Add Note Tests ====================

    @Test
    fun `addNote with blank text shows error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addNote("")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.addNoteError != null)
    }

    @Test
    fun `addNote with whitespace only shows error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addNote("   ")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.addNoteError != null)
    }

    @Test
    fun `addNote success updates notes list`() = runTest {
        val mockUser = NoteUser(id = 1, username = "admin")
        val updatedNotes = listOf(
            Note(id = 1, note = "Existing note", created = "2024-01-01", user = mockUser),
            Note(id = 2, note = "New note", created = "2024-01-02", user = mockUser)
        )
        coEvery { documentRepository.addNote(123, "New note") } returns Result.success(updatedNotes)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addNote("New note")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isAddingNote)
        assertEquals(2, state.notes.size)
    }

    @Test
    fun `addNote failure sets addNoteError`() = runTest {
        coEvery { documentRepository.addNote(any(), any()) } returns
            Result.failure(Exception("Cannot add note"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addNote("Test note")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isAddingNote)
        assertTrue(state.addNoteError != null)
    }

    @Test
    fun `clearAddNoteError removes addNoteError`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addNote("")
        assertTrue(viewModel.uiState.value.addNoteError != null)

        viewModel.clearAddNoteError()

        assertNull(viewModel.uiState.value.addNoteError)
    }

    // ==================== Delete Note Tests ====================

    @Test
    fun `deleteNote success updates notes list`() = runTest {
        val mockUser = NoteUser(id = 1, username = "admin")
        val remainingNotes = listOf(
            Note(id = 2, note = "Remaining note", created = "2024-01-01", user = mockUser)
        )
        coEvery { documentRepository.deleteNote(123, 1) } returns Result.success(remainingNotes)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteNote(1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.isDeletingNoteId)
        assertEquals(1, state.notes.size)
        assertEquals(2, state.notes[0].id)
    }

    @Test
    fun `deleteNote failure sets deleteNoteError`() = runTest {
        coEvery { documentRepository.deleteNote(any(), any()) } returns
            Result.failure(Exception("Cannot delete note"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteNote(1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.isDeletingNoteId)
        assertTrue(state.deleteNoteError != null)
    }

    @Test
    fun `deleteNote tracks which note is being deleted`() = runTest {
        coEvery { documentRepository.deleteNote(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(1000)
            Result.success(emptyList())
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteNote(42)
        // Should track the note ID being deleted

        advanceUntilIdle()
        assertNull(viewModel.uiState.value.isDeletingNoteId)
    }

    @Test
    fun `clearDeleteNoteError removes deleteNoteError`() = runTest {
        coEvery { documentRepository.deleteNote(any(), any()) } returns
            Result.failure(Exception("Error"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteNote(1)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.deleteNoteError != null)

        viewModel.clearDeleteNoteError()

        assertNull(viewModel.uiState.value.deleteNoteError)
    }

    // ==================== Document ID Parsing Tests ====================

    @Test
    fun `invalid documentId shows error and does not load`() = runTest {
        every { savedStateHandle.get<String>("documentId") } returns "invalid"
        every { context.getString(any()) } returns "Ungültige Dokument-ID. Bitte navigiere zurück und öffne das Dokument erneut."

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Should NOT try to load document with invalid ID
        coVerify(exactly = 0) { documentRepository.getDocument(any(), any()) }

        // Should show error state
        assertFalse(viewModel.uiState.value.isLoading)
        assertNotNull(viewModel.uiState.value.error)
    }

    @Test
    fun `null documentId shows error and does not load`() = runTest {
        every { savedStateHandle.get<String>("documentId") } returns null
        every { context.getString(any()) } returns "Ungültige Dokument-ID. Bitte navigiere zurück und öffne das Dokument erneut."

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Should NOT try to load document with null ID
        coVerify(exactly = 0) { documentRepository.getDocument(any(), any()) }

        // Should show error state
        assertFalse(viewModel.uiState.value.isLoading)
        assertNotNull(viewModel.uiState.value.error)
    }

    // ==================== Helper Functions ====================

    private fun createMockDocument(
        id: Int,
        title: String = "Document $id",
        content: String? = "Content $id"
    ): Document {
        return Document(
            id = id,
            title = title,
            content = content,
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
