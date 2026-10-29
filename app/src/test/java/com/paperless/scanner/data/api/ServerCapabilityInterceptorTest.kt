package com.paperless.scanner.data.api

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ServerCapabilityInterceptorTest {
    private val store = ServerCapabilityStore()
    private val base = "https://example.test/"
    private val request = Request.Builder().url("${base}api/documents/")
        .header("Authorization", "Token test")
        .tag(ServerRequestSession::class.java, store.captureRequest(base, store.snapshotGeneration()))
        .build()

    private fun response(code: Int = 200, networkCode: Int? = 200, actual: Request = request): Response {
        val builder = Response.Builder().request(actual).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").header("X-Version", "2.20.0").header("X-Api-Version", "9")
            .body("untouched body".toResponseBody())
        if (networkCode != null) builder.networkResponse(Response.Builder().request(actual)
            .protocol(Protocol.HTTP_1_1).code(networkCode).message("network").build())
        return builder.build()
    }

    private fun intercept(response: Response): Response {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns response
        return ServerCapabilityInterceptor(store).intercept(chain)
    }

    @Test fun `successful network response observes version and preserves body`() {
        val response = response()
        assertSame(response, intercept(response))
        assertEquals(9, store.state.value.apiVersion)
        assertEquals("untouched body", response.body!!.string())
    }

    @Test fun `cache revalidation and failed responses do not confirm versions`() {
        listOf(response(networkCode = null), response(networkCode = 304), response(code = 403, networkCode = 403)).forEach {
            intercept(it).close()
            assertNull(store.state.value.serverVersion)
        }
    }

    @Test fun `redirect to another origin and unauthenticated final response ignored`() {
        intercept(response(actual = request.newBuilder().url("https://other.test/api/documents/").build())).close()
        assertNull(store.state.value.serverVersion)
        intercept(response(actual = request.newBuilder().removeHeader("Authorization").build())).close()
        assertNull(store.state.value.serverVersion)
    }
}
