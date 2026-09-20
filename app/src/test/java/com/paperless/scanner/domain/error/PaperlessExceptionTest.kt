package com.paperless.scanner.domain.error

import android.content.Context
import com.paperless.scanner.R
import com.paperless.scanner.data.api.CleartextNotAllowlistedException
import com.paperless.scanner.data.network.CertificatePinMismatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Pin for [PaperlessException]'s two mappers.
 *
 * This file exists because of a user report on 2026-09-20 that repeated a Play review
 * of 2026-07-27: the in-app PDF viewer showed a bare "Unbekannter Fehler", and the
 * release notes for 1.5.238 had claimed that message was gone. It was not. PR #403
 * replaced the raw `Throwable.message` with the localised resource, which fixed a
 * genuine defect, but [PaperlessException.UnknownError] resolved to
 * `R.string.error_unknown` = "Unknown error" — which in German is exactly
 * "Unbekannter Fehler". The text was translated, not identified.
 *
 * Two properties are pinned here, and they are different things:
 *
 *  - **Identity.** A failure keeps the most specific type the mapper can prove. Six
 *    IOException subclasses used to be flattened to NetworkError by catch blocks that
 *    sat ahead of [PaperlessException.from]; those are gone, and these tests fail if
 *    they come back.
 *  - **Discriminability.** When the mapper genuinely cannot classify, the message still
 *    carries a short tag. A screenshot of "Unknown error (HTTP 100)" is a bug report;
 *    a screenshot of "Unknown error" is not, which is why the cause behind the July
 *    review was still unknown seven weeks later.
 */
