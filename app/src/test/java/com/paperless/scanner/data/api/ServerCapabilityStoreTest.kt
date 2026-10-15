package com.paperless.scanner.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ServerCapabilityStoreTest {
    private val store = ServerCapabilityStore()
    private val base = "https://example.test/paperless/"
    private val request = "${base}api/documents/".toHttpUrl()
    private fun session() = requireNotNull(store.captureRequest(base, store.snapshotGeneration()))

    @Test fun `startup capture initializes once and observes separate versions`() {
        val session = session()
        store.observe(session, request, "v2.20.0", "9")
        assertEquals("v2.20.0", store.state.value.displayVersion)
        assertEquals(9, store.state.value.apiVersion)
        assertEquals(base, store.state.value.serverBase)
        assertNotNull(store.state.value.serverVersion)
        assertNull(store.captureRequest("https://other.test/", store.snapshotGeneration()))
    }

    @Test fun `same server account change rejects late response and inactive capture`() {
        val old = session()
        val generation = store.beginCredentialChange()
        assertNull(store.state.value.serverBase)
        assertNull(store.captureRequest(base, generation))
        store.finishCredentialChange(generation, base)
        store.observe(old, request, "2.20.0", "9")
        assertNull(store.state.value.serverVersion)
        store.observe(session(), request, "2.21.0", "10")
        assertEquals(10, store.state.value.apiVersion)
    }

    @Test fun `logout cannot be reactivated by startup capture or stale finish`() {
        val old = session()
        val first = store.beginCredentialChange()
        val logout = store.beginCredentialChange()
        store.finishCredentialChange(first, base)
        assertNull(store.captureRequest(base, logout))
        store.finishCredentialChange(logout, null)
        assertNull(store.captureRequest(base, logout))
        store.observe(old, request, "2.20.0", "9")
        assertNull(store.state.value.serverBase)
    }

    @Test fun `observation rejects different origin port path and scheme`() {
        val session = session()
        listOf("https://other.test/paperless/api/documents/", "http://example.test/paperless/api/documents/",
            "https://example.test:8443/paperless/api/documents/", "https://example.test/paperless-other/api/documents/",
            "https://example.test/api/documents/").forEach {
            store.observe(session, it.toHttpUrl(), "2.20.0", "9")
            assertNull(it, store.state.value.serverVersion)
        }
    }

    @Test fun `missing and invalid headers stay conservative independently`() {
        val session = session()
        store.observe(session, request, "2.20.0", "9")
        store.observe(session, request, null, null)
        assertNull(store.state.value.serverVersion)
        assertNull(store.state.value.displayVersion)
        assertNull(store.state.value.apiVersion)
        store.observe(session, request, "dev", "9")
        assertNull(store.state.value.serverVersion)
        assertEquals(9, store.state.value.apiVersion)
        store.observe(session, request, "2.20.0", "dev")
        assertNotNull(store.state.value.serverVersion)
        assertNull(store.state.value.apiVersion)
    }

    @Test fun `invalid bases and generation mismatch cannot capture requests`() {
        assertNull(store.captureRequest(base, store.snapshotGeneration() + 1))
        assertNull(store.captureRequest("invalid", store.snapshotGeneration()))
        assertNull(store.captureRequest("https://user:password@example.test/", store.snapshotGeneration()))
        assertNotNull(store.captureRequest(base, store.snapshotGeneration()))
    }

    @Test fun `switching server clears old evidence and validates normalized base`() {
        val old = session()
        store.observe(old, request, "2.20.0", "9")
        val generation = store.beginCredentialChange()
        val nextBase = "https://other.test/prefix"
        store.finishCredentialChange(generation, nextBase)
        assertNull(store.state.value.serverVersion)
        assertEquals("$nextBase/", store.state.value.serverBase)
        store.observe(old, request, "2.21.0", "10")
        assertNull(store.state.value.serverVersion)
        val next = requireNotNull(store.captureRequest(nextBase, generation))
        store.observe(next, "$nextBase/api/documents/".toHttpUrl(), "2.22.0", "10")
        assertEquals(10, store.state.value.apiVersion)
    }
}
