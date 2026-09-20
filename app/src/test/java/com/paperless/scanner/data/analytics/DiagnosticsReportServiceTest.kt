package com.paperless.scanner.data.analytics

import android.content.Context
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.every
import kotlinx.coroutines.flow.flowOf
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pin for the one decision that makes the diagnostic report reach the people it is for.
 *
 * This service used to begin `logFailure` with
 * `if (!analyticsService.isAnalyticsEnabled()) return`, which discarded the report
 * entirely — so for a user who declined analytics `lastReport` stayed null and the button
 * offering to send us a report was never even visible. Those are precisely the users the
 * app otherwise learns nothing from, and one of them is why this exists.
 *
 * The distinction being pinned: keeping a report IN MEMORY transmits nothing, and the
 * consent gate governs automatic transmission. So the report is always built, and only
 * the Crashlytics forwarding stays behind the gate.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsReportServiceTest {

    private lateinit var context: Context
    private lateinit var analyticsService: AnalyticsService
    private lateinit var crashlyticsHelper: CrashlyticsHelper
    private lateinit var tokenManager: TokenManager
    private lateinit var service: DiagnosticsReportService

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        analyticsService = mockk(relaxed = true)
        crashlyticsHelper = mockk(relaxed = true)
        // NOTHING is stored. That is the production state during setup — the server URL is
        // written only after a successful login — and the previous version of this test
        // handed back the attempted host here, which is why it passed over a real leak.
        tokenManager = mockk(relaxed = true)
        every { tokenManager.serverUrl } returns flowOf(null)
        every { tokenManager.paperlessGptUrl } returns flowOf(null)
        every { tokenManager.acceptedHttpHostsFlow } returns flowOf(emptyList())
        service = DiagnosticsReportService(context, analyticsService, crashlyticsHelper, tokenManager)
    }

    private fun logOnce(errorMessage: String = "HTTP 406") = service.logFailure(
        authType = DiagnosticReport.Source.DOCUMENT_DOWNLOAD,
        serverUrl = SERVER_URL,
        httpStatusCode = 406,
        errorType = "ContentError",
        errorMessage = errorMessage
    )

    @Test
    fun `a report is created even when the user declined analytics`() {
        every { analyticsService.isAnalyticsEnabled() } returns false

        logOnce()

        assertNotNull(
            "the report was discarded, so the send button would never appear",
            service.lastReport.value
        )
    }

    @Test
    fun `declining analytics still stops the automatic forwarding`() {
        // The other half, and the reason the gate was not simply deleted: keeping the
        // report in memory transmits nothing, but Crashlytics does.
        every { analyticsService.isAnalyticsEnabled() } returns false

        logOnce()

        verify(exactly = 0) { crashlyticsHelper.logStateBreadcrumb(any(), any()) }
    }

    @Test
    fun `granting analytics forwards as before`() {
        // The positive control. Without it the two tests above would pass just as well
        // if the forwarding had been removed entirely.
        every { analyticsService.isAnalyticsEnabled() } returns true

        logOnce()

        assertNotNull(service.lastReport.value)
        verify { crashlyticsHelper.logStateBreadcrumb(any(), any()) }
    }

    @Test
    fun `the server address is hashed, never carried in readable form`() {
        every { analyticsService.isAnalyticsEnabled() } returns false

        logOnce()
        val text = service.createShareableReport()

        assertFalse("the host reached the report", text.contains(HOST))
        assertTrue("the hash is missing, so the report cannot group by server", text.contains("Server Hash"))
    }

    @Test
    fun `an exception message carrying the host is redacted with NOTHING stored yet`() {
        // The load-bearing case, and the one two earlier versions of this file got wrong.
        //
        // The first fed the host only through `serverUrl`, which the report hashes by
        // construction, so it proved nothing about `errorMessage` — which IS printed
        // verbatim and which AuthRepository fills with raw `e.message`, naming the host on
        // any DNS or connect failure. The second fixed that but mocked the STORED url to
        // return the same host, so it passed while production leaked: the url is written
        // only after a successful login, and this report's original purpose is the login
        // that did NOT succeed.
        //
        // Here nothing is stored (see setup). The only thing the service can know is the
        // host of the attempt it was just told about.
        every { analyticsService.isAnalyticsEnabled() } returns false

        logOnce(errorMessage = """java.net.UnknownHostException: Unable to resolve host "$HOST"""")
        val text = service.createShareableReport()

        assertFalse("the host reached the report through errorMessage", text.contains(HOST))
        assertTrue("the diagnosis was thrown away with the host", text.contains("UnknownHostException"))
    }

    @Test
    fun `a resolved IP in an exception message is redacted in the structured part too`() {
        // The structured half used to get no shape rules at all: the same connect failure
        // was reduced to <ip> in the log tail while the server's public address stood two
        // sections above it, in "Error Message".
        every { analyticsService.isAnalyticsEnabled() } returns false

        logOnce(errorMessage = "Failed to connect to $HOST/93.184.216.34:443")
        val text = service.createShareableReport()

        assertFalse("the resolved address reached the report", text.contains("93.184.216.34"))
        assertTrue("the failure itself was lost", text.contains("Failed to connect"))
    }

    @Test
    fun `the shareable report says so when nothing has happened yet`() {
        // Not a formality: the settings entry reads lastReport to decide visibility, and
        // an empty report that looks like a real one would waste a user's mail.
        assertNull(service.lastReport.value)
        assertTrue(service.createShareableReport().contains("No debug report"))
    }

    @Test
    fun `the report carries the source so download and login are distinguishable`() {
        every { analyticsService.isAnalyticsEnabled() } returns false

        logOnce()

        assertEquals(
            DiagnosticReport.Source.DOCUMENT_DOWNLOAD,
            service.lastReport.value?.authType
        )
    }

    private companion object {
        const val HOST = "paperless.private.example"
        const val SERVER_URL = "https://$HOST"
    }
}
