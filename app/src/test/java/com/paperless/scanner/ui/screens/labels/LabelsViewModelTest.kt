package com.paperless.scanner.ui.screens.labels

import android.content.Context
import com.paperless.scanner.data.api.models.CustomField
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.CustomFieldRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
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
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class LabelsViewModelTest {

    private lateinit var context: Context
    private lateinit var tagRepository: TagRepository
    private lateinit var correspondentRepository: CorrespondentRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var customFieldRepository: CustomFieldRepository

    private lateinit var tagFlow: MutableStateFlow<List<Tag>>
    private lateinit var correspondentFlow: MutableStateFlow<List<Correspondent>>
    private lateinit var documentTypeFlow: MutableStateFlow<List<DocumentType>>
    private lateinit var customFieldFlow: MutableStateFlow<List<CustomField>>

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        customFieldRepository = mockk(relaxed = true)

        tagFlow = MutableStateFlow(emptyList())
        correspondentFlow = MutableStateFlow(emptyList())
        documentTypeFlow = MutableStateFlow(emptyList())
        customFieldFlow = MutableStateFlow(emptyList())

        every { tagRepository.observeTags() } returns tagFlow
        every { correspondentRepository.observeCorrespondents() } returns correspondentFlow
        every { documentTypeRepository.observeDocumentTypes() } returns documentTypeFlow
        every { customFieldRepository.observeCustomFields() } returns customFieldFlow

        coEvery { tagRepository.getTags(any()) } returns Result.success(emptyList())
        coEvery { correspondentRepository.getCorrespondents(any()) } returns Result.success(emptyList())
        coEvery { documentTypeRepository.getDocumentTypes(any()) } returns Result.success(emptyList())
        coEvery { customFieldRepository.getCustomFields(any()) } returns Result.success(emptyList())
        coEvery { customFieldRepository.isCustomFieldsApiAvailable() } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): LabelsViewModel = LabelsViewModel(
        context = context,
        tagRepository = tagRepository,
        correspondentRepository = correspondentRepository,
        documentTypeRepository = documentTypeRepository,
        customFieldRepository = customFieldRepository
    )

    @org.junit.Test
    fun `tab switch recomputes entities without manual trigger`() = runTest {
        tagFlow.value = listOf(Tag(id = 1, name = "Tag-A", color = "#FFFFFF", documentCount = 0))
        correspondentFlow.value = listOf(Correspondent(id = 10, name = "Corr-X", documentCount = 0))

        val viewModel = createViewModel()
        runCurrent()

        // Active tab defaults to TAG.
        org.junit.Assert.assertEquals(
            listOf("Tag-A"),
            viewModel.uiState.value.entities.map { it.name }
        )

        viewModel.setEntityType(EntityType.CORRESPONDENT)
        runCurrent()

        org.junit.Assert.assertEquals(
            listOf("Corr-X"),
            viewModel.uiState.value.entities.map { it.name }
        )
    }

    @org.junit.Test
    fun `search query update flows into uiState entities`() = runTest {
        tagFlow.value = listOf(
            Tag(id = 1, name = "Invoice", color = "#FFFFFF", documentCount = 0),
            Tag(id = 2, name = "Receipt", color = "#FFFFFF", documentCount = 0)
        )

        val viewModel = createViewModel()
        runCurrent()
        org.junit.Assert.assertEquals(2, viewModel.uiState.value.entities.size)

        viewModel.search("inv")
        runCurrent()

        org.junit.Assert.assertEquals(
            listOf("Invoice"),
            viewModel.uiState.value.entities.map { it.name }
        )
    }

    @org.junit.Test
    fun `late subscriber sees latest entities on first emission`() = runTest {
        tagFlow.value = listOf(Tag(id = 1, name = "Tag-A", color = "#FFFFFF", documentCount = 0))

        val viewModel = createViewModel()
        runCurrent()

        // Late subscriber: collect AFTER the source flow has emitted and the VM has processed it.
        val firstEmission = viewModel.uiState.value
        org.junit.Assert.assertEquals(
            listOf("Tag-A"),
            firstEmission.entities.map { it.name }
        )
    }

    @org.junit.Test
    fun `interleaved tag and correspondent emissions stay consistent on TAG tab`() = runTest {
        val viewModel = createViewModel()
        runCurrent()

        // Active tab is TAG. Emit on the correspondent flow first to ensure
        // the unrelated source does not leak into uiState.entities.
        correspondentFlow.value = listOf(Correspondent(id = 99, name = "Corr-Leak", documentCount = 0))
        runCurrent()

        org.junit.Assert.assertTrue(
            "uiState.entities must not contain a correspondent while TAG is the active tab",
            viewModel.uiState.value.entities.none { it.entityType == EntityType.CORRESPONDENT }
        )

        // Now emit on the tag flow — this MUST land in uiState.entities.
        tagFlow.value = listOf(Tag(id = 1, name = "Tag-A", color = "#FFFFFF", documentCount = 0))
        runCurrent()

        org.junit.Assert.assertEquals(
            listOf("Tag-A" to EntityType.TAG),
            viewModel.uiState.value.entities.map { it.name to it.entityType }
        )

        // Switch to CORRESPONDENT — the correspondent that arrived earlier must
        // now be the visible state, with no tag bleed-through.
        viewModel.setEntityType(EntityType.CORRESPONDENT)
        runCurrent()

        org.junit.Assert.assertEquals(
            listOf("Corr-Leak" to EntityType.CORRESPONDENT),
            viewModel.uiState.value.entities.map { it.name to it.entityType }
        )
    }
}
