package com.paperless.scanner.ui.screens.labels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ): LabelsViewModel = LabelsViewModel(
        context = context,
        tagRepository = tagRepository,
        correspondentRepository = correspondentRepository,
        documentTypeRepository = documentTypeRepository,
        customFieldRepository = customFieldRepository,
        savedStateHandle = savedStateHandle
    )

    @Test
    fun `tab switch recomputes entities without manual trigger`() = runTest {
        tagFlow.value = listOf(Tag(id = 1, name = "Tag-A", color = "#FFFFFF", documentCount = 0))
        correspondentFlow.value = listOf(Correspondent(id = 10, name = "Corr-X", documentCount = 0))

        val viewModel = createViewModel()

        viewModel.uiState.test {
            // Drive the combine pipeline and collect the settled state.
            // expectMostRecentItem() drains all buffered emissions and returns the last one,
            // avoiding fragile "skip N items" counts when intermediate settings-update
            // emissions arrive between the combine re-derivation step.
            runCurrent()
            val initialState = expectMostRecentItem()

            // Active tab defaults to TAG.
            assertEquals(
                listOf("Tag-A"),
                initialState.entities.map { it.name }
            )

            viewModel.setEntityType(EntityType.CORRESPONDENT)
            runCurrent()
            val afterSwitch = expectMostRecentItem()

            assertEquals(
                listOf("Corr-X"),
                afterSwitch.entities.map { it.name }
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search query update flows into uiState entities`() = runTest {
        tagFlow.value = listOf(
            Tag(id = 1, name = "Invoice", color = "#FFFFFF", documentCount = 0),
            Tag(id = 2, name = "Receipt", color = "#FFFFFF", documentCount = 0)
        )

        val viewModel = createViewModel()

        viewModel.uiState.test {
            runCurrent()
            val initialState = expectMostRecentItem()
            assertEquals(2, initialState.entities.size)

            viewModel.search("inv")
            runCurrent()
            val filteredState = expectMostRecentItem()

            assertEquals(
                listOf("Invoice"),
                filteredState.entities.map { it.name }
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `late subscriber sees latest entities on first emission`() = runTest {
        tagFlow.value = listOf(Tag(id = 1, name = "Tag-A", color = "#FFFFFF", documentCount = 0))

        val viewModel = createViewModel()
        // Drive the combine pipeline BEFORE subscribing — the settled StateFlow value
        // will already reflect the tags, so a late subscriber's first awaitItem() directly
        // returns the non-empty state. This is the strongest test of the StateFlow replay contract.
        runCurrent()

        viewModel.uiState.test {
            val firstEmission = awaitItem()
            assertEquals(
                listOf("Tag-A"),
                firstEmission.entities.map { it.name }
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `interleaved tag and correspondent emissions stay consistent on TAG tab`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            // Drive the initial combine pipeline. All source flows are empty so the combine
            // produces entities=emptyList() — same as the StateFlow's initial value — and
            // StateFlow suppresses the duplicate. expectMostRecentItem() returns whatever
            // is buffered (the initial replay), then we proceed with mutations.
            runCurrent()
            expectMostRecentItem()

            // Active tab is TAG. Emit on the correspondent flow first to ensure
            // the unrelated source does not leak into uiState.entities.
            // Because the TAG entity list is still empty, the combine produces the same
            // empty entities list → StateFlow suppresses the duplicate → no new emission.
            // We verify via the current snapshot value rather than awaitItem / expectMostRecentItem.
            correspondentFlow.value = listOf(Correspondent(id = 99, name = "Corr-Leak", documentCount = null))
            runCurrent()

            assertTrue(
                "uiState.entities must not contain a correspondent while TAG is the active tab",
                viewModel.uiState.value.entities.none { it.entityType == EntityType.CORRESPONDENT }
            )

            // Now emit on the tag flow — entities change → StateFlow emits a new item.
            tagFlow.value = listOf(Tag(id = 1, name = "Tag-A", color = "#FFFFFF", documentCount = 0))
            runCurrent()
            val afterTagEmit = expectMostRecentItem()

            assertEquals(
                listOf("Tag-A" to EntityType.TAG),
                afterTagEmit.entities.map { it.name to it.entityType }
            )

            // Switch to CORRESPONDENT — the correspondent that arrived earlier must
            // now be the visible state, with no tag bleed-through.
            viewModel.setEntityType(EntityType.CORRESPONDENT)
            runCurrent()
            val afterTabSwitch = expectMostRecentItem()

            // documentCount = null in the domain model must be coalesced to 0 by the ViewModel mapping.
            assertEquals(0, afterTabSwitch.entities.first().documentCount)

            assertEquals(
                listOf("Corr-Leak" to EntityType.CORRESPONDENT),
                afterTabSwitch.entities.map { it.name to it.entityType }
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ====================================================================
    // Issue #104 — SavedStateHandle round-trip for sheet/dialog state
    // ====================================================================

    @Test
    fun `openCreateSheet writes through to SavedStateHandle`() = runTest {
        val handle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle = handle)

        viewModel.openCreateSheet()
        runCurrent()

        assertEquals(true, handle.get<Boolean>(LabelsViewModel.KEY_SHOW_CREATE_SHEET))
        assertEquals(null, handle.get<Int>(LabelsViewModel.KEY_EDITING_ENTITY_ID))
        assertTrue(viewModel.uiState.value.showCreateSheet)
        assertEquals(null, viewModel.uiState.value.editingEntityId)
    }

    @Test
    fun `startEditingEntity persists ID and opens sheet`() = runTest {
        val handle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle = handle)

        viewModel.startEditingEntity(42)
        runCurrent()

        assertEquals(42, handle.get<Int>(LabelsViewModel.KEY_EDITING_ENTITY_ID))
        assertEquals(true, handle.get<Boolean>(LabelsViewModel.KEY_SHOW_CREATE_SHEET))
        assertEquals(42, viewModel.uiState.value.editingEntityId)
        assertTrue(viewModel.uiState.value.showCreateSheet)
    }

    @Test
    fun `closeCreateSheet clears sheet visibility and edit target in both places`() = runTest {
        val handle = SavedStateHandle().apply {
            set(LabelsViewModel.KEY_SHOW_CREATE_SHEET, true)
            set(LabelsViewModel.KEY_EDITING_ENTITY_ID, 7)
        }
        val viewModel = createViewModel(savedStateHandle = handle)

        viewModel.closeCreateSheet()
        runCurrent()

        assertEquals(false, handle.get<Boolean>(LabelsViewModel.KEY_SHOW_CREATE_SHEET))
        assertEquals(null, handle.get<Int>(LabelsViewModel.KEY_EDITING_ENTITY_ID))
        assertEquals(false, viewModel.uiState.value.showCreateSheet)
        assertEquals(null, viewModel.uiState.value.editingEntityId)
    }

    @Test
    fun `openSortFilterSheet and closeSortFilterSheet round-trip via SavedStateHandle`() = runTest {
        val handle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle = handle)

        viewModel.openSortFilterSheet()
        runCurrent()
        assertEquals(true, handle.get<Boolean>(LabelsViewModel.KEY_SHOW_SORT_FILTER_SHEET))
        assertTrue(viewModel.uiState.value.showSortFilterSheet)

        viewModel.closeSortFilterSheet()
        runCurrent()
        assertEquals(false, handle.get<Boolean>(LabelsViewModel.KEY_SHOW_SORT_FILTER_SHEET))
        assertEquals(false, viewModel.uiState.value.showSortFilterSheet)
    }

    @Test
    fun `search writes through to SavedStateHandle`() = runTest {
        val handle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle = handle)

        viewModel.search("invoice")
        runCurrent()

        assertEquals("invoice", handle.get<String>(LabelsViewModel.KEY_SEARCH_QUERY))
        assertEquals("invoice", viewModel.uiState.value.searchQuery)
    }

    @Test
    fun `process death restore — uiState reflects SavedStateHandle on second VM instance`() = runTest {
        // Simulates the AC: process death (or AppLock unlock) brings up a
        // fresh VM instance with the same Hilt-injected SavedStateHandle.
        val handle = SavedStateHandle().apply {
            set(LabelsViewModel.KEY_SEARCH_QUERY, "rechnung")
            set(LabelsViewModel.KEY_SHOW_CREATE_SHEET, true)
            set(LabelsViewModel.KEY_EDITING_ENTITY_ID, 99)
            set(LabelsViewModel.KEY_SHOW_SORT_FILTER_SHEET, true)
        }

        val viewModel = createViewModel(savedStateHandle = handle)
        runCurrent()

        val restored = viewModel.uiState.value
        assertEquals("rechnung", restored.searchQuery)
        assertTrue(restored.showCreateSheet)
        assertEquals(99, restored.editingEntityId)
        assertTrue(restored.showSortFilterSheet)
    }

    @Test
    fun `resetState wipes SavedStateHandle keys for sheet visibility`() = runTest {
        val handle = SavedStateHandle().apply {
            set(LabelsViewModel.KEY_SEARCH_QUERY, "stale")
            set(LabelsViewModel.KEY_SHOW_CREATE_SHEET, true)
            set(LabelsViewModel.KEY_EDITING_ENTITY_ID, 5)
            set(LabelsViewModel.KEY_SHOW_SORT_FILTER_SHEET, true)
        }
        val viewModel = createViewModel(savedStateHandle = handle)

        viewModel.resetState()
        runCurrent()

        assertEquals("", handle.get<String>(LabelsViewModel.KEY_SEARCH_QUERY))
        assertEquals(false, handle.get<Boolean>(LabelsViewModel.KEY_SHOW_CREATE_SHEET))
        assertEquals(null, handle.get<Int>(LabelsViewModel.KEY_EDITING_ENTITY_ID))
        assertEquals(false, handle.get<Boolean>(LabelsViewModel.KEY_SHOW_SORT_FILTER_SHEET))
    }
}
