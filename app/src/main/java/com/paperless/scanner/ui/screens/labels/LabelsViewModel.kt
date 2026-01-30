package com.paperless.scanner.ui.screens.labels

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.CustomFieldRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.util.DateFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LabelSortOption {
    NAME_ASC,
    NAME_DESC,
    COUNT_DESC,
    COUNT_ASC,
    NEWEST,
    OLDEST
}

enum class LabelFilterOption {
    ALL,
    WITH_DOCUMENTS,
    EMPTY,
    MANY_DOCUMENTS
}

/**
 * Entity types supported in the Labels Screen.
 * Determines which entities are shown in the active tab.
 */
enum class EntityType {
    TAG,
    CORRESPONDENT,
    DOCUMENT_TYPE,
    CUSTOM_FIELD
}

/**
 * Unified entity item for multi-entity support.
 * Represents Tags, Correspondents, Document Types, and Custom Fields.
 */
data class EntityItem(
    val id: Int,
    val name: String,
    val color: Color? = null,  // Only for Tags
    val documentCount: Int = 0,
    val entityType: EntityType,
    val dataType: String? = null  // Only for Custom Fields (e.g., "string", "integer", "monetary")
)

/**
 * State for pending entity deletion with document count info.
 */
data class PendingDeleteEntity(
    val id: Int,
    val name: String,
    val documentCount: Int,
    val entityType: EntityType
)

data class LabelsUiState(
    val currentEntityType: EntityType = EntityType.TAG,  // NEW: Active tab
    val entities: List<EntityItem> = emptyList(),  // RENAMED from labels
    val documentsForEntity: List<LabelDocument> = emptyList(),  // RENAMED from documentsForLabel
    val selectedEntity: EntityItem? = null,  // RENAMED from selectedLabel - BEST PRACTICE: Navigation state in ViewModel survives navigation
    val isLoading: Boolean = true,
    val isLoadingDocuments: Boolean = false,
    val isDeleting: Boolean = false,
    val pendingDeleteEntity: PendingDeleteEntity? = null,  // RENAMED from pendingDeleteLabel
    val isLoadingDeleteInfo: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val sortOption: LabelSortOption = LabelSortOption.NAME_ASC,
    val filterOption: LabelFilterOption = LabelFilterOption.ALL,
    val customFieldsAvailable: Boolean = false  // NEW: Feature flag for custom fields API
)

