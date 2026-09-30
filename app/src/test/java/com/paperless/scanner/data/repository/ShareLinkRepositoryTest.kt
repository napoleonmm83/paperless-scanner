package com.paperless.scanner.data.repository

import com.google.gson.JsonParser
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.ServerCapabilityStore
import com.paperless.scanner.domain.model.ShareFileVersion
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Exercises the actual API annotations, Gson wire contract and repository session guards. */
class ShareLinkRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var store: ServerCapabilityStore
    private lateinit var client: OkHttpClient
    private lateinit var repository: ShareLinkRepository
    private lateinit var base: String

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        base = server.url("/paperless/").toString()
        store = ServerCapabilityStore()
        client = OkHttpClient.Builder()
            .cache(Cache(temporaryFolder.newFolder("http-cache"), 1_048_576L))
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        val api = Retrofit.Builder().baseUrl(base).client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(PaperlessApi::class.java)
        repository = ShareLinkRepository(api, ServerCapabilityRepository(api, store), store)
        establishVersion("2.0.0")
    }

    @After fun tearDown() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        client.cache?.close()
        server.shutdown()
    }

    private fun establishVersion(version: String?) {
        val generation = store.beginCredentialChange()
        store.finishCredentialChange(generation, base)
        val session = requireNotNull(store.captureRequest(base, generation))
        store.observe(session, server.url("/paperless/api/documents/"), version, "4")
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun link(slug: String = "publicToken", id: Int = 17) =
        """{"id":$id,"created":"2026-09-30T12:00:00Z","expiration":null,"slug":"$slug"}"""

    private fun request(): RecordedRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))

    @Test fun `list accepts bare array with metadata and preserves installation prefix`() = runBlocking {
        server.enqueue(json("""{"has_archive_version":true}"""))
        server.enqueue(json("[${link()}]"))

        val result = repository.load(42).getOrThrow()

        assertTrue(result.hasArchiveVersion)
        assertEquals(17, result.links.single().id)
        assertNull(result.links.single().expiration)
        assertEquals(server.url("/paperless/share/publicToken").toString(), result.links.single().url)
        val metadata = request()
        assertEquals("GET", metadata.method)
        assertEquals("/paperless/api/documents/42/metadata/", metadata.path)
        assertEquals("no-store", metadata.getHeader("Cache-Control"))
        val list = request()
        assertEquals("GET", list.method)
        assertEquals("/paperless/api/documents/42/share_links/", list.path)
        assertEquals("no-store", list.getHeader("Cache-Control"))
    }

    @Test fun `reloading never reuses cached metadata or link list`() = runBlocking {
        repeat(2) { index ->
            server.enqueue(json("""{"has_archive_version":false}""").setHeader("Cache-Control", "public, max-age=300"))
            server.enqueue(json(if (index == 0) "[${link()}]" else "[]").setHeader("Cache-Control", "public, max-age=300"))
        }

        assertEquals(1, repository.load(42).getOrThrow().links.size)
        assertTrue(repository.load(42).getOrThrow().links.isEmpty())
        assertEquals(4, server.requestCount)
        assertEquals(0, client.cache!!.hitCount())
    }

    @Test fun `create original sends document version and seven day UTC expiration`() = runBlocking {
        server.enqueue(json(link()))
        val before = Instant.now()
        assertEquals(17, repository.create(42, 7, ShareFileVersion.ORIGINAL).getOrThrow().id)
        val after = Instant.now()

        val post = request()
        assertEquals("POST", post.method)
        assertEquals("/paperless/api/share_links/", post.path)
        val body = JsonParser.parseString(post.body.readUtf8()).asJsonObject
        assertEquals(42, body["document"].asInt)
        assertEquals("original", body["file_version"].asString)
        val expiration = Instant.parse(body["expiration"].asString)
        assertFalse(expiration.isBefore(before.plus(Duration.ofDays(7))))
        assertFalse(expiration.isAfter(after.plus(Duration.ofDays(7))))
    }

    @Test fun `unlimited expiration creates original without an expiration date`() = runBlocking {
        server.enqueue(json(link()))
        assertTrue(repository.create(42, null, ShareFileVersion.ORIGINAL).isSuccess)
        val body = JsonParser.parseString(request().body.readUtf8()).asJsonObject
        assertEquals("original", body["file_version"].asString)
        assertTrue(!body.has("expiration") || body["expiration"].isJsonNull)
    }

    @Test fun `archive creation checks actual archive availability before publishing`() = runBlocking {
        server.enqueue(json("""{"has_archive_version":false}"""))
        assertTrue(repository.create(42, 7, ShareFileVersion.ARCHIVE).isFailure)
        assertEquals("/paperless/api/documents/42/metadata/", request().path)
        assertEquals(1, server.requestCount)
    }

    @Test fun `archive creation publishes only after metadata confirms archive`() = runBlocking {
        server.enqueue(json("""{"has_archive_version":true}"""))
        server.enqueue(json(link()))
        assertTrue(repository.create(42, 7, ShareFileVersion.ARCHIVE).isSuccess)
        assertEquals("GET", request().method)
        val post = request()
        assertEquals("POST", post.method)
        assertEquals("archive", JsonParser.parseString(post.body.readUtf8()).asJsonObject["file_version"].asString)
    }

    @Test fun `revoke accepts empty 204 response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(repository.delete(17).isSuccess)
        val delete = request()
        assertEquals("DELETE", delete.method)
        assertEquals("/paperless/api/share_links/17/", delete.path)
    }

    @Test fun `revoke preserves permission denial as failure`() = runBlocking {
        server.enqueue(json("""{"detail":"Permission denied"}""").setResponseCode(403))
        val error = repository.delete(17).exceptionOrNull()
        assertTrue(error is HttpException)
        assertEquals(403, (error as HttpException).code())
        assertEquals(1, server.requestCount)
    }

    @Test fun `list permission denial is reported instead of returning an empty list`() = runBlocking {
        server.enqueue(json("""{"has_archive_version":true}"""))
        server.enqueue(json("""{"detail":"Permission denied"}""").setResponseCode(403))
        val error = repository.load(42).exceptionOrNull()
        assertTrue(error is HttpException)
        assertEquals(403, (error as HttpException).code())
        assertEquals(2, server.requestCount)
    }

    @Test fun `server supplied slug cannot redirect public link to an arbitrary destination`() = runBlocking {
        server.enqueue(json(link(slug = "https://other.invalid/token")))
        assertTrue(repository.create(42, 7, ShareFileVersion.ORIGINAL).isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `old prerelease and unknown servers reject all actions before HTTP`() = runBlocking {
        listOf(null, "1.17.4", "2.0.0-rc.1").forEach { version ->
            establishVersion(version)
            assertTrue(repository.load(42).exceptionOrNull() is UnsupportedOperationException)
            assertTrue(repository.create(42, 7, ShareFileVersion.ORIGINAL).exceptionOrNull() is UnsupportedOperationException)
            assertTrue(repository.delete(17).exceptionOrNull() is UnsupportedOperationException)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `late created link from old account is discarded`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                establishVersion("2.0.0") // Same server, different credentials generation.
                return json(link())
            }
        }
        val result = repository.create(42, 7, ShareFileVersion.ORIGINAL)
        assertTrue(result.isFailure)
        assertEquals("Server session changed", result.exceptionOrNull()?.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun `account change after metadata prevents subsequent list request`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                establishVersion("2.0.0")
                return json("""{"has_archive_version":true}""")
            }
        }
        assertTrue(repository.load(42).isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `account change during archive check prevents publication`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                establishVersion("2.0.0")
                return json("""{"has_archive_version":true}""")
            }
        }
        assertTrue(repository.create(42, 7, ShareFileVersion.ARCHIVE).isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `response slug length and positive identifier boundaries reject malformed links`() = runBlocking {
        val cases = listOf(Triple("a".repeat(128), 1, true), Triple("a".repeat(129), 1, false), Triple("ok", 0, false), Triple("", 1, false))
        cases.forEach { (slug, id, accepted) ->
            server.enqueue(json("""{"has_archive_version":false}"""))
            server.enqueue(json("[${link(slug, id)}]"))
            assertEquals(accepted, repository.load(42).isSuccess)
        }
    }

    @Test fun `expected old click session cannot create or revoke in new account`() = runBlocking {
        val clickedGeneration = store.snapshotGeneration()
        establishVersion("2.0.0")
        assertTrue(repository.create(42, 7, ShareFileVersion.ORIGINAL, clickedGeneration).isFailure)
        assertTrue(repository.delete(17, clickedGeneration).isFailure)
        assertTrue(repository.load(42, clickedGeneration).isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun `invalid creation and revoke input never reaches server`() = runBlocking {
        listOf(0, -1, 366).forEach { days ->
            assertTrue(repository.create(42, days, ShareFileVersion.ORIGINAL).isFailure)
        }
        assertTrue(repository.create(0, 7, ShareFileVersion.ORIGINAL).isFailure)
        assertTrue(repository.load(-1).isFailure)
        assertTrue(repository.delete(0).isFailure)
        assertEquals(0, server.requestCount)
    }
}
