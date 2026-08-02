package com.paperless.scanner.data.api.models

import com.google.gson.JsonParseException
import com.paperless.scanner.di.GsonProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [TasksResponseDeserializer].
 *
 * `/api/tasks/` is a bare JSON array up to API v9 and a paginated object from
 * v10 on. Handed the v10 shape, a plain `List<PaperlessTask>` binding fails with
 * "Expected BEGIN_ARRAY but was BEGIN_OBJECT" — which is exactly how document
 * processing status would break against a Paperless-ngx 3.0 server.
 *
 * Goes through [GsonProvider.instance] rather than a locally built Gson so the
 * production registration is covered too: an unregistered adapter would fail
 * these tests instead of passing silently.
 */
class TasksResponseDeserializerTest {

    private val gson = GsonProvider.instance

    private val v9Task = """
        {
          "id": 1,
          "task_id": "abc-123",
          "task_file_name": "scan.pdf",
          "date_created": "2026-08-02T10:00:00Z",
          "date_done": null,
          "type": "file",
          "status": "SUCCESS",
          "result": "ok",
          "acknowledged": false
        }
    """.trimIndent()

    @Test
    fun `parses the unpaginated API v9 array`() {
        val json = "[$v9Task]"

        val response = gson.fromJson(json, TasksResponse::class.java)

        assertEquals(1, response.results.size)
        assertEquals("abc-123", response.results[0].taskId)
        assertEquals(1, response.count)
        // No envelope means no further pages — fetchAllPages must stop after one request.
        assertNull(response.next)
    }

    @Test
    fun `parses the paginated API v10 page object`() {
        val json = """
            {
              "count": 1,
              "next": "https://paperless.example.com/api/tasks/?page=2",
              "previous": null,
              "results": [$v9Task]
            }
        """.trimIndent()

        val response = gson.fromJson(json, TasksResponse::class.java)

        assertEquals(1, response.results.size)
        assertEquals("abc-123", response.results[0].taskId)
        assertEquals(1, response.count)
        // The link must survive so fetchAllPages keeps walking instead of
        // silently returning only page one.
        assertEquals("https://paperless.example.com/api/tasks/?page=2", response.next)
    }

    @Test
    fun `reads the API v10 trigger_source rename into type`() {
        // v10 renamed `type` to `trigger_source`. Without the @SerializedName
        // alternate this lands as null in a non-null Kotlin field.
        val json = """
            {
              "count": 1,
              "next": null,
              "previous": null,
              "results": [
                {
                  "id": 2,
                  "task_id": "def-456",
                  "date_created": "2026-08-02T10:00:00Z",
                  "trigger_source": "api_upload",
                  "status": "PENDING"
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, TasksResponse::class.java)

        assertEquals("api_upload", response.results[0].type)
    }

    @Test
    fun `treats a recognisable page object without results as empty`() {
        // `count` identifies this as a DRF page envelope, so an omitted `results`
        // is a genuinely empty page rather than an unknown payload.
        val response = gson.fromJson("""{"count": 0, "next": null, "previous": null}""", TasksResponse::class.java)

        assertTrue(response.results.isEmpty())
        assertNull(response.next)
    }

    @Test
    fun `tolerates a null results member instead of throwing ClassCastException`() {
        // Gson's getAsJsonArray is an unchecked cast: a JSON null member yields
        // JsonNull, and casting that to JsonArray blows up past any elvis operator.
        val response = gson.fromJson(
            """{"count": 0, "next": null, "previous": null, "results": null}""",
            TasksResponse::class.java
        )

        assertTrue(response.results.isEmpty())
    }

    @Test
    fun `tolerates a non-primitive next member`() {
        // `asString` throws on an object; the envelope should still parse.
        val response = gson.fromJson(
            """{"count": 0, "next": {"unexpected": true}, "results": []}""",
            TasksResponse::class.java
        )

        assertNull(response.next)
        assertTrue(response.results.isEmpty())
    }

    @Test
    fun `rejects an unrecognised payload instead of reporting zero tasks`() {
        // Reporting "no tasks" for a payload we do not understand would leave the
        // processing list silently empty AND stop the poll, with no error anywhere
        // — the exact failure mode PR #403 was filed to remove. Failing loudly puts
        // it on the existing error path instead.
        assertThrows(JsonParseException::class.java) {
            gson.fromJson(""""unexpected"""", TasksResponse::class.java)
        }
    }

    @Test
    fun `rejects a JSON object that is not a task page`() {
        // No `results`, no `count` — not a task page. Must not become "0 tasks".
        assertThrows(JsonParseException::class.java) {
            gson.fromJson("""{"detail": "something else entirely"}""", TasksResponse::class.java)
        }
    }
}
