package com.paperless.scanner.data.sync

import android.util.Log
import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.Document
import com.paperless.scanner.data.database.dao.CachedCorrespondentDao
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedDocumentTypeDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.dao.SyncMetadataDao
import com.paperless.scanner.data.database.entities.SyncMetadata
import com.paperless.scanner.data.database.mappers.toCachedEntity
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

@Singleton
class SyncManager @Inject constructor(
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTagDao: CachedTagDao,
    private val cachedCorrespondentDao: CachedCorrespondentDao,
    private val cachedDocumentTypeDao: CachedDocumentTypeDao,
    private val pendingChangeDao: PendingChangeDao,
    private val syncMetadataDao: SyncMetadataDao
) {
    private val gson = Gson()
    private val TAG = "SyncManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Reactive pending changes count
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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get pending changes count", e)
                }
                delay(2000) // Check every 2 seconds
            }
        }
    }

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
                val response = api.getTags(page = page, pageSize = 100)
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
                val response = api.getCorrespondents(page = page, pageSize = 100)
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
                val response = api.getDocumentTypes(page = page, pageSize = 100)
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
                val response = api.getDocuments(page = page, pageSize = 100)
                val cachedDocuments = response.results.map { it.toCachedEntity() }
                cachedDocumentDao.insertAll(cachedDocuments)

                // Track which IDs we received from the server
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

    private suspend fun pushPendingChanges() {
        try {
            Log.d(TAG, "Pushing pending changes...")
            val pending = pendingChangeDao.getAll()

            if (pending.isEmpty()) {
                Log.d(TAG, "No pending changes to push")
                return
            }

            Log.d(TAG, "Found ${pending.size} pending changes")

            for (change in pending) {
                try {
                    when (change.entityType) {
                        "document" -> pushDocumentChange(change)
                        "tag" -> pushTagChange(change)
                        "correspondent" -> pushCorrespondentChange(change)
                        "document_type" -> pushDocumentTypeChange(change)
                        else -> {
                            Log.w(TAG, "Unknown entity type: ${change.entityType}")
                            pendingChangeDao.delete(change)
                        }
                    }

                    // Successfully pushed, delete the pending change
                    pendingChangeDao.delete(change)
                    Log.d(TAG, "Successfully pushed ${change.changeType} for ${change.entityType} ${change.entityId}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to push change ${change.id}", e)

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

    private suspend fun pushDocumentChange(change: com.paperless.scanner.data.database.entities.PendingChange) {
        when (change.changeType) {
            "update" -> {
                // Parse the change data and call API
                // Note: You'll need to define a proper request model based on your API
                val data = gson.fromJson(change.changeData, Map::class.java)
                // api.updateDocument(change.entityId!!, data)
                // For now, just log
                Log.d(TAG, "Would update document ${change.entityId} with data: $data")
            }
            "delete" -> {
                api.deleteDocument(change.entityId!!)
            }
            else -> {
                Log.w(TAG, "Unknown change type: ${change.changeType}")
            }
        }
    }

    private suspend fun pushTagChange(change: com.paperless.scanner.data.database.entities.PendingChange) {
        when (change.changeType) {
            "update" -> {
                val data = gson.fromJson(change.changeData, Map::class.java)
                Log.d(TAG, "Would update tag ${change.entityId} with data: $data")
            }
            "delete" -> {
                api.deleteTag(change.entityId!!)
            }
            else -> {
                Log.w(TAG, "Unknown change type: ${change.changeType}")
            }
        }
    }

    private suspend fun pushCorrespondentChange(change: com.paperless.scanner.data.database.entities.PendingChange) {
        when (change.changeType) {
            "delete" -> {
                api.deleteCorrespondent(change.entityId!!)
            }
            else -> {
                Log.w(TAG, "Unknown change type: ${change.changeType}")
            }
        }
    }

    private suspend fun pushDocumentTypeChange(change: com.paperless.scanner.data.database.entities.PendingChange) {
        when (change.changeType) {
            "delete" -> {
                api.deleteDocumentType(change.entityId!!)
            }
            else -> {
                Log.w(TAG, "Unknown change type: ${change.changeType}")
            }
        }
    }
}