@HiltViewModel
class LabelsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tagRepository: TagRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val customFieldRepository: CustomFieldRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LabelsUiState())
    val uiState: StateFlow<LabelsUiState> = _uiState.asStateFlow()

    // Separate collections for each entity type (all as EntityItem)
    private var allTags: List<EntityItem> = emptyList()
    private var allCorrespondents: List<EntityItem> = emptyList()
    private var allDocumentTypes: List<EntityItem> = emptyList()
    private var allCustomFields: List<EntityItem> = emptyList()

    init {
        // BEST PRACTICE: Start Flow observer FIRST, then trigger API refresh
        observeTagsReactively()
        observeCorrespondentsReactively()
        observeDocumentTypesReactively()
        observeCustomFieldsReactively()
        detectCustomFieldsAvailability()
        refresh()
    }

    /**
     * Detects if Custom Fields API is available on the server.
     * Updates customFieldsAvailable in UiState to show/hide the tab.
     */
    private fun detectCustomFieldsAvailability() {
        viewModelScope.launch {
            val isAvailable = customFieldRepository.isCustomFieldsApiAvailable()
            _uiState.update { it.copy(customFieldsAvailable = isAvailable) }
        }
    }

    /**
     * Returns entity-type-specific error message for CRUD operations.
     *
     * @param operation "create", "update", or "delete"
     * @param entityType The entity type that failed
     * @return Localized error message string
     */
    private fun getEntityErrorMessage(operation: String, entityType: EntityType): String {
        return when (operation) {
            "create" -> when (entityType) {
                EntityType.TAG -> context.getString(R.string.error_create_tag)
                EntityType.CORRESPONDENT -> context.getString(R.string.error_create_correspondent)
                EntityType.DOCUMENT_TYPE -> context.getString(R.string.error_create_document_type)
                EntityType.CUSTOM_FIELD -> context.getString(R.string.error_create_custom_field)
            }
            "update" -> when (entityType) {
                EntityType.TAG -> context.getString(R.string.error_update_tag)
                EntityType.CORRESPONDENT -> context.getString(R.string.error_update_correspondent)
                EntityType.DOCUMENT_TYPE -> context.getString(R.string.error_update_document_type)
                EntityType.CUSTOM_FIELD -> context.getString(R.string.error_update_custom_field)
            }
            "delete" -> when (entityType) {
                EntityType.TAG -> context.getString(R.string.error_delete_tag)
                EntityType.CORRESPONDENT -> context.getString(R.string.error_delete_correspondent)
                EntityType.DOCUMENT_TYPE -> context.getString(R.string.error_delete_document_type)
                EntityType.CUSTOM_FIELD -> context.getString(R.string.error_delete_custom_field)
            }
            else -> context.getString(R.string.error_unknown)
        }
    }

    /**
     * Helper method to get active entities based on current entity type.
     */
    private fun getActiveEntities(): List<EntityItem> {
        return when (_uiState.value.currentEntityType) {
            EntityType.TAG -> allTags
            EntityType.CORRESPONDENT -> allCorrespondents
            EntityType.DOCUMENT_TYPE -> allDocumentTypes
            EntityType.CUSTOM_FIELD -> allCustomFields
        }
    }

    /**
     * Switches the active entity type (tab change).
     * Clears selection and reapplies current search/filter/sort to new entity type.
     *
     * @param type The entity type to switch to (TAG, CORRESPONDENT, DOCUMENT_TYPE, CUSTOM_FIELD)
     */
    fun setEntityType(type: EntityType) {
        _uiState.update {
            it.copy(
                currentEntityType = type,
                selectedEntity = null,
                documentsForEntity = emptyList()
            )
        }

        // Reapply current filters to the new entity type
        applyCurrentFilters()
    }

    /**
     * Applies current search, filter, and sort settings to the active entity type.
     * Updates the UI state with processed entities.
     */
    private fun applyCurrentFilters() {
        val activeEntities = getActiveEntities()
        val processed = applySearchFilterSortEntities(activeEntities, _uiState.value)

        _uiState.update {
            it.copy(
                entities = processed,
                isLoading = false
            )
        }
    }

    /**
     * BEST PRACTICE (Google Architecture Sample):
     * Reactive Flow for tags - SINGLE SOURCE OF TRUTH for UI state.
     * Automatically updates when Room database changes.
     */
    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags().collect { tags ->
                // Convert to EntityItem
                allTags = tags.map { tag ->
                    EntityItem(
                        id = tag.id,
                        name = tag.name,
                        color = parseColor(tag.color),
                        documentCount = tag.documentCount ?: 0,
                        entityType = EntityType.TAG
                    )
                }

                // If Tags is the active entity type, update UI state
                if (_uiState.value.currentEntityType == EntityType.TAG) {
                    val processed = applySearchFilterSortEntities(allTags, _uiState.value)
                    _uiState.update {
                        it.copy(
                            entities = processed,
                            isLoading = false,
                            error = null
                        )
                    }
                }
            }
        }
    }

    /**
     * Reactive Flow for correspondents - updates UI when database changes.
     */
    private fun observeCorrespondentsReactively() {
        viewModelScope.launch {
            correspondentRepository.observeCorrespondents().collect { correspondents ->
                // Convert to EntityItem
                allCorrespondents = correspondents.map { correspondent ->
                    EntityItem(
                        id = correspondent.id,
                        name = correspondent.name,
                        documentCount = correspondent.documentCount ?: 0,
                        entityType = EntityType.CORRESPONDENT
                    )
                }

                // If Correspondents is the active entity type, update UI state
                if (_uiState.value.currentEntityType == EntityType.CORRESPONDENT) {
                    val processed = applySearchFilterSortEntities(allCorrespondents, _uiState.value)
                    _uiState.update {
                        it.copy(
                            entities = processed,
                            isLoading = false,
                            error = null
                        )
                    }
                }
            }
        }
    }

    /**
     * Reactive Flow for document types - updates UI when database changes.
     */
    private fun observeDocumentTypesReactively() {
        viewModelScope.launch {
            documentTypeRepository.observeDocumentTypes().collect { documentTypes ->
                // Convert to EntityItem
                allDocumentTypes = documentTypes.map { documentType ->
                    EntityItem(
                        id = documentType.id,
                        name = documentType.name,
                        documentCount = documentType.documentCount ?: 0,
                        entityType = EntityType.DOCUMENT_TYPE
                    )
                }

                // If Document Types is the active entity type, update UI state
                if (_uiState.value.currentEntityType == EntityType.DOCUMENT_TYPE) {
                    val processed = applySearchFilterSortEntities(allDocumentTypes, _uiState.value)
                    _uiState.update {
                        it.copy(
                            entities = processed,
                            isLoading = false,
                            error = null
                        )
                    }
                }
            }
        }
    }

    /**
     * Reactive Flow for custom fields - updates UI when database changes.
     */
    private fun observeCustomFieldsReactively() {
        viewModelScope.launch {
            customFieldRepository.observeCustomFields().collect { customFields ->
                // Convert to EntityItem
                allCustomFields = customFields.map { customField ->
                    EntityItem(
                        id = customField.id,
                        name = customField.name,
                        documentCount = 0, // Custom fields don't have document count
                        entityType = EntityType.CUSTOM_FIELD,
                        dataType = customField.dataType
                    )
                }

                // If Custom Fields is the active entity type, update UI state
                if (_uiState.value.currentEntityType == EntityType.CUSTOM_FIELD) {
                    val processed = applySearchFilterSortEntities(allCustomFields, _uiState.value)
                    _uiState.update {
                        it.copy(
                            entities = processed,
                            isLoading = false,
                            error = null
                        )
                    }
                }
            }
        }
    }

    /**
     * NEW: Apply search, filter, and sort to EntityItem collections.
     * Works with unified entity model for multi-entity support.
     */
    private fun applySearchFilterSortEntities(
        entities: List<EntityItem>,
        state: LabelsUiState
    ): List<EntityItem> {
        // 1. Apply search
        var result = if (state.searchQuery.isBlank()) {
            entities
        } else {
            entities.filter { it.name.contains(state.searchQuery, ignoreCase = true) }
        }

        // 2. Apply filter
        result = when (state.filterOption) {
            LabelFilterOption.ALL -> result
            LabelFilterOption.WITH_DOCUMENTS -> result.filter { it.documentCount > 0 }
            LabelFilterOption.EMPTY -> result.filter { it.documentCount == 0 }
            LabelFilterOption.MANY_DOCUMENTS -> result.filter { it.documentCount > 5 }
        }

        // 3. Apply sort
        result = when (state.sortOption) {
            LabelSortOption.NAME_ASC -> result.sortedBy { it.name.lowercase() }
            LabelSortOption.NAME_DESC -> result.sortedByDescending { it.name.lowercase() }
            LabelSortOption.COUNT_DESC -> result.sortedByDescending { it.documentCount }
            LabelSortOption.COUNT_ASC -> result.sortedBy { it.documentCount }
            LabelSortOption.NEWEST -> result.sortedByDescending { it.id } // ID as proxy for creation time
            LabelSortOption.OLDEST -> result.sortedBy { it.id }
        }

        return result
    }

    /**
     * BEST PRACTICE (Google Architecture Sample):
     * refresh() triggers API fetch for ALL entity types and updates Room caches.
     * It does NOT update _uiState.entities directly - that's done by reactive observers.
     * This ensures single source of truth: Room → Flow → UI.
     *
     * Loads all entity types in parallel for efficiency.
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load all entity types in parallel
            val tagsDeferred = async { tagRepository.getTags(forceRefresh = true) }
            val correspondentsDeferred = async { correspondentRepository.getCorrespondents(forceRefresh = true) }
            val documentTypesDeferred = async { documentTypeRepository.getDocumentTypes(forceRefresh = true) }
            val customFieldsDeferred = async { customFieldRepository.getCustomFields(forceRefresh = true) }

            // Wait for all to complete
            val tagsResult = tagsDeferred.await()
            val correspondentsResult = correspondentsDeferred.await()
            val documentTypesResult = documentTypesDeferred.await()
            val customFieldsResult = customFieldsDeferred.await()

            // Check for errors (prioritize tag errors since that's the default tab)
            if (tagsResult.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = tagsResult.exceptionOrNull()?.message
                            ?: context.getString(R.string.error_loading)
                    )
                }
            } else {
                // Success - reactive observers will update UI automatically
                _uiState.update { it.copy(isLoading = false) }
            }

            // Set custom fields availability flag based on API response
            customFieldsResult.onSuccess { fields ->
                _uiState.update { it.copy(customFieldsAvailable = fields.isNotEmpty()) }
            }.onFailure {
                // If custom fields API fails (404), hide the tab
                _uiState.update { it.copy(customFieldsAvailable = false) }
            }
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyCurrentFilters()
    }

    fun setSortOption(option: LabelSortOption) {
        _uiState.update { it.copy(sortOption = option) }
        applyCurrentFilters()
    }

    fun setFilterOption(option: LabelFilterOption) {
        _uiState.update { it.copy(filterOption = option) }
        applyCurrentFilters()
    }

    fun setSortAndFilter(sort: LabelSortOption, filter: LabelFilterOption) {
        _uiState.update { it.copy(sortOption = sort, filterOption = filter) }
        applyCurrentFilters()
    }

    fun resetSortAndFilter() {
        _uiState.update {
            it.copy(
                sortOption = LabelSortOption.NAME_ASC,
                filterOption = LabelFilterOption.ALL
            )
        }
        applyCurrentFilters()
    }

    /**
     * NEW: Unified create method for all entity types.
     * Creates entity based on currentEntityType.
     *
     * @param name Entity name (required for all types)
     * @param color Color (only used for Tags, ignored for others)
     * @param dataType Data type (only used for Custom Fields, ignored for others)
     */
    fun createEntity(name: String, color: Color? = null, dataType: String? = null) {
        viewModelScope.launch {
            val result = when (_uiState.value.currentEntityType) {
                EntityType.TAG -> {
                    val colorHex = colorToHex(color ?: labelColorOptions.first())
                    tagRepository.createTag(name, colorHex)
                }
                EntityType.CORRESPONDENT -> {
                    correspondentRepository.createCorrespondent(name)
                }
                EntityType.DOCUMENT_TYPE -> {
                    documentTypeRepository.createDocumentType(name)
                }
                EntityType.CUSTOM_FIELD -> {
                    customFieldRepository.createCustomField(name, dataType ?: "string")
                }
            }

            result.onSuccess {
                // BEST PRACTICE: No manual refresh needed!
                // Reactive observers automatically update UI.
            }.onFailure { error ->
                _uiState.update {
                    it.copy(error = getEntityErrorMessage("create", _uiState.value.currentEntityType))
                }
            }
        }
    }

    /**
     * NEW: Unified update method for all entity types.
     * Updates entity based on currentEntityType.
     *
     * @param id Entity ID to update
     * @param name New entity name (required for all types)
     * @param color New color (only used for Tags, ignored for others)
     * @param dataType New data type (only used for Custom Fields, ignored for others)
     */
    fun updateEntity(id: Int, name: String, color: Color? = null, dataType: String? = null) {
        viewModelScope.launch {
            val result = when (_uiState.value.currentEntityType) {
                EntityType.TAG -> {
                    val colorHex = colorToHex(color ?: labelColorOptions.first())
                    tagRepository.updateTag(id, name, colorHex)
                }
                EntityType.CORRESPONDENT -> {
                    correspondentRepository.updateCorrespondent(id, name)
                }
                EntityType.DOCUMENT_TYPE -> {
                    documentTypeRepository.updateDocumentType(id, name)
                }
                EntityType.CUSTOM_FIELD -> {
                    // Custom fields don't support update in Phase 1
                    // Would require PUT endpoint and repository method
                    Result.failure(Exception("Custom field update not yet implemented"))
                }
            }

            result.onSuccess {
                // BEST PRACTICE: No manual refresh needed!
                // Reactive observers automatically update UI.
            }.onFailure { error ->
                _uiState.update {
                    it.copy(error = getEntityErrorMessage("update", _uiState.value.currentEntityType))
                }
            }
        }
    }

    /**
     * NEW: Prepares entity deletion by loading the document count.
     * Shows confirmation dialog with info about affected documents.
     * Works for all entity types based on currentEntityType.
     */
    fun prepareDeleteEntity(entityId: Int) {
        val entity = getActiveEntities().find { it.id == entityId } ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDeleteInfo = true) }

            // Use the documentCount from the entity directly (already available)
            // This avoids an extra API call since we have the count cached
            _uiState.update {
                it.copy(
                    pendingDeleteEntity = PendingDeleteEntity(
                        id = entity.id,
                        name = entity.name,
                        documentCount = entity.documentCount,
                        entityType = entity.entityType
                    ),
                    isLoadingDeleteInfo = false
                )
            }
        }
    }

    /**
     * NEW: Confirms and executes the pending entity deletion.
     * Deletes based on the entityType stored in pendingDeleteEntity.
     */
    fun confirmDeleteEntity() {
        val pending = _uiState.value.pendingDeleteEntity ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }

            val result = when (pending.entityType) {
                EntityType.TAG -> tagRepository.deleteTag(pending.id)
                EntityType.CORRESPONDENT -> correspondentRepository.deleteCorrespondent(pending.id)
                EntityType.DOCUMENT_TYPE -> documentTypeRepository.deleteDocumentType(pending.id)
                EntityType.CUSTOM_FIELD -> customFieldRepository.deleteCustomField(pending.id)
            }

            result.onSuccess {
                // BEST PRACTICE: No manual refresh needed!
                // Reactive observers automatically update UI.
                _uiState.update {
                    it.copy(
                        pendingDeleteEntity = null,
                        isDeleting = false
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        error = getEntityErrorMessage("delete", pending.entityType),
                        isDeleting = false
                    )
                }
            }
        }
    }

    /**
     * Clears the pending delete state (cancel deletion).
     */
    fun clearPendingDelete() {
        _uiState.update { it.copy(pendingDeleteEntity = null) }
    }

    /**
     * NEW: Loads documents for a specific entity.
     * Works for all entity types based on currentEntityType.
     *
     * @param entityId The ID of the entity to load documents for
     */
    fun loadDocumentsForEntity(entityId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDocuments = true) }

            val result = when (_uiState.value.currentEntityType) {
                EntityType.TAG -> tagRepository.getDocumentsForTag(entityId)
                EntityType.CORRESPONDENT -> correspondentRepository.getDocumentsForCorrespondent(entityId)
                EntityType.DOCUMENT_TYPE -> documentTypeRepository.getDocumentsForDocumentType(entityId)
                EntityType.CUSTOM_FIELD -> {
                    // Custom fields don't have associated documents
                    Result.success(emptyList())
                }
            }

            result.onSuccess { documents ->
                val entityDocs = documents.map { doc ->
                    LabelDocument(
                        id = doc.id,
                        title = doc.title,
                        date = DateFormatter.formatDateShort(doc.created),
                        pageCount = 1 // API doesn't provide page count
                    )
                }
                _uiState.update {
                    it.copy(
                        documentsForEntity = entityDocs,
                        isLoadingDocuments = false
                    )
                }
            }.onFailure {
                _uiState.update {
                    it.copy(
                        documentsForEntity = emptyList(),
                        isLoadingDocuments = false
                    )
                }
            }
        }
    }

    private fun parseColor(colorString: String?): Color {
        if (colorString == null) return labelColorOptions.first()

        return try {
            if (colorString.startsWith("#")) {
                Color(android.graphics.Color.parseColor(colorString))
            } else {
                labelColorOptions.first()
            }
        } catch (e: Exception) {
            labelColorOptions.first()
        }
    }

    private fun colorToHex(color: Color): String {
        val red = (color.red * 255).toInt()
        val green = (color.green * 255).toInt()
        val blue = (color.blue * 255).toInt()
        return String.format("#%02X%02X%02X", red, green, blue)
    }

    /**
     * NEW: Selects an entity and loads its documents.
     * BEST PRACTICE: Navigation state in ViewModel survives navigation.
     * When user navigates to DocumentDetail and back, selection is preserved.
     *
     * @param entity The entity to select (can be any type)
     */
    fun selectEntity(entity: EntityItem) {
        _uiState.update { it.copy(selectedEntity = entity) }
        loadDocumentsForEntity(entity.id)
    }

    /**
     * Clears the selected entity and its documents list.
     */
    fun clearSelectedEntity() {
        _uiState.update { it.copy(selectedEntity = null, documentsForEntity = emptyList()) }
    }

    /**
     * Clears only the documents list, keeping the entity selected.
     */
    fun clearDocumentsForEntity() {
        _uiState.update { it.copy(documentsForEntity = emptyList()) }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "") }
        applyCurrentFilters()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun resetState() {
        _uiState.update { LabelsUiState() }
        allTags = emptyList()
        allCorrespondents = emptyList()
        allDocumentTypes = emptyList()
        allCustomFields = emptyList()
        refresh()
    }
}
