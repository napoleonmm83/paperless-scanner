package com.paperless.scanner.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class NetworkRetryTest {

    @Test
    fun `successful call returns immediately without retry`() = runTest {
        var attempts = 0
        val result = withRetry(maxRetries = 3, initialDelayMs = 1L, maxDelayMs = 1L) {
            attempts++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `IOException retries up to maxRetries then throws`() = runTest {
        var attempts = 0
        var thrown: Throwable? = null
        try {
            withRetry<String>(maxRetries = 2, initialDelayMs = 1L, maxDelayMs = 1L) {
                attempts++
                throw IOException("network down")
            }
        } catch (e: IOException) {
            thrown = e
        }
        assertNotNull(thrown)
        // 1 initial + 2 retries = 3 attempts
        assertEquals(3, attempts)
    }

    @Test
    fun `IOException succeeds on retry`() = runTest {
        var attempts = 0
        val result = withRetry(maxRetries = 3, initialDelayMs = 1L, maxDelayMs = 1L) {
            attempts++
            if (attempts < 3) throw IOException("flaky")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `HttpException 5xx retries until exhausted`() = runTest {
        var attempts = 0
        var thrown: HttpException? = null
        try {
            withRetry<String>(maxRetries = 2, initialDelayMs = 1L, maxDelayMs = 1L) {
                attempts++
                throw httpException(503)
            }
        } catch (e: HttpException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals(503, thrown!!.code())
        assertEquals(3, attempts)
    }

    @Test
    fun `HttpException 4xx is not retried`() = runTest {
        var attempts = 0
        var thrown: HttpException? = null
        try {
            withRetry<String>(maxRetries = 3, initialDelayMs = 1L, maxDelayMs = 1L) {
                attempts++
                throw httpException(404)
            }
        } catch (e: HttpException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals(404, thrown!!.code())
        assertEquals(1, attempts)
    }

    @Test
    fun `HttpException 401 is not retried`() = runTest {
        var attempts = 0
        try {
            withRetry<String>(maxRetries = 3, initialDelayMs = 1L, maxDelayMs = 1L) {
                attempts++
                throw httpException(401)
            }
        } catch (_: HttpException) {
            // expected
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `CancellationException propagates immediately and is never retried`() = runTest {
        var attempts = 0
        var thrown: CancellationException? = null
        try {
            withRetry<String>(maxRetries = 3, initialDelayMs = 1L, maxDelayMs = 1L) {
                attempts++
                throw CancellationException("cancelled")
            }
        } catch (e: CancellationException) {
            thrown = e
        }
        assertNotNull(thrown)
        // catch order matters: CancellationException MUST come before any
        // IllegalStateException catch (it inherits from IllegalStateException);
        // see PR #195 for the precedent.
        assertEquals(1, attempts)
    }

    @Test
    fun `5xx then success on retry returns body`() = runTest {
        var attempts = 0
        val result = withRetry(maxRetries = 3, initialDelayMs = 1L, maxDelayMs = 1L) {
            attempts++
            if (attempts == 1) throw httpException(502)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `IllegalStateException propagates without retry`() = runTest {
        // CancellationException extends IllegalStateException; we must verify
        // our catch order does NOT swallow plain IllegalStateException as
        // CancellationException, and does not retry it as IOException either.
        var attempts = 0
        var thrown: IllegalStateException? = null
        try {
            withRetry<String>(maxRetries = 3, initialDelayMs = 1L, maxDelayMs = 1L) {
                attempts++
                throw IllegalStateException("boom")
            }
        } catch (e: IllegalStateException) {
            // CancellationException is also an IllegalStateException — accept
            // either, but verify it was thrown only once.
            if (e is CancellationException) {
                fail("Plain IllegalStateException was misclassified as CancellationException")
            }
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals(1, attempts)
    }

    @Test
    fun `delay is capped at maxDelayMs`() = runTest {
        // initialDelay=100, doubling each attempt: 100, 200, 400. maxDelay=150
        // caps each delay at 150. Expected timeline: 100 + 150 + 150 = 400ms.
        // testScheduler.currentTime advances per delay() so we can assert the
        // cap is enforced rather than only counting attempts.
        var attempts = 0
        val result = withRetry(
            maxRetries = 3,
            initialDelayMs = 100L,
            maxDelayMs = 150L,
        ) {
            attempts++
            if (attempts < 4) throw IOException("flaky")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(4, attempts)
        assertEquals(400L, testScheduler.currentTime)
    }

    @Test
    fun `zero maxRetries throws on first failure`() = runTest {
        var attempts = 0
        try {
            withRetry<String>(maxRetries = 0, initialDelayMs = 1L, maxDelayMs = 1L) {
                attempts++
                throw IOException("immediate")
            }
        } catch (e: IOException) {
            assertTrue(e.message?.contains("immediate") == true)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `withResponseRetry retries 5xx Response and returns success`() = runTest {
        var attempts = 0
        val result = withResponseRetry(maxRetries = 3, initialDelayMs = 1L, maxDelayMs = 1L) {
            attempts++
            if (attempts == 1) Response.error<String>(503, errorBody())
            else Response.success("ok")
        }
        assertTrue(result.isSuccessful)
        assertEquals("ok", result.body())
        assertEquals(2, attempts)
    }

    @Test
    fun `withResponseRetry returns 4xx Response unchanged without retry`() = runTest {
        var attempts = 0
        val result = withResponseRetry<String>(maxRetries = 3, initialDelayMs = 1L, maxDelayMs = 1L) {
            attempts++
            Response.error(404, errorBody())
        }
        assertEquals(404, result.code())
        assertEquals(1, attempts)
    }

    @Test
    fun `withResponseRetry exhausts on persistent 5xx and throws HttpException`() = runTest {
        var attempts = 0
        var thrown: HttpException? = null
        try {
            withResponseRetry<String>(maxRetries = 2, initialDelayMs = 1L, maxDelayMs = 1L) {
                attempts++
                Response.error(500, errorBody())
            }
        } catch (e: HttpException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals(500, thrown!!.code())
        assertEquals(3, attempts)
    }

    @Test
    fun `withResponseRetry returns successful Response immediately`() = runTest {
        var attempts = 0
        val result = withResponseRetry(maxRetries = 3, initialDelayMs = 1L, maxDelayMs = 1L) {
            attempts++
            Response.success("first-try")
        }
        assertEquals("first-try", result.body())
        assertEquals(1, attempts)
    }

    private fun errorBody() = "{}".toResponseBody("application/json".toMediaTypeOrNull())

    private fun httpException(code: Int): HttpException {
        return HttpException(Response.error<Any>(code, errorBody()))
    }
}
