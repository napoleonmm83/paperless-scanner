package com.paperless.scanner.data.repository

import androidx.test.filters.LargeTest
import com.google.gson.Gson
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.testing.BaseRoomRepositoryTest
import io.mockk.every
import io.mockk.mockk
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

/**
 * Repository tests for [DocumentSyncRepository].
 *
 * Uses a real in-memory Room database (see [BaseRoomRepositoryTest]) so all
 * `pendingChangeDao` writes go through the real schema; we then read the
 * inserted rows back to assert their content. `ServerHealthMonitor` is a
 * service collaborator and remains mocked.
 */
@LargeTest
class DocumentSyncRepositoryTest : BaseRoomRepositoryTest() {

    private lateinit var pendingChangeDao: PendingChangeDao
    private lateinit var serverHealthMonitor: ServerHealthMonitor
    private lateinit var gson: Gson
    private lateinit var repo: DocumentSyncRepository

    @Before
    fun setup() {
        serverHealthMonitor = mockk(relaxed = true)
        pendingChangeDao = database.pendingChangeDao()
        gson = Gson()
        repo = DocumentSyncRepository(pendingChangeDao, serverHealthMonitor, gson)
    }

    // ===== queueDocumentUpdate =====

    @Test
    fun `queueDocumentUpdate writes PendingChange with gson-serialized payload`() = runTest {
        repo.queueDocumentUpdate(
            42,
            DocumentSyncRepository.DocumentUpdatePayload(title = "hello", tags = listOf(1, 2)),
        )

        val rows = pendingChangeDao.getAll()
        assertEquals(1, rows.size)
        val captured = rows.first()
        assertEquals("document", captured.entityType)
        assertEquals(42, captured.entityId)
        assertEquals("update", captured.changeType)
        assertTrue(captured.changeData.contains("\"title\":\"hello\""))
        assertTrue(captured.changeData.contains("\"tags\":[1,2]"))
        assertFalse(captured.changeData.contains("correspondent"))
        assertFalse(captured.changeData.contains("documentType"))
    }

    @Test
    fun `queueDocumentUpdate handles title with embedded quotes safely`() = runTest {
        // BUG FIX VERIFICATION (#169): the previous buildString impl produced
        // invalid JSON for titles containing `"`. gson.toJson escapes correctly.
        repo.queueDocumentUpdate(
            7,
            DocumentSyncRepository.DocumentUpdatePayload(title = """He said "hi""""),
        )

        val captured = pendingChangeDao.getAll().single()
        // The captured changeData must be valid JSON that round-trips back to the
        // original title via the same parser SyncManager uses.
        val parsed = gson.fromJson(captured.changeData, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val parsedMap = parsed as Map<String, Any?>
        assertEquals("""He said "hi"""", parsedMap["title"])
    }

    @Test
    fun `queueDocumentUpdate omits null payload fields from JSON`() = runTest {
        repo.queueDocumentUpdate(
            1,
            DocumentSyncRepository.DocumentUpdatePayload(title = "only title"),
        )

        val json = pendingChangeDao.getAll().single().changeData
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
        repo.queueDocumentDelete(99)

        val captured = pendingChangeDao.getAll().single()
        assertEquals("document", captured.entityType)
        assertEquals(99, captured.entityId)
        assertEquals("delete", captured.changeType)
        assertEquals("{}", captured.changeData)
    }

    // ===== queueTrashAction =====

    @Test
    fun `queueTrashAction RESTORE writes one PendingChange per id with changeType restore`() = runTest {
        repo.queueTrashAction(listOf(1, 2, 3), DocumentSyncRepository.TrashAction.RESTORE)

        val rows = pendingChangeDao.getAll()
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.entityType == "trash" && it.changeType == "restore" })
        assertEquals(setOf(1, 2, 3), rows.mapNotNull { it.entityId }.toSet())
    }

    @Test
    fun `queueTrashAction PERMANENT_DELETE writes one PendingChange per id with changeType delete`() = runTest {
        repo.queueTrashAction(listOf(7, 8), DocumentSyncRepository.TrashAction.PERMANENT_DELETE)

        val rows = pendingChangeDao.getAll()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.entityType == "trash" && it.changeType == "delete" })
        assertEquals(setOf(7, 8), rows.mapNotNull { it.entityId }.toSet())
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

    @Test
    fun `executeOrQueue re-throws CancellationException to preserve structured concurrency`() = runTest {
        // Coroutine cancellation MUST propagate, not be wrapped in Result.failure.
        // Previously catch(Exception) swallowed CancellationException, breaking cancel.
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(true)
        var offlineCalled = false

        val thrown = try {
            repo.executeOrQueue<String>(
                online = { throw kotlin.coroutines.cancellation.CancellationException("scope cancelled") },
                offlineQueueAndOptimistic = { offlineCalled = true; "should-not-run" },
            )
            null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            e
        }

        assertNotNull("CancellationException must propagate, not be wrapped", thrown)
        assertEquals("scope cancelled", thrown!!.message)
        assertFalse("offline path must NOT run for CancellationException", offlineCalled)
    }
}
