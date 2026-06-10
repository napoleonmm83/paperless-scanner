package com.paperless.scanner.data.sync

import android.util.Log
import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.Document as ApiDocument
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.database.entities.CachedTag
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.testing.BaseRoomRepositoryTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the merge logic inside [SyncManager].
 *
 * Focuses on [SyncManager.upsertDocumentsPreservingLocalDeletes], the helper
 * that protects locally-soft-deleted documents from being silently restored
 * when the server still returns them in `GET /api/documents/` (because the
 * matching `PendingChange` has not yet been pushed, or the push raced this
 * sync). See the move-to-trash debugging session 2026-05-28.
 *
 * Pattern: real Room DAOs against the in-memory schema (per CLAUDE.md
 * "don't mock the database" rule, Issue #137), mock the boundary
 * collaborators (`PaperlessApi`).
 */
class SyncManagerTest : BaseRoomRepositoryTest() {

    private lateinit var syncManager: SyncManager
    private lateinit var api: PaperlessApi
    private val gson = Gson()

    @Before
    fun setUpSyncManager() {
        api = mockk(relaxed = true)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        syncManager = SyncManager(
            api = api,
            cachedDocumentDao = database.cachedDocumentDao(),
            cachedTagDao = database.cachedTagDao(),
            cachedCorrespondentDao = database.cachedCorrespondentDao(),
            cachedDocumentTypeDao = database.cachedDocumentTypeDao(),
            pendingChangeDao = database.pendingChangeDao(),
            syncMetadataDao = database.syncMetadataDao(),
            gson = gson,
            db = database,
            serializer = DocumentSerializer(gson),
        )
    }

    @After
    fun tearDownLog() {
        unmockkStatic(Log::class)
    }

    private suspend fun queueDocumentDelete(documentId: Int) {
        database.pendingChangeDao().insert(
            PendingChange(
                entityType = "document",
                entityId = documentId,
                changeType = "delete",
                changeData = "{}",
            )
        )
    }

    private fun cachedDoc(
        id: Int,
        title: String = "Doc $id",
        isDeleted: Boolean = false,
        deletedAt: Long? = null,
        tagsJson: String = "[]",
    ): CachedDocument = CachedDocument(
        id = id,
        title = title,
        content = null,
        created = "2026-01-01T00:00:00Z",
        modified = "2026-01-01T00:00:00Z",
        added = "2026-01-01T00:00:00Z",
        archiveSerialNumber = null,
        originalFileName = null,
        correspondent = null,
        documentType = null,
        storagePath = null,
        tags = tagsJson,
        customFields = null,
        isCached = true,
        lastSyncedAt = 0L,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
    )

    @Test
    fun `upsertDocumentsPreservingLocalDeletes keeps locally soft-deleted document deleted`() = runTest {
        val dao = database.cachedDocumentDao()

        // Given: doc 1 was just swiped to trash (optimistic soft-delete already
        // applied locally, PendingChange is queued); doc 2 is a normal live doc.
        dao.insertAll(listOf(cachedDoc(id = 1, title = "Old 1"), cachedDoc(id = 2, title = "Old 2")))
        dao.softDelete(id = 1, deletedAt = 12345L)
        queueDocumentDelete(1)
        assertEquals("setup sanity: doc 1 is locally deleted", listOf(1), dao.getDeletedIds())

        // When: a fullSync pulls the server's view, which still includes doc 1
        // as non-deleted because the PendingChange has not been pushed yet.
        val fromServer = listOf(
            cachedDoc(id = 1, title = "Server 1"),
            cachedDoc(id = 2, title = "Server 2"),
        )
        syncManager.upsertDocumentsPreservingLocalDeletes(fromServer)

        // Then: doc 1 must STILL be deleted locally — its trash state survives
        // the sync. Doc 2 has been updated to the server payload as normal.
        assertEquals("doc 1 must remain in trash", listOf(1), dao.getDeletedIds())
        assertNull("getDocument hides deleted rows — doc 1 must not appear", dao.getDocument(1))

        val doc2 = dao.getDocument(2)
        assertNotNull("doc 2 must still be retrievable", doc2)
        assertEquals("doc 2 must reflect server payload", "Server 2", doc2!!.title)
    }

    @Test
    fun `upsertDocumentsPreservingLocalDeletes preserves multiple concurrent soft-deletes`() = runTest {
        val dao = database.cachedDocumentDao()

        // Given: user swiped TWO documents to trash in rapid succession
        // (the exact symptom: "only one ends up in trash" when sync clobbers
        // one of the two optimistic deletes).
        dao.insertAll(listOf(
            cachedDoc(id = 10, title = "Old 10"),
            cachedDoc(id = 11, title = "Old 11"),
            cachedDoc(id = 12, title = "Old 12"),
        ))
        dao.softDeleteMultiple(ids = listOf(10, 11), deletedAt = 7777L)
        queueDocumentDelete(10)
        queueDocumentDelete(11)

        // When: server response still contains all three as non-deleted
        syncManager.upsertDocumentsPreservingLocalDeletes(listOf(
            cachedDoc(id = 10, title = "Server 10"),
            cachedDoc(id = 11, title = "Server 11"),
            cachedDoc(id = 12, title = "Server 12"),
        ))

        // Then: BOTH soft-deletes survive
        val deleted = dao.getDeletedIds().toSet()
        assertTrue("doc 10 must remain in trash", 10 in deleted)
        assertTrue("doc 11 must remain in trash", 11 in deleted)
        assertTrue("doc 12 must not be in trash", 12 !in deleted)
        assertEquals("doc 12 must still exist locally", 1, dao.getAllIds().count { it == 12 })
    }

    @Test
    fun `upsertDocumentsPreservingLocalDeletes clears local trash when server restored the document`() = runTest {
        // Codex P2 regression guard (2026-05-28 review): the previous version
        // protected EVERY isDeleted=1 row, which meant a doc deleted earlier
        // and then restored from the Paperless web UI could never come back
        // locally because /api/documents/ returns it as active but our upsert
        // skipped it. Only rows whose delete is still PENDING on the offline
        // queue must be excluded — confirmed (server-side) restorations must
        // propagate.
        val dao = database.cachedDocumentDao()

        // Given: doc 20 sits in local trash (stale state — was deleted long
        // ago, the pending change already pushed and removed from the queue).
        dao.insertAll(listOf(cachedDoc(id = 20, title = "Old 20")))
        dao.softDelete(id = 20, deletedAt = 12345L)
        assertEquals("setup sanity: doc 20 is locally deleted", listOf(20), dao.getDeletedIds())
        assertTrue(
            "setup sanity: no pending delete for doc 20",
            database.pendingChangeDao().getAll().none {
                it.entityType == "document" && it.changeType == "delete" && it.entityId == 20
            },
        )

        // When: a fullSync pulls the server's view and doc 20 is now active
        // again because someone restored it via the web UI.
        syncManager.upsertDocumentsPreservingLocalDeletes(listOf(
            cachedDoc(id = 20, title = "Server 20 restored", isDeleted = false, deletedAt = null),
        ))

        // Then: the local trash flag must be cleared, otherwise the user keeps
        // seeing the doc as trashed forever.
        assertTrue(
            "doc 20 must no longer be in trash",
            dao.getDeletedIds().none { it == 20 },
        )
        val doc20 = dao.getDocument(20)
        assertNotNull("doc 20 must be retrievable as a live document", doc20)
        assertEquals("doc 20 must reflect server payload", "Server 20 restored", doc20!!.title)
    }

    @Test
    fun `upsertDocumentsPreservingLocalDeletes still upserts non-deleted documents`() = runTest {
        val dao = database.cachedDocumentDao()

        // Given: a clean cache (no soft-deletes)
        dao.insertAll(listOf(cachedDoc(id = 5, title = "Old 5")))
        assertTrue("setup sanity: no deleted ids", dao.getDeletedIds().isEmpty())

        // When: server returns updates
        syncManager.upsertDocumentsPreservingLocalDeletes(listOf(
            cachedDoc(id = 5, title = "Server 5 updated"),
            cachedDoc(id = 6, title = "Server 6 new"),
        ))

        // Then: both are present with server payload — the preservation guard
        // must not block normal upserts.
        assertEquals("Server 5 updated", dao.getDocument(5)?.title)
        assertEquals("Server 6 new", dao.getDocument(6)?.title)
    }

    // ---- #65: offline tag-count atomicity when replaying a queued document update ----

    private fun cachedTag(id: Int, name: String, documentCount: Int = 0) = CachedTag(
        id = id,
        name = name,
        color = null,
        match = null,
        matchingAlgorithm = null,
        isInboxTag = false,
        documentCount = documentCount,
    )

    private fun apiDoc(
        id: Int = 1,
        title: String = "Doc $id",
        tags: List<Int> = emptyList(),
    ): ApiDocument = ApiDocument(
        id = id,
        title = title,
        content = null,
        created = "2026-01-01T00:00:00Z",
        modified = "2026-01-01T00:00:00Z",
        added = "2026-01-01T00:00:00Z",
        correspondentId = null,
        documentTypeId = null,
        tags = tags,
        archiveSerialNumber = null,
        originalFileName = null,
        notes = emptyList(),
        owner = null,
        permissions = null,
        userCanChange = true,
        ocrConfidence = null,
    )

    private fun updateChange(documentId: Int, tags: List<Int>): PendingChange = PendingChange(
        entityType = "document",
        entityId = documentId,
        changeType = "update",
        changeData = gson.toJson(mapOf("tags" to tags)),
    )

    @Test
    fun `pushDocumentChange update adjusts cached tag counts by the new-minus-old delta`() = runTest {
        val tagDao = database.cachedTagDao()
        val docDao = database.cachedDocumentDao()
        // Old tag set [1, 2] cached on the document; tags 1 & 2 count 1, tag 3 count 0.
        tagDao.insertAll(listOf(cachedTag(1, "T1", 1), cachedTag(2, "T2", 1), cachedTag(3, "T3", 0)))
        docDao.insert(cachedDoc(id = 1, title = "Before", tagsJson = "[1,2]"))
        coEvery { api.updateDocument(eq(1), any()) } returns apiDoc(id = 1, title = "After", tags = listOf(2, 3))

        syncManager.pushDocumentChange(updateChange(documentId = 1, tags = listOf(2, 3)))

        // tag 1 removed (-1), tag 3 added (+1), tag 2 untouched.
        assertEquals(0, tagDao.getTag(1)?.documentCount)
        assertEquals(1, tagDao.getTag(2)?.documentCount)
        assertEquals(1, tagDao.getTag(3)?.documentCount)
        // The cache row reflects the pushed update.
        assertEquals("After", docDao.getDocument(1)?.title)
    }

    @Test
    fun `pushDocumentChange update skips tag delta when old set is unknown - cached row missing`() = runTest {
        val tagDao = database.cachedTagDao()
        // #334: no cached row for doc 1 → the old tag set is UNKNOWN, not empty. The delta
        // must be skipped instead of incrementing every requested tag.
        tagDao.insertAll(listOf(cachedTag(2, "T2", 1), cachedTag(3, "T3", 0)))
        coEvery { api.updateDocument(eq(1), any()) } returns apiDoc(id = 1, title = "After", tags = listOf(2, 3))

        syncManager.pushDocumentChange(updateChange(documentId = 1, tags = listOf(2, 3)))

        assertEquals(1, tagDao.getTag(2)?.documentCount) // NOT over-counted to 2
        assertEquals(0, tagDao.getTag(3)?.documentCount) // NOT over-counted to 1
        // The push itself still lands the updated cache row.
        assertEquals("After", database.cachedDocumentDao().getDocument(1)?.title)
    }

    @Test
    fun `pushDocumentChange update skips tag delta when cached tags JSON is unparseable`() = runTest {
        val tagDao = database.cachedTagDao()
        val docDao = database.cachedDocumentDao()
        // #334: the cached row exists but its tags JSON is corrupt → old set UNKNOWN.
        tagDao.insertAll(listOf(cachedTag(2, "T2", 1), cachedTag(3, "T3", 0)))
        docDao.insert(cachedDoc(id = 1, title = "Before", tagsJson = "{corrupt"))
        coEvery { api.updateDocument(eq(1), any()) } returns apiDoc(id = 1, title = "After", tags = listOf(2, 3))

        syncManager.pushDocumentChange(updateChange(documentId = 1, tags = listOf(2, 3)))

        assertEquals(1, tagDao.getTag(2)?.documentCount)
        assertEquals(0, tagDao.getTag(3)?.documentCount)
        assertEquals("After", docDao.getDocument(1)?.title)
    }

    @Test
    fun `pushDocumentChange update with genuinely empty old tag set still applies the delta`() = runTest {
        val tagDao = database.cachedTagDao()
        // #334 boundary: "[]" is a KNOWN-empty old set — empty→non-empty must still increment.
        tagDao.insertAll(listOf(cachedTag(3, "T3", 0)))
        database.cachedDocumentDao().insert(cachedDoc(id = 1, tagsJson = "[]"))
        coEvery { api.updateDocument(eq(1), any()) } returns apiDoc(id = 1, tags = listOf(3))

        syncManager.pushDocumentChange(updateChange(documentId = 1, tags = listOf(3)))

        assertEquals(1, tagDao.getTag(3)?.documentCount) // 0 + 1
    }

    @Test
    fun `pushDocumentChange update rolls back the cache insert when a tag-delta DAO call throws`() = runTest {
        // #65: the cache insert and per-tag deltas must be ONE atomic unit. Force the delta
        // to throw by injecting a mocked cachedTagDao (keeping the REAL database + real
        // cachedDocumentDao) so the rollback applies to the real Room transaction.
        val throwingTagDao = mockk<CachedTagDao>(relaxed = true)
        coEvery { throwingTagDao.updateDocumentCount(any(), any()) } throws RuntimeException("delta boom")
        val managerWithThrowingTagDao = SyncManager(
            api = api,
            cachedDocumentDao = database.cachedDocumentDao(),
            cachedTagDao = throwingTagDao,
            cachedCorrespondentDao = database.cachedCorrespondentDao(),
            cachedDocumentTypeDao = database.cachedDocumentTypeDao(),
            pendingChangeDao = database.pendingChangeDao(),
            syncMetadataDao = database.syncMetadataDao(),
            gson = gson,
            db = database,
            serializer = DocumentSerializer(gson),
        )
        database.cachedDocumentDao().insert(cachedDoc(id = 1, title = "Before", tagsJson = "[1,2]"))
        coEvery { api.updateDocument(eq(1), any()) } returns apiDoc(id = 1, title = "After", tags = listOf(2, 3))

        try {
            managerWithThrowingTagDao.pushDocumentChange(updateChange(documentId = 1, tags = listOf(2, 3)))
            fail("expected the tag-delta throw to propagate out of the transaction")
        } catch (e: RuntimeException) {
            assertEquals("delta boom", e.message)
        }

        // Rollback: the cache row still shows the pre-update state, never the "After" insert.
        val cached = database.cachedDocumentDao().getDocument(1)
        assertEquals("Before", cached?.title)
        assertEquals("[1,2]", cached?.tags)
    }
}