@RunWith(RobolectricTestRunner::class)
class PaperlessExceptionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    // ---------------------------------------------------------------- fromHttpCode

    @Test
    fun `406 is content negotiation, not a generic client error`() {
        val error = PaperlessException.fromHttpCode(406)

        // Must NOT fall into ClientError's else-branch ("Request error (406)"), which
        // is what happened while 406 sat inside the 400..499 range.
        assertTrue("406 fell through to ClientError", error is PaperlessException.ContentError)
        assertEquals(
            context.getString(R.string.error_not_acceptable, 406),
            error.getLocalizedMessage(context)
        )
    }

    @Test
    fun `a 304 is named rather than swallowed as unknown`() {
        // 304 stands in for the whole 3xx range here; it is not a claim that 304 is
        // likely. An earlier version of this comment said our own OkHttp cache puts it
        // within reach — the production code retracts that in so many words
        // (PaperlessException.kt: the cache answers a 304 internally and hands back the
        // stored 200, and the app sends no conditional request). What can actually
        // arrive is 305, 306, a 3xx without a Location header, or an abandoned redirect
        // chain: rare, and cheap insurance against the unidentifiable fallback.
        val error = PaperlessException.fromHttpCode(304)

        assertTrue(error is PaperlessException.ContentError)
        assertEquals(
            context.getString(R.string.error_unexpected_redirect, 304),
            error.getLocalizedMessage(context)
        )
        assertFalse(
            "a 304 still renders as the unidentifiable fallback",
            error.getLocalizedMessage(context) == context.getString(R.string.error_unknown)
        )
    }

    @Test
    fun `the whole 3xx range is covered, not just the cache case`() {
        // 305 and 306 are dead in practice but free to cover, and a 3xx without a
        // Location header can be anything. None of them should reach UnknownError.
        listOf(304, 305, 306, 399).forEach { code ->
            assertTrue(
                "HTTP $code fell through to UnknownError",
                PaperlessException.fromHttpCode(code) is PaperlessException.ContentError
            )
        }
    }

    @Test
    fun `an unclassifiable status still carries its number`() {
        val error = PaperlessException.fromHttpCode(100, "informational")

        assertTrue(error is PaperlessException.UnknownError)
        assertEquals("HTTP 100", (error as PaperlessException.UnknownError).diagnosticTag)
        assertTrue(
            "the status is missing from the user-visible message",
            error.getLocalizedMessage(context).contains("HTTP 100")
        )
    }

    @Test
    fun `the server message never reaches the screen`() {
        // serverMessage can hold a response body. It belongs in logs, not in a string
        // rendered on the device and shipped as an analytics dimension.
        val error = PaperlessException.fromHttpCode(100, "secret-looking payload")

        assertFalse(
            error.getLocalizedMessage(context).contains("secret-looking payload")
        )
    }

    @Test
    fun `the ordinary client and server ranges are untouched`() {
        assertTrue(PaperlessException.fromHttpCode(404) is PaperlessException.ClientError)
        assertTrue(PaperlessException.fromHttpCode(500) is PaperlessException.ServerError)
        assertTrue(PaperlessException.fromHttpCode(401) is PaperlessException.AuthError)
        assertTrue(PaperlessException.fromHttpCode(403) is PaperlessException.AuthError)
        assertTrue(PaperlessException.fromHttpCode(429) is PaperlessException.RateLimitError)
    }

    // --------------------------------------------------------------- diagnosticTag

    @Test
    fun `every mapped error offers a discriminator, and the HTTP ones carry the code`() {
        // The analytics event and the support report both read this, so "every subtype
        // answers" matters more than any single value. Without the ContentError override
        // a 304 and a 406 would both report "ContentError" — the same
        // cannot-tell-them-apart problem this change exists to remove, one level up.
        assertEquals("HTTP 304", PaperlessException.fromHttpCode(304).diagnosticTag)
        assertEquals("HTTP 406", PaperlessException.fromHttpCode(406).diagnosticTag)
        assertEquals("HTTP 100", PaperlessException.fromHttpCode(100).diagnosticTag)

        // Types whose own message already names the cause fall back to the class name.
        assertEquals("ClientError", PaperlessException.fromHttpCode(404).diagnosticTag)
        assertEquals("ServerError", PaperlessException.fromHttpCode(500).diagnosticTag)
        assertEquals("AuthError", PaperlessException.fromHttpCode(401).diagnosticTag)
    }

    @Test
    fun `the five unreachable reasons do not collapse into one label`() {
        // ServerUnreachable covers the most common download failures there are, so the
        // default class-name tag would have reported DNS failure, refused connection,
        // timeout and TLS trust as the same string — counting a set without splitting
        // it, on the busiest type, in the same change that added the property to stop
        // exactly that.
        val tags = listOf(
            UnknownHostException(),
            ConnectException(),
            SocketTimeoutException(),
            SSLHandshakeException("bad cert")
        ).map { PaperlessException.from(it).diagnosticTag }

        assertEquals("two reasons share a tag", tags.size, tags.toSet().size)
        assertTrue(tags.contains(ServerOfflineReason.DNS_FAILURE.name))
        assertTrue(tags.contains(ServerOfflineReason.SSL_ERROR.name))
    }

    @Test
    fun `two content errors that a report tells apart are not equal`() {
        // These two differ ONLY in the tag - same resId, same format args. An earlier
        // version of this test compared fromHttpCode(304) with (305), which already
        // differ in messageFormatArgs; deleting the tag comparison from equals left it
        // green, so it guarded nothing. A watchdog that cannot fail is furniture.
        val a = PaperlessException.ContentError(
            R.string.error_unexpected_redirect,
            arrayOf(304),
            diagnosticTag = "HTTP 304"
        )
        val b = PaperlessException.ContentError(
            R.string.error_unexpected_redirect,
            arrayOf(304),
            diagnosticTag = "cache-revalidation"
        )

        assertFalse("the tag is missing from equals", a == b)
        assertFalse("the tag is missing from hashCode", a.hashCode() == b.hashCode())
    }

    @Test
    fun `the discriminator never carries server text`() {
        // It is rendered on screen and shipped as an analytics dimension. A host name or
        // a response body in here would be both a cardinality problem and a leak.
        val error = PaperlessException.fromHttpCode(406, "https://docs.internal.example/secret")

        assertFalse(error.diagnosticTag.contains("example"))
        assertFalse(error.getLocalizedMessage(context).contains("example"))
    }

    // ----------------------------------------------------------------------- from

    @Test
    fun `a TLS trust failure is a server problem, not an internet problem`() {
        // These extend IOException, so before this branch existed a bad or expired
        // certificate told the user to check their internet connection — which sends
        // them to their router over a problem on their server.
        listOf(
            SSLHandshakeException("handshake failed"),
            SSLPeerUnverifiedException("peer not verified")
        ).forEach { thrown ->
            val error = PaperlessException.from(thrown)

            assertTrue("${thrown::class.simpleName} lost its identity", error is PaperlessException.ServerUnreachable)
            error as PaperlessException.ServerUnreachable
            assertEquals(ServerOfflineReason.SSL_ERROR, error.reason)
            // The exception itself has to survive, or the crash report names the mapper
            // instead of the handshake and is worth nothing.
            assertTrue("the handshake exception was discarded", error.cause === thrown)
        }
    }

    @Test
    fun `an ordinary TLS read error stays a network error`() {
        // THE negative control for the test above, and the reason this branch matches two
        // named subclasses instead of the SSLException family.
        //
        // Conscrypt raises a plain SSLException for ordinary I/O on an already-established
        // connection — "Read error: ... Connection reset by peer", "Write error: ...
        // Broken pipe" — which is the normal outcome of changing networks mid-download.
        // Matching the family would relabel every one of those as a certificate problem
        // and send the user to inspect a certificate that is perfectly fine. That would
        // have been a worse defect than the one the branch was added to fix, on a much
        // more common path.
        val error = PaperlessException.from(SSLException("Read error: Connection reset by peer"))

        assertTrue(
            "a plain SSLException was mistaken for a certificate problem",
            error is PaperlessException.NetworkError
        )
    }

    @Test
    fun `the typed IOException subclasses keep their identity`() {
        // Each of these is an IOException. A `catch (IOException)` ahead of from()
        // erased all of them; this is the pin against that returning.
        assertEquals(
            ServerOfflineReason.DNS_FAILURE,
            (PaperlessException.from(UnknownHostException()) as PaperlessException.ServerUnreachable).reason
        )
        assertEquals(
            ServerOfflineReason.CONNECTION_REFUSED,
            (PaperlessException.from(ConnectException()) as PaperlessException.ServerUnreachable).reason
        )
        assertEquals(
            ServerOfflineReason.TIMEOUT,
            (PaperlessException.from(SocketTimeoutException()) as PaperlessException.ServerUnreachable).reason
        )
        assertTrue(
            PaperlessException.from(CleartextNotAllowlistedException("example.com"))
                is PaperlessException.CleartextBlocked
        )
        assertTrue(
            PaperlessException.from(CertificatePinMismatchException("example.com", "a", "b"))
                is PaperlessException.CertificatePinMismatch
        )
    }

    @Test
    fun `a plain IOException is still a network error`() {
        // The counterpart to the test above: removing the catch block must not change
        // the ordinary case. Without this, the test above would pass just as well if
        // every IOException had been rerouted somewhere new.
        assertTrue(PaperlessException.from(IOException("socket closed")) is PaperlessException.NetworkError)
    }

    @Test
    fun `an unclassifiable throwable names its class`() {
        val error = PaperlessException.from(IllegalStateException("boom"))

        assertTrue(error is PaperlessException.UnknownError)
        assertEquals("IllegalStateException", (error as PaperlessException.UnknownError).diagnosticTag)
        assertTrue(error.getLocalizedMessage(context).contains("IllegalStateException"))
    }

    @Test
    fun `an anonymous throwable still produces a tag`() {
        // object : Throwable() has an empty simpleName on the JVM. Without the ifEmpty
        // guard the message would render as "Unknown error ()" — worse than the text
        // this whole change exists to replace.
        val error = PaperlessException.from(object : RuntimeException("anonymous") {}) as PaperlessException.UnknownError

        assertTrue("empty tag leaked into the message", error.diagnosticTag.isNotEmpty())
    }

    @Test
    fun `an already-mapped exception passes through untouched`() {
        val original = PaperlessException.AuthError(401)

        assertTrue(PaperlessException.from(original) === original)
    }

    // ------------------------------------------------------------------ isRetryable

    @Test
    fun `retry behaviour follows the type, and two cases deliberately changed`() {
        // Unchanged: these were retryable as NetworkError and stay retryable as
        // ServerUnreachable, so removing the catch block did not alter backoff.
        assertTrue(PaperlessException.from(UnknownHostException()).isRetryable)
        assertTrue(PaperlessException.from(SocketTimeoutException()).isRetryable)
        assertTrue(PaperlessException.from(IOException()).isRetryable)

        // NOT changed by this diff — pinned so it cannot be undone by accident.
        //
        // An earlier version of this comment claimed both had just stopped being
        // retryable "instead of burning the full backoff ladder". That was wrong twice,
        // and DocumentRepository.kt says so at the deletion site: from() has carried both
        // branches since issues #233 and #36, and withRetry sees the RAW throwable, so
        // isRetryable never governed the download path at all. For cleartext the ladder
        // is still burned today (NetworkRetry.kt:43 retries every IOException).
        //
        // What this test is actually for: re-introducing a catch(IOException) ahead of
        // from() would flatten both to NetworkError, which IS retryable — so these two
        // assertions go red on exactly that regression.
        assertFalse(
            PaperlessException.from(CertificatePinMismatchException("example.com", "a", "b")).isRetryable
        )
        assertFalse(
            PaperlessException.from(CleartextNotAllowlistedException("example.com")).isRetryable
        )
    }
}
