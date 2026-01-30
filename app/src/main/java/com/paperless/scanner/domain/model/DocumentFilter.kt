package com.paperless.scanner.domain.model

import com.google.gson.annotations.SerializedName
import com.paperless.scanner.di.GsonProvider

/**
 * Sort field options for document sorting.
 */
enum class DocumentSortField {
    @SerializedName("added")
    ADDED,      // Hinzugefügt (Default)

    @SerializedName("created")
    CREATED,    // Erstellt

    @SerializedName("modified")
    MODIFIED,   // Geändert

    @SerializedName("title")
    TITLE,      // Titel

    @SerializedName("asn")
    ASN         // Archive Serial Number
}

/**
 * Sort order options.
 */
enum class SortOrder {
    @SerializedName("asc")
    ASC,        // Aufsteigend

    @SerializedName("desc")
    DESC        // Absteigend (Default)
}

/**
 * Domain model for structured document filtering and sorting.
 *
 * Supports 11 filter criteria for comprehensive offline and online filtering:
 * - Multi-tag filtering with OR logic (tagIds)
 * - Metadata filters (correspondent, document type)
 * - Date range filters (created, added, modified)
 * - Archive status filters (hasArchiveSerialNumber, archiveSerialNumber)
 *
 * Supports sorting with 5 fields and 2 directions:
 * - Sort fields: ADDED (default), CREATED, MODIFIED, TITLE, ASN
 * - Sort order: DESC (default), ASC
 *
 * Note: Full-text search is handled separately via SearchBar, not in this filter model.
 *
 * Persistence: Serialized to JSON for DataStore and SavedStateHandle storage.
 *
 * Usage:
 * ```kotlin
 * val filter = DocumentFilter(
 *     tagIds = listOf(1, 2, 3),
 *     correspondentId = 5,
 *     createdDateFrom = "2024-01-01",
 *     sortBy = DocumentSortField.CREATED,
 *     sortOrder = SortOrder.DESC
 * )
 * ```
 */
data class DocumentFilter(
    /**
     * Tag IDs for filtering (OR logic: document must have at least ONE of these tags).
     * Empty list = no tag filter.
     */
    @SerializedName("tag_ids")
    val tagIds: List<Int> = emptyList(),

    /**
     * Correspondent ID filter (exact match).
     * Null = no correspondent filter.
     */
    @SerializedName("correspondent_id")
    val correspondentId: Int? = null,

    /**
     * Document type ID filter (exact match).
     * Null = no document type filter.
     */
    @SerializedName("document_type_id")
    val documentTypeId: Int? = null,

    /**
     * Created date range start (inclusive, ISO 8601 format: YYYY-MM-DD).
     * Null = no lower bound.
     */
    @SerializedName("created_date_from")
    val createdDateFrom: String? = null,

    /**
     * Created date range end (inclusive, ISO 8601 format: YYYY-MM-DD).
     * Null = no upper bound.
     */
    @SerializedName("created_date_to")
    val createdDateTo: String? = null,

    /**
     * Added date range start (inclusive, ISO 8601 format: YYYY-MM-DD).
     * Null = no lower bound.
     */
    @SerializedName("added_date_from")
    val addedDateFrom: String? = null,

    /**
     * Added date range end (inclusive, ISO 8601 format: YYYY-MM-DD).
     * Null = no upper bound.
     */
    @SerializedName("added_date_to")
    val addedDateTo: String? = null,

    /**
     * Modified date range start (inclusive, ISO 8601 format: YYYY-MM-DD).
     * Null = no lower bound.
     */
    @SerializedName("modified_date_from")
    val modifiedDateFrom: String? = null,

    /**
     * Modified date range end (inclusive, ISO 8601 format: YYYY-MM-DD).
     * Null = no upper bound.
     */
    @SerializedName("modified_date_to")
    val modifiedDateTo: String? = null,

    /**
     * Archive serial number status filter.
     * - null: no filter (show all documents)
     * - true: only documents WITH archive serial number
     * - false: only documents WITHOUT archive serial number
     */
    @SerializedName("has_archive_serial_number")
    val hasArchiveSerialNumber: Boolean? = null,

    /**
     * Specific archive serial number filter (exact match).
     * Null = no ASN filter.
     */
    @SerializedName("archive_serial_number")
    val archiveSerialNumber: Int? = null,

    /**
     * Sort field (default: ADDED).
     * Determines which document field to sort by.
     */
    @SerializedName("sort_by")
    val sortBy: DocumentSortField = DocumentSortField.ADDED,

    /**
     * Sort order (default: DESC).
     * Determines ascending or descending sort direction.
     */
    @SerializedName("sort_order")
    val sortOrder: SortOrder = SortOrder.DESC
) {
    /**
     * Check if this filter is empty (no active filters).
     */
    fun isEmpty(): Boolean {
        return tagIds.isEmpty() &&
                correspondentId == null &&
                documentTypeId == null &&
                createdDateFrom == null &&
                createdDateTo == null &&
                addedDateFrom == null &&
                addedDateTo == null &&
                modifiedDateFrom == null &&
                modifiedDateTo == null &&
                hasArchiveSerialNumber == null &&
                archiveSerialNumber == null
    }

    /**
     * Count active filters (for UI badge display).
     */
    fun activeFilterCount(): Int {
        var count = 0
        if (tagIds.isNotEmpty()) count++
        if (correspondentId != null) count++
        if (documentTypeId != null) count++
        if (createdDateFrom != null || createdDateTo != null) count++
        if (addedDateFrom != null || addedDateTo != null) count++
        if (modifiedDateFrom != null || modifiedDateTo != null) count++
        if (hasArchiveSerialNumber != null) count++
        if (archiveSerialNumber != null) count++
        return count
    }

    /**
     * Serialize to JSON string for persistence (DataStore, SavedStateHandle).
     */
    fun toJson(): String {
        return gson.toJson(this)
    }

    companion object {
        private val gson = GsonProvider.instance

        /**
         * Empty filter (no active filters).
         */
        fun empty(): DocumentFilter {
            return DocumentFilter()
        }

        /**
         * Deserialize from JSON string.
         * Returns empty filter if JSON is null, blank, or invalid.
         */
        fun fromJson(json: String?): DocumentFilter {
            if (json.isNullOrBlank()) return empty()
            return try {
                gson.fromJson(json, DocumentFilter::class.java) ?: empty()
            } catch (e: Exception) {
                empty()
            }
        }
    }
}
