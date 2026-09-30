package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.ServerCapabilityStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ServerCapabilityRepositoryTest {
    private val api = mockk<PaperlessApi>()
    private val store = ServerCapabilityStore()
    private val repository = ServerCapabilityRepository(api, store)

    @Test fun `refresh uses small document query and known version skips probe`() = runTest {
        coEvery { api.getDocuments(pageSize = 1) } returns mockk()
        assertTrue(repository.refresh().isSuccess)
        coVerify(exactly = 1) { api.getDocuments(pageSize = 1) }
        val base = "https://example.test/"
        val session = requireNotNull(store.captureRequest(base, store.snapshotGeneration()))
        store.observe(session, "${base}api/documents/".toHttpUrl(), "2.20.0", "9")
        assertTrue(repository.refresh().isSuccess)
        coVerify(exactly = 1) { api.getDocuments(pageSize = 1) }
    }

    @Test fun `failure is returned and cancellation propagates`() = runTest {
        coEvery { api.getDocuments(pageSize = 1) } throws IllegalStateException("network failed")
        assertTrue(repository.refresh().isFailure)
        coEvery { api.getDocuments(pageSize = 1) } throws CancellationException("cancelled")
        try {
            repository.refresh()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `future feature call guard rejects before network access`() {
        listOf("share_links", "pdf_edit", "unknown_feature").forEach { id ->
            try {
                repository.requireFeature(id)
                fail("Unavailable feature must be rejected")
            } catch (_: UnsupportedOperationException) { }
        }
        coVerify(exactly = 0) { api.getDocuments(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
