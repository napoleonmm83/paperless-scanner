package com.paperless.scanner.data.analytics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.util.DiagnosticsLog
import com.paperless.scanner.util.LogSanitizer
import com.paperless.scanner.util.SharedFileCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects anonymized failure reports — a login attempt, a document download, a render
 * failure — and turns them into something a user can send us.
 *
 * **What it does:**
 * - Keeps the last failure in memory, always, whatever the analytics setting says
 * - Forwards it to Crashlytics ONLY with consent, because that one is automatic
 * - Builds a shareable report the user can mail or copy
 *
 * **Privacy.** The consent line above is the important one and it used to be wrong here:
 * this class advertised "only if analytics enabled" while the report itself is what a
 * user needs in order to TELL us something. Nothing leaves the device without their
 * action; the gate governs automatic transmission.
 * - Server URLs are hashed, and the host is redacted by value from the finished text
 *   (see [withoutKnownHost]) — hashing alone missed every place the host arrives as part
 *   of an exception message or a log line
 * - No usernames, passwords or tokens are stored
 * - Response bodies are sanitized, and only safe headers are kept
 */
@Singleton
class DiagnosticsReportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val analyticsService: AnalyticsService,
    private val crashlyticsHelper: CrashlyticsHelper,
    // Only ever read, and only for the server URL: the report promises that the address
    // appears as a checksum and nowhere in readable form, and keeping that promise means
    // knowing the value so it can be removed. See [withoutKnownHost].
    private val tokenManager: TokenManager
) {
    companion object {
        private const val TAG = "DiagnosticsReportService"
    }

    // Last report for potential manual send
    private val _lastReport = MutableStateFlow<DiagnosticReport?>(null)
    val lastReport: StateFlow<DiagnosticReport?> = _lastReport.asStateFlow()

    /**
     * Create and log a debug report for a failed auth attempt.
     *
     * @param authType Type of authentication attempted
     * @param serverUrl Server URL (will be hashed)
     * @param httpStatusCode HTTP status code if available
     * @param errorType Exception class name or error category
     * @param errorMessage User-facing or technical error message
     * @param response OkHttp response for header extraction (optional)
     * @param serverDetection Server detection results (optional)
     */
    fun logFailure(
        authType: DiagnosticReport.Source,
        serverUrl: String?,
        httpStatusCode: Int? = null,
        errorType: String? = null,
        errorMessage: String? = null,
        response: Response? = null,
        responseBody: String? = null,
        serverDetection: DiagnosticReport.ServerDetectionInfo? = null
    ) {
        val report = DiagnosticReport(
            authType = authType,
            serverUrlHash = DiagnosticReport.hashServerUrl(serverUrl),
            httpStatusCode = httpStatusCode,
            errorType = errorType,
            errorMessage = errorMessage,
            responseHeaders = response?.let {
                DiagnosticReport.extractSafeHeaders(it.headers.toMultimap())
            } ?: emptyMap(),
            responseBodyPreview = DiagnosticReport.sanitizeResponseBody(responseBody),
            networkInfo = getNetworkInfo(),
            serverDetection = serverDetection
        )

        // Always kept in memory. This method used to begin with
        // `if (!isAnalyticsEnabled()) return`, which discarded the report entirely —
        // so for a user who declined analytics the button that offers to send us a
        // report was never even visible, because lastReport stayed null. Those are
        // precisely the users we otherwise hear nothing from, and one of them is why
        // this work exists.
        //
        // Nothing here transmits. The report sits in memory until the user opens it,
        // reads it and chooses to send it; that is consent by action, and the analytics
        // gate governs AUTOMATIC transmission, which is the next line.
        _lastReport.value = report

        if (analyticsService.isAnalyticsEnabled()) {
            // This one IS automatic, so it stays behind the gate.
            logToCrashlytics(report)
        }

        Log.d(TAG, "Diagnostic report created: ${report.reportId}")
    }

    /**
     * Removes the user's own server address from a finished report.
     *
     * The line sanitizer redacts by shape and cannot tell `paperless.example.com` from
     * `java.net.UnknownHostException`, so a host without a scheme in front of it walked
     * straight through — into the log tail (the cleartext-allowlist line fires on every
     * request), and into the structured part too, where `errorMessage` and the
     * protocol-detection results carry raw exception text. Redacting the KNOWN value
     * needs no guessing.
     *
     * Blocking (reads DataStore); every caller is already on a background dispatcher.
     * A failure here must never cost the report, but it must also never silently ship an
     * unredacted one — so the throw is recorded rather than swallowed, and the text is
     * returned as-is only because a report with the host in it is still better than no
     * report at all when the user has explicitly asked to send one.
     */
    private fun withoutKnownHost(text: String): String {
        val serverUrl = try {
            tokenManager.getServerUrlSync()
        } catch (e: Exception) {
            crashlyticsHelper.recordException(e)
            null
        }
        return LogSanitizer.redactKnownHost(text, serverUrl)
    }

    /** A shareable report string for manual sharing (mail, clipboard, a GitHub issue). */
    fun createShareableReport(): String = withoutKnownHost(buildShareableReport())

    private fun buildShareableReport(): String {
        val report = _lastReport.value ?: return "No debug report available."

        return buildString {
            appendLine("## Paperless Scanner diagnostic report")
            appendLine("Report ID: `${report.reportId}`")
            appendLine("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(report.timestamp))}")
            appendLine()
            // "Failure", not "Auth Attempt": the same report now covers a failed document
            // download and a render failure, and a heading that names the wrong cause is
            // exactly the class of defect this whole change set exists to remove.
            appendLine("### Failure")
            appendLine("- Source: ${report.authType}")
            appendLine("- Server Hash: `${report.serverUrlHash}`")
            appendLine("- HTTP Status: ${report.httpStatusCode ?: "N/A"}")
            appendLine("- Error Type: ${report.errorType ?: "N/A"}")
            appendLine("- Error Message: ${report.errorMessage ?: "N/A"}")
            appendLine()
            appendLine("### Device Info")
            appendLine("- Android: ${report.deviceInfo.androidRelease} (API ${report.deviceInfo.androidVersion})")
            appendLine("- Device: ${report.deviceInfo.manufacturer} ${report.deviceInfo.model}")
            appendLine()
            appendLine("### Network")
            appendLine("- Type: ${report.networkInfo.networkType}")
            appendLine("- VPN Active: ${report.networkInfo.isVpnActive}")
            appendLine("- Has Internet: ${report.networkInfo.hasInternet}")

            report.serverDetection?.let { sd ->
                appendLine()
                appendLine("### Server Detection")
                appendLine("- HTTPS Attempted: ${sd.httpsAttempted}")
                sd.httpsResult?.let { appendLine("- HTTPS Result: $it") }
                appendLine("- HTTP Attempted: ${sd.httpAttempted}")
                sd.httpResult?.let { appendLine("- HTTP Result: $it") }
                appendLine("- Cloudflare Detected: ${sd.isCloudflare}")
                sd.cfRayHeader?.let { appendLine("- CF-Ray: `$it`") }
            }

            if (report.responseHeaders.isNotEmpty()) {
                appendLine()
                appendLine("### Response Headers")
                report.responseHeaders.forEach { (key, value) ->
                    appendLine("- $key: $value")
                }
            }

            report.responseBodyPreview?.let {
                appendLine()
                appendLine("### Response Body Preview")
                appendLine("```")
                appendLine(it)
                appendLine("```")
            }
        }
    }

    /**
     * The full report a user can mail us: the structured part above plus recent log
     * lines.
     *
     * Two sources, and the code does NOT merge them — logcat wins when it has anything
     * at all, the ring buffer steps in only when logcat comes back empty. Interleaving
     * the two would need a shared clock they do not have. Neither is load-bearing alone:
     * the ring buffer is the one
     * we control and it always exists; logcat is where the roughly 577 direct
     * `android.util.Log` calls live — the repository and network paths among them — and
     * it may be empty on a device that refuses it. Both are sanitized: the buffer on the
     * way in, logcat here, by the same function.
     *
     * Blocking (logcat spawns a process). Call from a background dispatcher.
     */
    fun createFullReport(): String = withoutKnownHost(buildFullReport())

    private fun buildFullReport(): String = buildString {
        appendLine(buildShareableReport())
        appendLine()
        appendLine("### Recent log")

        val buffered = DiagnosticsLog.snapshot()
        val logcat = DiagnosticsLog.readLogcatTail()

        // Stated rather than hidden: a reader needs to know whether an empty section
        // means "nothing happened" or "this device would not tell us".
        appendLine("_buffer: ${buffered.size} lines · logcat: ${logcat.size} lines_")
        appendLine()
        appendLine("```")
        (logcat.ifEmpty { buffered }).forEach { appendLine(it) }
        appendLine("```")
    }

    /**
     * Writes [createFullReport] into the FileProvider-scoped reports directory and
     * returns the file, or null if it could not be written.
     *
     * A file rather than only text in the mail body because a mail client will silently
     * truncate a long body, and the log is the part that gets truncated.
     */
    fun writeReportFile(cacheDir: java.io.File): java.io.File? = try {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        java.io.File(SharedFileCache.sharedReportsDir(cacheDir), "paperless-report-$stamp.txt")
            .apply { writeText(createFullReport()) }
    } catch (e: Exception) {
        crashlyticsHelper.recordException(e)
        null
    }

    /**
     * Log detailed report to Crashlytics for non-fatal tracking.
     */
    private fun logToCrashlytics(report: DiagnosticReport) {
        val details = buildString {
            append("auth=${report.authType}")
            append(", http=${report.httpStatusCode ?: "N/A"}")
            append(", error=${report.errorType ?: "N/A"}")
            append(", net=${report.networkInfo.networkType}")
            append(", android=${report.deviceInfo.androidVersion}")
            report.serverDetection?.let {
                if (it.isCloudflare) append(", cloudflare=true")
            }
        }

        crashlyticsHelper.logStateBreadcrumb("DIAGNOSTIC_REPORT", details)

        // Set custom keys for this failure.
        //
        // Guarded because this reaches Firebase's static singleton directly rather than
        // through CrashlyticsHelper, and FirebaseInitProvider does not run under
        // Robolectric — so `Firebase.crashlytics` throws IllegalStateException there and
        // took every test of this class down with it, including the one proving that
        // forwarding still happens when consent IS granted. The project already carries
        // this exact pattern for RemoteConfigManager
        // (gotcha_robolectric_eager_firebase_inject).
        //
        // Swallowed on purpose and only here: failing to attach a custom key must never
        // cost the breadcrumb above or the in-memory report, which are the parts a user
        // can actually send us.
        try {
            Firebase.crashlytics.apply {
                setCustomKey("last_report_source", report.authType.name)
                setCustomKey("last_report_http_code", report.httpStatusCode ?: -1)
                setCustomKey("last_report_error", report.errorType ?: "none")
                setCustomKey("last_report_network", report.networkInfo.networkType)
                report.serverDetection?.let {
                    setCustomKey("last_report_cloudflare", it.isCloudflare)
                }
            }
        } catch (e: IllegalStateException) {
            // Firebase not initialised — under test, or on a device where init failed.
        }
    }

    /**
     * Get current network info.
     */
    private fun getNetworkInfo(): DiagnosticReport.NetworkInfo {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return DiagnosticReport.NetworkInfo()

        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        val networkType = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }

        val isVpnActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        return DiagnosticReport.NetworkInfo(
            networkType = networkType,
            isVpnActive = isVpnActive,
            hasInternet = hasInternet
        )
    }

    /**
     * Clear the last report.
     */
    fun clearLastReport() {
        _lastReport.value = null
    }
}
