package com.paperless.scanner.data.database

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.paperless.scanner.domain.model.DocumentFilter
import com.paperless.scanner.domain.model.DocumentSortField
import com.paperless.scanner.domain.model.SortOrder

/**
 * Utility for building dynamic SQL queries from DocumentFilter.
 *
 * Supports structured filter criteria:
 * - Multi-tag filtering with OR logic (uses JSON LIKE patterns)
 * - Correspondent ID (exact match)
 * - Document Type ID (exact match)
 * - Date ranges (created, added, modified) with ISO string comparison
 * - Archive status (IS NULL / IS NOT NULL / exact ASN match)
 *
 * Note: Full-text search is handled separately via SearchBar/Repository, not in this builder.
 *
 * Usage (Paging 3):
 * ```kotlin
 * val query = DocumentFilterQueryBuilder.buildPagingQuery(searchQuery, filter)
 * val pagingSource = dao.getDocumentsPagingSource(query)
 * ```
 *
 * Usage (Count):
 * ```kotlin
 * val query = DocumentFilterQueryBuilder.buildCountQuery(searchQuery, filter)
 * val count = dao.getCountWithFilter(query)
 * ```
 */
object DocumentFilterQueryBuilder {

    /**
     * Build COUNT query for documents matching the search query and/or filter.
     *
     * @param searchQuery Full-text search query (from SearchBar)
     * @param filter DocumentFilter with structured criteria (from FilterSheet)
     * @return SupportSQLiteQuery ready for @RawQuery
     */
    fun buildCountQuery(
        searchQuery: String? = null,
        filter: DocumentFilter
    ): SupportSQLiteQuery {
        val whereConditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        // Always exclude soft-deleted documents
        whereConditions.add("isDeleted = 0")

        // Full-text search (separate from structured filter)
        if (!searchQuery.isNullOrBlank()) {
            val trimmedQuery = searchQuery.trim()
            whereConditions.add(
                "(title LIKE ? OR content LIKE ? OR originalFileName LIKE ? OR archiveSerialNumber LIKE ?)"
            )
            val likePattern = "%$trimmedQuery%"
            args.add(likePattern)
            args.add(likePattern)
            args.add(likePattern)
            args.add(likePattern)
        }

        // Multi-tag filtering (OR logic)
        if (filter.tagIds.isNotEmpty()) {
            // Gson stores List<Int> as [1,2,3] (numbers without quotes)
            // Need to match: [1], [1,2], [2,1], etc.
            // Use 4 patterns per tag: start [ID,  middle ,ID,  end ,ID]  single [ID]
            val tagConditions = filter.tagIds.flatMap { tagId ->
                listOf(
                    "tags LIKE ?", // [ID,...
                    "tags LIKE ?", // ...,ID,...
                    "tags LIKE ?", // ...,ID]
                    "tags LIKE ?"  // [ID] (single tag)
                )
            }
            whereConditions.add("(${tagConditions.joinToString(" OR ")})")
            filter.tagIds.forEach { tagId ->
                args.add("%[$tagId,%") // Tag at start: [1,2,3]
                args.add("%,$tagId,%") // Tag in middle: [9,1,5]
                args.add("%,$tagId]%") // Tag at end: [9,5,1]
                args.add("%[$tagId]%") // Single tag: [1]
            }
        }

        // Correspondent filter
        if (filter.correspondentId != null) {
            whereConditions.add("correspondent = ?")
            args.add(filter.correspondentId)
        }

        // Document Type filter
        if (filter.documentTypeId != null) {
            whereConditions.add("documentType = ?")
            args.add(filter.documentTypeId)
        }

        // Created date range
        if (filter.createdDateFrom != null) {
            whereConditions.add("created >= ?")
            args.add(filter.createdDateFrom)
        }
        if (filter.createdDateTo != null) {
            whereConditions.add("created <= ?")
            args.add("${filter.createdDateTo}T23:59:59")
        }

        // Added date range
        if (filter.addedDateFrom != null) {
            whereConditions.add("added >= ?")
            args.add(filter.addedDateFrom)
        }
        if (filter.addedDateTo != null) {
            whereConditions.add("added <= ?")
            args.add("${filter.addedDateTo}T23:59:59")
        }

        // Modified date range
        if (filter.modifiedDateFrom != null) {
            whereConditions.add("modified >= ?")
            args.add(filter.modifiedDateFrom)
        }
        if (filter.modifiedDateTo != null) {
            whereConditions.add("modified <= ?")
            args.add("${filter.modifiedDateTo}T23:59:59")
        }

        // Archive status
        when (filter.hasArchiveSerialNumber) {
            true -> whereConditions.add("archiveSerialNumber IS NOT NULL")
            false -> whereConditions.add("archiveSerialNumber IS NULL")
            null -> {}
        }

        // Specific ASN
        if (filter.archiveSerialNumber != null) {
            whereConditions.add("archiveSerialNumber = ?")
            args.add(filter.archiveSerialNumber.toString())
        }

        // Build COUNT SQL
        val sql = buildString {
            append("SELECT COUNT(*) FROM cached_documents")
            if (whereConditions.isNotEmpty()) {
                append(" WHERE ")
                append(whereConditions.joinToString(" AND "))
            }
        }

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /**
     * Build SELECT query for Paging 3 PagingSource (WITHOUT LIMIT/OFFSET).
     *
     * CRITICAL: Room automatically adds LIMIT/OFFSET for PagingSource.
     * This query should NOT include LIMIT/OFFSET or pagination will break!
     *
     * @param searchQuery Full-text search query (from SearchBar)
     * @param filter DocumentFilter with structured criteria (from FilterSheet)
     * @return SupportSQLiteQuery ready for PagingSource @RawQuery
     */
    fun buildPagingQuery(
        searchQuery: String? = null,
        filter: DocumentFilter
    ): SupportSQLiteQuery {
        val whereConditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        // Always exclude soft-deleted documents
        whereConditions.add("isDeleted = 0")

        // Full-text search (separate from structured filter)
        if (!searchQuery.isNullOrBlank()) {
            val trimmedQuery = searchQuery.trim()
            whereConditions.add(
                "(title LIKE ? OR content LIKE ? OR originalFileName LIKE ? OR archiveSerialNumber LIKE ?)"
            )
            val likePattern = "%$trimmedQuery%"
            args.add(likePattern)
            args.add(likePattern)
            args.add(likePattern)
            args.add(likePattern)
        }

        // Multi-tag filtering (OR logic)
        if (filter.tagIds.isNotEmpty()) {
            // Gson stores List<Int> as [1,2,3] (numbers without quotes)
            // Need to match: [1], [1,2], [2,1], etc.
            // Use 4 patterns per tag: start [ID,  middle ,ID,  end ,ID]  single [ID]
            val tagConditions = filter.tagIds.flatMap { tagId ->
                listOf(
                    "tags LIKE ?", // [ID,...
                    "tags LIKE ?", // ...,ID,...
                    "tags LIKE ?", // ...,ID]
                    "tags LIKE ?"  // [ID] (single tag)
                )
            }
            whereConditions.add("(${tagConditions.joinToString(" OR ")})")
            filter.tagIds.forEach { tagId ->
                args.add("%[$tagId,%") // Tag at start: [1,2,3]
                args.add("%,$tagId,%") // Tag in middle: [9,1,5]
                args.add("%,$tagId]%") // Tag at end: [9,5,1]
                args.add("%[$tagId]%") // Single tag: [1]
            }
        }

        // Correspondent filter
        if (filter.correspondentId != null) {
            whereConditions.add("correspondent = ?")
            args.add(filter.correspondentId)
        }

        // Document Type filter
        if (filter.documentTypeId != null) {
            whereConditions.add("documentType = ?")
            args.add(filter.documentTypeId)
        }

        // Created date range
        if (filter.createdDateFrom != null) {
            whereConditions.add("created >= ?")
            args.add(filter.createdDateFrom)
        }
        if (filter.createdDateTo != null) {
            whereConditions.add("created <= ?")
            args.add("${filter.createdDateTo}T23:59:59")
        }

        // Added date range
        if (filter.addedDateFrom != null) {
            whereConditions.add("added >= ?")
            args.add(filter.addedDateFrom)
        }
        if (filter.addedDateTo != null) {
            whereConditions.add("added <= ?")
            args.add("${filter.addedDateTo}T23:59:59")
        }

        // Modified date range
        if (filter.modifiedDateFrom != null) {
            whereConditions.add("modified >= ?")
            args.add(filter.modifiedDateFrom)
        }
        if (filter.modifiedDateTo != null) {
            whereConditions.add("modified <= ?")
            args.add("${filter.modifiedDateTo}T23:59:59")
        }

        // Archive status
        when (filter.hasArchiveSerialNumber) {
            true -> whereConditions.add("archiveSerialNumber IS NOT NULL")
            false -> whereConditions.add("archiveSerialNumber IS NULL")
            null -> {}
        }

        // Specific ASN
        if (filter.archiveSerialNumber != null) {
            whereConditions.add("archiveSerialNumber = ?")
            args.add(filter.archiveSerialNumber.toString())
        }

        // Build SELECT SQL (NO LIMIT/OFFSET - Room adds them automatically!)
        val sql = buildString {
            append("SELECT * FROM cached_documents")
            if (whereConditions.isNotEmpty()) {
                append(" WHERE ")
                append(whereConditions.joinToString(" AND "))
            }
            append(buildOrderByClause(filter))
            // ❌ DO NOT ADD LIMIT/OFFSET - Room handles pagination automatically
        }

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /**
     * Build ORDER BY clause from filter sort settings.
     *
     * @param filter DocumentFilter with sortBy and sortOrder
     * @return SQL ORDER BY clause (e.g., "ORDER BY title COLLATE NOCASE ASC")
     */
    private fun buildOrderByClause(filter: DocumentFilter): String {
        val orderByField = when (filter.sortBy) {
            DocumentSortField.ADDED -> "added"
            DocumentSortField.CREATED -> "created"
            DocumentSortField.MODIFIED -> "modified"
            DocumentSortField.TITLE -> "title COLLATE NOCASE" // Case-insensitive sort
            DocumentSortField.ASN -> "CAST(archiveSerialNumber AS INTEGER)" // Numeric sort (handles NULL)
        }
        val direction = if (filter.sortOrder == SortOrder.ASC) "ASC" else "DESC"
        return " ORDER BY $orderByField $direction"
    }
}
