package com.paperless.scanner.data.sync

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.Document
import com.paperless.scanner.data.api.models.TrashBulkActionRequest
import com.paperless.scanner.data.api.models.UpdateDocumentRequest
import com.paperless.scanner.data.database.AppDatabase
import com.paperless.scanner.data.database.dao.CachedCorrespondentDao
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedDocumentTypeDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.dao.SyncMetadataDao
import com.paperless.scanner.data.database.entities.SyncMetadata
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.util.withResponseRetry
import com.paperless.scanner.util.withRetry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SyncManager - Orchestrates bidirectional synchronization with Paperless-ngx server.
 *
 * **SYNC ARCHITECTURE:**
 * Implements a two-phase sync strategy:
 * 1. **PUSH** - Upload pending local changes to server first
 * 2. **PULL** - Download latest data from server to cache
 *
 * This order ensures local changes aren't overwritten by stale server data.
 *
 * **SYNC FLOW:**
 * ```
 * performFullSync()
 *     ├── pushPendingChanges()     ← Push local edits/deletes
 *     ├── syncTags()               ← Pull tags
 *     ├── syncCorrespondents()     ← Pull correspondents
 *     ├── syncDocumentTypes()      ← Pull document types
 *     └── syncDocuments()          ← Pull all documents
 * ```
 *
 * **ORPHAN DETECTION:**
 * Each sync operation tracks which IDs exist on the server and removes
 * locally cached items that no longer exist (deleted on server/web).
 *
 * **PENDING CHANGES:**
 * Local changes made offline are queued in [PendingChangeDao] and pushed
 * when connectivity is restored. Supports:
 * - Document updates (title, tags, correspondent, etc.)
 * - Document deletes (soft delete)
 * - Trash permanent deletes
 * - Tag/correspondent/document type deletes
 *
 * **ERROR HANDLING:**
 * - Individual push failures don't abort entire sync
 * - Retry count tracked per pending change
 * - Failed document deletes block dependent trash deletes
 *
 * @property api Paperless-ngx REST API interface
 * @property cachedDocumentDao Room DAO for document cache
 * @property cachedTagDao Room DAO for tag cache
 * @property cachedCorrespondentDao Room DAO for correspondent cache
 * @property cachedDocumentTypeDao Room DAO for document type cache
 * @property pendingChangeDao Room DAO for offline change queue
 * @property syncMetadataDao Room DAO for sync timestamps
 * @property gson JSON serializer for change data
 * @property db Room database handle for atomic multi-DAO transactions (#65)
 * @property serializer Reused cached-tag-id JSON parser (#65)
 *
 * @see DocumentRepository For document-level operations
 * @see PendingChangeDao For offline change persistence
 * @see SyncMetadata For tracking last sync time
 */
@Singleton
class SyncManager @Inject constructor(
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTagDao: CachedTagDao,
    private val cachedCorrespondentDao: CachedCorrespondentDao,
    private val cachedDocumentTypeDao: CachedDocumentTypeDao,
    private val pendingChangeDao: PendingChangeDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val gson: Gson,
    private val db: AppDatabase,
    private val serializer: DocumentSerializer
) {
    private val TAG = "SyncManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Reactive count of pending changes waiting to sync.
     * Updates every 2 seconds. Useful for sync status indicators.
     */
    private val _pendingChangesCount = MutableStateFlow(0)
    val pendingChangesCount: StateFlow<Int> = _pendingChangesCount.asStateFlow()

    init {
        // Start monitoring pending changes
        startPendingChangesMonitor()
    }

    private fun startPendingChangesMonitor() {
        scope.launch {
            while (isActive) {
                try {
                    val count = pendingChangeDao.getCount()
                    _pendingChangesCount.value = count
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get pending changes count", e)
                }
                delay(2000) // Check every 2 seconds
            }
        }
    }

    /**
     * Perform a complete bidirectional sync with the server.
     *
     * **SYNC PHASES:**
     * 1. Push all pending local changes to server
     * 2. Pull and cache tags (with orphan cleanup)
     * 3. Pull and cache correspondents (with orphan cleanup)
     * 4. Pull and cache document types (with orphan cleanup)
     * 5. Pull and cache all documents (with orphan cleanup)
     * 6. Update sync metadata timestamp
     *
     * **USAGE:**
     * ```kotlin
     * val result = syncManager.performFullSync()
     * result.onSuccess {
     *     showMessage("Sync complete")
     * }.onFailure { error ->
     *     showError("Sync failed: ${error.message}")
     * }
     * ```
     *
     * @return [Result] with Unit on success, or failure with exception
     */
    suspend fun performFullSync(): Result<Unit> {
        return try {
            Log.d(TAG, "Starting full sync...")

            // 1. Push pending changes first
            pushPendingChanges()

            // 2. Pull all data from API
            syncTags()
            syncCorrespondents()
            syncDocumentTypes()
            syncDocuments()

            // 3. Update metadata
            syncMetadataDao.insertOrUpdate(
                SyncMetadata(
                    key = "last_full_sync",
                    value = System.currentTimeMillis().toString()
                )
            )

            Log.d(TAG, "Full sync completed successfully")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Full sync failed", e)
            Result.failure(e)
        }
    }

    private suspend fun syncTags() {
        try {
            Log.d(TAG, "Syncing tags...")

            // Step 1: Get all cached tag IDs BEFORE sync
            val cachedIdsBefore = cachedTagDao.getAllIds().toSet()

            // Step 2: Fetch and insert tags from API
            var page = 1
            var hasMore = true
            val syncedIds = mutableSetOf<Int>()

            while (hasMore) {
                val response = withRetry { api.getTags(page = page, pageSize = 100) }
                val cachedTags = response.results.map { it.toCachedEntity() }
                cachedTagDao.insertAll(cachedTags)
                syncedIds.addAll(cachedTags.map { it.id })

                hasMore = response.next != null
                page++
            }

            // Step 3: Remove orphaned tags (deleted on server)
            val orphanedIds = cachedIdsBefore - syncedIds
            if (orphanedIds.isNotEmpty()) {
                Log.d(TAG, "Removing ${orphanedIds.size} orphaned tags")
                cachedTagDao.deleteByIds(orphanedIds.toList())
            }

            Log.d(TAG, "Tags synced successfully: ${syncedIds.size} synced, ${orphanedIds.size} removed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync tags", e)
            throw e
        }
    }

    private suspend fun syncCorrespondents() {
        try {
            Log.d(TAG, "Syncing correspondents...")

            // Step 1: Get all cached correspondent IDs BEFORE sync
            val cachedIdsBefore = cachedCorrespondentDao.getAllIds().toSet()

            // Step 2: Fetch and insert correspondents from API
            var page = 1
            var hasMore = true
            val syncedIds = mutableSetOf<Int>()

            while (hasMore) {
                val response = withRetry { api.getCorrespondents(page = page, pageSize = 100) }
                val cachedCorrespondents = response.results.map { it.toCachedEntity() }
                cachedCorrespondentDao.insertAll(cachedCorrespondents)
                syncedIds.addAll(cachedCorrespondents.map { it.id })

                hasMore = response.next != null
                page++
            }

            // Step 3: Remove orphaned correspondents (deleted on server)
            val orphanedIds = cachedIdsBefore - syncedIds
            if (orphanedIds.isNotEmpty()) {
                Log.d(TAG, "Removing ${orphanedIds.size} orphaned correspondents")
                cachedCorrespondentDao.deleteByIds(orphanedIds.toList())
            }

            Log.d(TAG, "Correspondents synced successfully: ${syncedIds.size} synced, ${orphanedIds.size} removed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync correspondents", e)
            throw e
        }
    }

    private suspend fun syncDocumentTypes() {
        try {
            Log.d(TAG, "Syncing document types...")

            // Step 1: Get all cached document type IDs BEFORE sync
            val cachedIdsBefore = cachedDocumentTypeDao.getAllIds().toSet()

            // Step 2: Fetch and insert document types from API
            var page = 1
            var hasMore = true
            val syncedIds = mutableSetOf<Int>()

            while (hasMore) {
                val response = withRetry { api.getDocumentTypes(page = page, pageSize = 100) }
                val cachedTypes = response.results.map { it.toCachedEntity() }
                cachedDocumentTypeDao.insertAll(cachedTypes)
                syncedIds.addAll(cachedTypes.map { it.id })

                hasMore = response.next != null
                page++
            }

            // Step 3: Remove orphaned document types (deleted on server)
            val orphanedIds = cachedIdsBefore - syncedIds
            if (orphanedIds.isNotEmpty()) {
                Log.d(TAG, "Removing ${orphanedIds.size} orphaned document types")
                cachedDocumentTypeDao.deleteByIds(orphanedIds.toList())
            }

            Log.d(TAG, "Document types synced successfully: ${syncedIds.size} synced, ${orphanedIds.size} removed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync document types", e)
            throw e
        }
    }

    private suspend fun syncDocuments() {
        try {
            Log.d(TAG, "Syncing documents...")

            // Step 1: Get all cached document IDs BEFORE sync
            val cachedIdsBefore = cachedDocumentDao.getAllIds().toSet()
            Log.d(TAG, "Cached documents before sync: ${cachedIdsBefore.size}")

            // Step 2: Fetch and insert documents from API
            var page = 1
            var hasMore = true
            var totalSynced = 0
            val syncedIds = mutableSetOf<Int>()

            while (hasMore) {
                val response = withRetry { api.getDocuments(page = page, pageSize = 100) }
                val cachedDocuments = response.results.map { it.toCachedEntity() }
                upsertDocumentsPreservingLocalDeletes(cachedDocuments)

                // Track which IDs we received from the server.
                // We add ALL IDs the server returned (even those skipped by the
                // upsert above) so the orphan computation below correctly sees
                // them as "still on server" and does NOT delete the local
                // soft-delete row.
                syncedIds.addAll(cachedDocuments.map { it.id })

                totalSynced += cachedDocuments.size
                hasMore = response.next != null
                page++

                if (page % 10 == 0) {
                    Log.d(TAG, "Synced $totalSynced documents so far...")
                }
            }

            // Step 3: Detect and remove orphaned documents (deleted on server)
            val orphanedIds = cachedIdsBefore - syncedIds
            if (orphanedIds.isNotEmpty()) {
                Log.d(TAG, "Removing ${orphanedIds.size} orphaned documents (deleted on server): $orphanedIds")
                cachedDocumentDao.deleteByIds(orphanedIds.toList())
            }

            syncMetadataDao.insertOrUpdate(
                SyncMetadata(
                    key = "documents_synced_count",
                    value = totalSynced.toString()
                )
            )

            syncMetadataDao.insertOrUpdate(
                SyncMetadata(
                    key = "documents_removed_count",
                    value = orphanedIds.size.toString()
                )
            )

            Log.d(TAG, "Documents synced successfully: $totalSynced synced, ${orphanedIds.size} removed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync documents", e)
            throw e
        }
    }

    /**
     * Upsert a page of documents pulled from the server, **preserving the
     * `isDeleted = 1` flag on rows whose delete is still pending in the
     * offline queue (i.e. the server has not yet been told about it).**
     *
     * The server has no notion of our offline-first soft-delete: a doc the
     * user has just swiped into the trash still appears in `GET /api/documents/`
     * with `isDeleted = 0` until [pushPendingChanges] reaches it. Calling
     * `insertAll` with `OnConflict.REPLACE` on that response would overwrite
     * the local optimistic `isDeleted = 1` back to `0`, which is exactly how
     * docs reappeared on the Home screen after a swipe-to-trash and why the
     * second of two rapid trash actions never showed up in the trash list.
     *
     * Strategy: protect only rows whose primary key has an outstanding
     * `entityType = "document" AND changeType = "delete"` row in
     * [PendingChangeDao] (queried via [PendingChangeDao.getPendingDeletedDocumentIds]).
     * Crucially, a row that is `isDeleted = 1` *without* a pending change is
     * NOT protected — that's the stale-cache case where the doc was deleted
     * earlier and then restored from the web UI; we want the server payload
     * (`isDeleted = 0`) to flow through. We still report all server IDs back
     * via the caller's `syncedIds` set so the orphan-cleanup pass does not
     * mistake protected rows for "deleted on server".
     */
    @VisibleForTesting
    internal suspend fun upsertDocumentsPreservingLocalDeletes(
        documents: List<com.paperless.scanner.data.database.entities.CachedDocument>,
    ) {
        if (documents.isEmpty()) return
        val pendingDeletes = pendingChangeDao.getPendingDeletedDocumentIds().toSet()
        if (pendingDeletes.isEmpty()) {
            cachedDocumentDao.insertAll(documents)
            return
        }
        val safe = documents.filterNot { it.id in pendingDeletes }
        if (safe.isNotEmpty()) cachedDocumentDao.insertAll(safe)
    }

    private suspend fun pushPendingChanges() {
        try {
            Log.d(TAG, "Pushing pending changes...")
            val pending = pendingChangeDao.getAll()

            if (pending.isEmpty()) {
                Log.d(TAG, "No pending changes to push")
                return
            }

            Log.d(TAG, "Found ${pending.size} pending changes")

            // Track failed document IDs - if a document delete fails, skip trash delete for same doc
            // This prevents "document not yet deleted" errors when offline delete + trash delete are queued
            val failedDocumentIds = mutableSetOf<Int>()

            for (change in pending) {
                try {
                    // Skip trash deletes for documents whose soft-delete failed
                    if (change.entityType == "trash" && change.entityId in failedDocumentIds) {
                        Log.w(TAG, "Skipping trash delete for document ${change.entityId} - soft delete failed earlier")
                        // Keep in queue for next sync attempt (soft delete needs to succeed first)
                        continue
                    }

                    when (change.entityType) {
                        "document" -> pushDocumentChange(change)
                        "tag" -> pushTagChange(change)
                        "correspondent" -> pushCorrespondentChange(change)
                        "document_type" -> pushDocumentTypeChange(change)
                        "trash" -> pushTrashChange(change)
                        else -> {
                            Log.w(TAG, "Unknown entity type: ${change.entityType}")
                            pendingChangeDao.delete(change)
                        }
                    }

                    // Successfully pushed, delete the pending change
                    pendingChangeDao.delete(change)
                    Log.d(TAG, "Successfully pushed ${change.changeType} for ${change.entityType} ${change.entityId}")
                } catch (e: CancellationException) {
                    // Abort the entire push loop on coroutine cancellation —
                    // never continue to the next pending change.
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to push change ${change.id}", e)

                    // Track failed document deletes to skip dependent trash deletes
                    if (change.entityType == "document" && change.changeType == "delete" && change.entityId != null) {
                        failedDocumentIds.add(change.entityId)
                        Log.d(TAG, "Added document ${change.entityId} to failed list - will skip trash delete")
                    }

                    // Update retry count and error
                    pendingChangeDao.update(
                        change.copy(
                            syncAttempts = change.syncAttempts + 1,
                            lastError = e.message
                        )
                    )

                    // Don't throw - continue with other changes
                }
            }

            Log.d(TAG, "Finished pushing pending changes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push pending changes", e)
            throw e
        }
    }

    @VisibleForTesting
    internal suspend fun pushDocumentChange(change: com.paperless.scanner.data.database.entities.PendingChange) {
        val entityId = change.entityId
        if (entityId == null) {
            Log.w(TAG, "Skipping document change ${change.id}: entityId is null")
            return
        }

        when (change.changeType) {
            "update" -> {
                // Parse the change data from JSON
                val data = gson.fromJson(change.changeData, Map::class.java) as Map<String, Any?>

                // Extract fields and convert to correct types
                val title = data["title"] as? String
                val tags = (data["tags"] as? List<*>)?.mapNotNull { (it as? Double)?.toInt() }
                val correspondent = (data["correspondent"] as? Double)?.toInt()
                val documentType = (data["documentType"] as? Double)?.toInt()
                val archiveSerialNumber = (data["archiveSerialNumber"] as? Double)?.toInt()
                val created = data["created"] as? String

                // #65: read the previously-cached tag set BEFORE the API call (and outside
                // the transaction). A read/parse failure must not abort the push;
                // getOldTagIds returns null when the old set is unknown (#334) and the
                // delta below is then skipped instead of over-counting every new tag.
                val oldTagIds = if (tags != null) getOldTagIds(entityId) else null

                // Create request and call API
                val request = UpdateDocumentRequest(
                    title = title,
                    tags = tags,
                    correspondent = correspondent,
                    documentType = documentType,
                    archiveSerialNumber = archiveSerialNumber,
                    created = created
                )

                val updatedDocument = withRetry { api.updateDocument(entityId, request) }

                // #65: the cache insert and the per-tag count deltas must be ONE atomic
                // unit, mirroring DocumentMetadataRepository.updateDocument. If a delta DAO
                // call throws, the whole transaction rolls back so the cached document is
                // never persisted with stale/incorrect tag counts alongside it.
                db.withTransaction {
                    cachedDocumentDao.insert(updatedDocument.toCachedEntity())
                    if (tags != null && oldTagIds != null) {
                        val oldSet = oldTagIds.toSet()
                        val newSet = tags.toSet()
                        (oldSet - newSet).forEach { tagId -> cachedTagDao.updateDocumentCount(tagId, -1) }
                        (newSet - oldSet).forEach { tagId -> cachedTagDao.updateDocumentCount(tagId, 1) }
                    }
                }

                Log.d(TAG, "Successfully pushed document update $entityId")
            }
            "delete" -> {
                withRetry { api.deleteDocument(entityId) }
            }
            else -> {
                Log.w(TAG, "Unknown change type: ${change.changeType}")
            }
        }
    }

    /**
     * #65/#334: reads the previously-cached tag-id set for a document so the push can
     * compute tag-count deltas. Returns null when that set is UNKNOWN — cached row missing,
     * tags JSON unparseable, or the read threw — so the caller skips the delta instead of
     * treating unknown as empty and over-counting every new tag. A genuinely empty old set
     * ("[]") returns emptyList(). Mirrors DocumentMetadataRepository.
     */
    private suspend fun getOldTagIds(documentId: Int): List<Int>? {
        return try {
            val cached = cachedDocumentDao.getDocument(documentId) ?: return null
            serializer.deserializeCachedTagIdsOrNull(cached.tags)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read old tag ids for doc $documentId", e)
            null
        }
    }

    private suspend fun pushTagChange(change: com.paperless.scanner.data.database.entities.PendingChange) {
        val entityId = change.entityId
        if (entityId == null) {
            Log.w(TAG, "Skipping tag change ${change.id}: entityId is null")
            return
        }

        when (change.changeType) {
            "update" -> {
                val data = gson.fromJson(change.changeData, Map::class.java)
                Log.d(TAG, "Would update tag $entityId with data: $data")
            }
            "delete" -> {
                withRetry { api.deleteTag(entityId) }
            }
            else -> {
                Log.w(TAG, "Unknown change type: ${change.changeType}")
            }
        }
    }

    private suspend fun pushCorrespondentChange(change: com.paperless.scanner.data.database.entities.PendingChange) {
        val entityId = change.entityId
        if (entityId == null) {
            Log.w(TAG, "Skipping correspondent change ${change.id}: entityId is null")
            return
        }

        when (change.changeType) {
            "delete" -> {
                withRetry { api.deleteCorrespondent(entityId) }
            }
            else -> {
                Log.w(TAG, "Unknown change type: ${change.changeType}")
            }
        }
    }

    private suspend fun pushDocumentTypeChange(change: com.paperless.scanner.data.database.entities.PendingChange) {
        val entityId = change.entityId
        if (entityId == null) {
            Log.w(TAG, "Skipping document type change ${change.id}: entityId is null")
            return
        }

        when (change.changeType) {
            "delete" -> {
                withRetry { api.deleteDocumentType(entityId) }
            }
            else -> {
                Log.w(TAG, "Unknown change type: ${change.changeType}")
            }
        }
    }

    /**
     * Push trash deletion to server (offline-queued permanent delete).
     */
    private suspend fun pushTrashChange(change: com.paperless.scanner.data.database.entities.PendingChange) {
        val entityId = change.entityId
        if (entityId == null) {
            Log.w(TAG, "Skipping trash change ${change.id}: entityId is null")
            return
        }

        when (change.changeType) {
            "delete" -> {
                val request = TrashBulkActionRequest(
                    documents = listOf(entityId),
                    action = "empty"  // "empty" = permanent delete from trash
                )
                val response = withResponseRetry { api.trashBulkAction(request) }
                if (!response.isSuccessful) {
                    throw Exception("Failed to delete trash document $entityId: HTTP ${response.code()}")
                }
                Log.d(TAG, "Successfully pushed trash delete for document $entityId")
            }
            else -> {
                Log.w(TAG, "Unknown trash change type: ${change.changeType}")
            }
        }
    }
}
