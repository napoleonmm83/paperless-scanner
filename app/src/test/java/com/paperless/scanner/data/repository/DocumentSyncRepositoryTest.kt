package com.paperless.scanner.data.repository

import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.health.ServerHealthMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class DocumentSyncRepositoryTest {

    private lateinit var pendingChangeDao: PendingChangeDao
    private lateinit var serverHealthMonitor: ServerHealthMonitor
    private lateinit var gson: Gson
    private lateinit var repo: DocumentSyncRepository

    @Before
    fun setup() {
        pendingChangeDao = mockk(relaxed = true)
        serverHealthMonitor = mockk(relaxed = true)
        gson = Gson()
        repo = DocumentSyncRepository(pendingChangeDao, serverHealthMonitor, gson)
    }

    // ===== queueDocumentUpdate =====

    @Test
    fun `queueDocumentUpdate writes PendingChange with gson-serialized payload`() = runTest {
        val slot = slot<PendingChange>()
        coEvery { pendingChangeDao.insert(capture(slot)) } returns 1L

        repo.queueDocumentUpdate(
            42,
            DocumentSyncRepository.DocumentUpdatePayload(title = "hello", tags = listOf(1, 2)),
        )

        val captured = slot.captured
        assertEquals("document", captured.entityType)
        assertEquals(42, captured.entityId)
        assertEquals("update", captured.changeType)
        // Verify JSON shape (gson default omits nulls, uses property names as keys)
        assertTrue(captured.changeData.contains("\"title\":\"hello\""))
        assertTrue(captured.changeData.contains("\"tags\":[1,2]"))
        // Null fields are omitted
        assertFalse(captured.changeData.contains("correspondent"))
        assertFalse(captured.changeData.contains("documentType"))
    }

    @Test
    fun `queueDocumentUpdate handles title with embedded quotes safely`() = runTest {
        // BUG FIX VERIFICATION (#169): the previous buildString impl produced
        // invalid JSON for titles containing `"`. gson.toJson escapes correctly.
        val slot = slot<PendingChange>()
        coEvery { pendingChangeDao.insert(capture(slot)) } returns 1L

        repo.queueDocumentUpdate(
            7,
            DocumentSyncRepository.DocumentUpdatePayload(title = """He said "hi""""),
        )

        // The captured changeData must be valid JSON that round-trips back to the
        // original title via the same parser SyncManager uses.
        val parsed = gson.fromJson(slot.captured.changeData, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val parsedMap = parsed as Map<String, Any?>
        assertEquals("""He said "hi"""", parsedMap["title"])
    }

    @Test
    fun `queueDocumentUpdate omits null payload fields from JSON`() = runTest {
        val slot = slot<PendingChange>()
        coEvery { pendingChangeDao.insert(capture(slot)) } returns 1L

        repo.queueDocumentUpdate(
            1,
            DocumentSyncRepository.DocumentUpdatePayload(title = "only title"),
        )

        val json = slot.captured.changeData
        assertTrue(json.contains("title"))
        assertFalse("tags must be omitted", json.contains("tags"))
        assertFalse("correspondent must be omitted", json.contains("correspondent"))
        assertFalse("documentType must be omitted", json.contains("documentType"))
        assertFalse("archiveSerialNumber must be omitted", json.contains("archiveSerialNumber"))
        assertFalse("created must be omitted", json.contains("created"))
    }

    // ===== queueDocumentDelete =====

    @Test
    fun `queueDocumentDelete writes correct PendingChange`() = runTest {
        val slot = slot<PendingChange>()
        coEvery { pendingChangeDao.insert(capture(slot)) } returns 1L

        repo.queueDocumentDelete(99)

        assertEquals("document", slot.captured.entityType)
        assertEquals(99, slot.captured.entityId)
        assertEquals("delete", slot.captured.changeType)
        assertEquals("{}", slot.captured.changeData)
    }

    // ===== queueTrashAction =====

    @Test
    fun `queueTrashAction RESTORE writes one PendingChange per id with changeType restore`() = runTest {
        repo.queueTrashAction(listOf(1, 2, 3), DocumentSyncRepository.TrashAction.RESTORE)

        coVerify(exactly = 1) { pendingChangeDao.insert(match { it.entityId == 1 && it.changeType == "restore" && it.entityType == "trash" }) }
        coVerify(exactly = 1) { pendingChangeDao.insert(match { it.entityId == 2 && it.changeType == "restore" }) }
        coVerify(exactly = 1) { pendingChangeDao.insert(match { it.entityId == 3 && it.changeType == "restore" }) }
    }

    @Test
    fun `queueTrashAction PERMANENT_DELETE writes one PendingChange per id with changeType delete`() = runTest {
        repo.queueTrashAction(listOf(7, 8), DocumentSyncRepository.TrashAction.PERMANENT_DELETE)

        coVerify(exactly = 1) { pendingChangeDao.insert(match { it.entityId == 7 && it.changeType == "delete" && it.entityType == "trash" }) }
        coVerify(exactly = 1) { pendingChangeDao.insert(match { it.entityId == 8 && it.changeType == "delete" }) }
    }

    // ===== executeOrQueue =====

    @Test
    fun `executeOrQueue online happy path returns Result success of online value`() = runTest {
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(true)

        val result = repo.executeOrQueue(
            online = { "online-result" },
            offlineQueueAndOptimistic = { "offline-result" },
        )

        assertTrue(result.isSuccess)
        assertEquals("online-result", result.getOrNull())
    }

    @Test
    fun `executeOrQueue offline runs offlineQueueAndOptimistic path`() = runTest {
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(false)

        val result = repo.executeOrQueue(
            online = { "online-result" },
            offlineQueueAndOptimistic = { "offline-result" },
        )

        assertTrue(result.isSuccess)
        assertEquals("offline-result", result.getOrNull())
    }

    @Test
    fun `executeOrQueue online IOException falls back to offlineQueueAndOptimistic`() = runTest {
        // BUG FIX VERIFICATION (#169 TOCTOU): previously, a mid-flight network drop
        // (state was true at entry, IOException thrown during API call) caused
        // the user's edit to be lost. Now, IOException triggers fallback to
        // the offline-queue path.
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(true)

        val result = repo.executeOrQueue<String>(
            online = { throw IOException("network drop") },
            offlineQueueAndOptimistic = { "fallback-result" },
        )

        assertTrue(result.isSuccess)
        assertEquals("fallback-result", result.getOrNull())
    }

    @Test
    fun `executeOrQueue online HttpException 4xx returns failure without fallback`() = runTest {
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(true)
        val errorBody = "{}".toResponseBody("application/json".toMediaTypeOrNull())
        var offlineCalled = false

        val result = repo.executeOrQueue<String>(
            online = { throw HttpException(Response.error<Any>(403, errorBody)) },
            offlineQueueAndOptimistic = { offlineCalled = true; "should-not-run" },
        )

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is PaperlessException)
        assertFalse("offline path must NOT run for HttpException", offlineCalled)
    }
}
