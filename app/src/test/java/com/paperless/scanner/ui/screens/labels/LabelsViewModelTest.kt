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
}
