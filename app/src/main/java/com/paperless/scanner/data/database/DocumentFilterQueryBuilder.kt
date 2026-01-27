package com.paperless.scanner.data.database

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.paperless.scanner.domain.model.DocumentFilter

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
 * Usage:
 * ```kotlin
 * val query = DocumentFilterQueryBuilder.buildQuery(filter, limit = 25, offset = 0)
 * val documents = dao.observeDocumentsWithFilter(query)
 * ```
 */
object DocumentFilterQueryBuilder {

    /**
     * Build SELECT query for documents matching the search query and/or filter.
     *
     * @param searchQuery Full-text search query (from SearchBar) - searches title, content, filename, ASN
     * @param filter DocumentFilter with structured criteria (from FilterSheet)
     * @param limit Max results
     * @param offset Pagination offset
     * @return SupportSQLiteQuery ready for @RawQuery
     */
    fun buildQuery(
        searchQuery: String? = null,
        filter: DocumentFilter,
        limit: Int = 25,
        offset: Int = 0
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

        // Multi-tag filtering (OR logic: document must have at least ONE of these tags)
        if (filter.tagIds.isNotEmpty()) {
            val tagConditions = filter.tagIds.map { "tags LIKE ?" }
            whereConditions.add("(${tagConditions.joinToString(" OR ")})")
            filter.tagIds.forEach { tagId ->
                args.add("%\"$tagId\"%") // JSON array pattern: "[1,2,3]"
            }
        }

        // Correspondent filter (exact match)
        if (filter.correspondentId != null) {
            whereConditions.add("correspondent = ?")
            args.add(filter.correspondentId)
        }

        // Document Type filter (exact match)
        if (filter.documentTypeId != null) {
            whereConditions.add("documentType = ?")
            args.add(filter.documentTypeId)
        }

        // Created date range (ISO 8601 string comparison works for YYYY-MM-DD format)
        if (filter.createdDateFrom != null) {
            whereConditions.add("created >= ?")
            args.add(filter.createdDateFrom)
        }
        if (filter.createdDateTo != null) {
            whereConditions.add("created <= ?")
            args.add("${filter.createdDateTo}T23:59:59") // End of day
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

        // Archive status filter
        when (filter.hasArchiveSerialNumber) {
            true -> whereConditions.add("archiveSerialNumber IS NOT NULL")
            false -> whereConditions.add("archiveSerialNumber IS NULL")
            null -> {} // No filter
        }

        // Specific archive serial number (exact match)
        if (filter.archiveSerialNumber != null) {
            whereConditions.add("archiveSerialNumber = ?")
            args.add(filter.archiveSerialNumber.toString())
        }

        // Build final SQL
        val sql = buildString {
            append("SELECT * FROM cached_documents")
            if (whereConditions.isNotEmpty()) {
                append(" WHERE ")
                append(whereConditions.joinToString(" AND "))
            }
            append(" ORDER BY added DESC")
            append(" LIMIT ? OFFSET ?")
        }

        // Add limit and offset to args
        args.add(limit)
        args.add(offset)

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

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
            val tagConditions = filter.tagIds.map { "tags LIKE ?" }
            whereConditions.add("(${tagConditions.joinToString(" OR ")})")
            filter.tagIds.forEach { tagId ->
                args.add("%\"$tagId\"%")
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
            val tagConditions = filter.tagIds.map { "tags LIKE ?" }
            whereConditions.add("(${tagConditions.joinToString(" OR ")})")
            filter.tagIds.forEach { tagId ->
                args.add("%\"$tagId\"%")
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
            append(" ORDER BY added DESC")
            // ❌ DO NOT ADD LIMIT/OFFSET - Room handles pagination automatically
        }

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
