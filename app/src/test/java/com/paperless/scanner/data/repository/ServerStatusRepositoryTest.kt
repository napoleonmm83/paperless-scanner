package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.ServerStatusResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Pins the Issue #153 (PR-A) contract change: [ServerStatusRepository.getServerStatus]
 * now returns `Result<ServerStatus>` (domain), mapped from the API DTO, while keeping
 * the x-version header merge that existed before. Pure JVM (mockk'd [PaperlessApi]).
 */
class ServerStatusRepositoryTest {

    private val api = mockk<PaperlessApi>()
    private val repository = ServerStatusRepository(api)

    @Test
    fun `getServerStatus maps the api body to a domain ServerStatus`() = runTest {
        coEvery { api.getServerStatus() } returns
            Response.success(ServerStatusResponse(paperlessVersion = "2.6.0", serverOs = "Linux"))

        val result = repository.getServerStatus()

        assertTrue(result.isSuccess)
        val status = result.getOrNull()
        assertEquals("2.6.0", status?.paperlessVersion)
        assertEquals("Linux", status?.serverOs)
    }

    @Test
    fun `getServerStatus falls back to the x-version header when body version is blank`() = runTest {
        coEvery { api.getServerStatus() } returns
            Response.success(
                // Whitespace-only (not null) so the repository's isNotBlank() branch
                // is the one exercised, matching this test's name (CodeRabbit PR #340).
                ServerStatusResponse(paperlessVersion = "   "),
                Headers.headersOf("x-version", "2.7.0"),
            )

        val result = repository.getServerStatus()

        assertEquals("2.7.0", result.getOrNull()?.paperlessVersion)
    }
}
