package com.paperless.scanner.data.analytics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.paperless.scanner.BuildConfig
import com.paperless.scanner.data.datastore.ServerUrlHolder
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.util.DiagnosticReportSender
import com.paperless.scanner.util.DiagnosticsLog
import com.paperless.scanner.util.LogSanitizer
import com.paperless.scanner.util.SharedFileCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton
import com.paperless.scanner.util.AppLogger

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
    // The contract, not the concrete class: this service uses only logStateBreadcrumb and
    // recordException, both of which it declares — so the test can use the project's
    // FakeCrashlyticsHelper and assert the breadcrumb it RECORDED instead of verifying
    // that a method was called. A verify passes against a method that does nothing.
    private val crashlyticsHelper: CrashlyticsHelperContract,
    // Only ever read, and only for the server URL: the report promises that the address
    // appears as a checksum and nowhere in readable form, and keeping that promise means
    // knowing the value so it can be removed. See [withoutKnownHost].
    private val tokenManager: TokenManager,
    // The URL the current request would use, as a single non-blocking atomic read. Same
    // source DynamicBaseUrlInterceptor uses, so it is the URL that was actually tried.
    private val serverUrlHolder: ServerUrlHolder
) {
    companion object {
        private const val TAG = "DiagnosticsReportService"
    }

    // Last report for potential manual send
    private val _lastReport = MutableStateFlow<DiagnosticReport?>(null)
    val lastReport: StateFlow<DiagnosticReport?> = _lastReport.asStateFlow()

    /**
     * Every host that must never appear in the report in readable form.
     *
     * Held in memory and fed from two directions, because either one alone leaks:
     *
     * - **Observed** from the stored settings. A `getServerUrlSync()` here was the first
     *   attempt and it was wrong twice over: it blocks on DataStore, and two of the three
     *   callers reach this class from a Compose click lambda on the main thread — the
     *   report used to do no IO at all, so that was a regression, not an inherited cost.
     * - **Recorded at the attempt**, from the `serverUrl` that [logFailure] already
     *   receives. The stored URL is written only AFTER a successful login
     *   (`AuthRepository.saveCredentials`), so during setup — a new install, a new server,
     *   a failed login — there is nothing stored to redact against. That is precisely the
     *   path this report was built for, and the version before this one handed the
     *   attempted hostname out in the clear while promising it did not.
     *
     * A set rather than one value because a user can have several: the Paperless server,
     * a Paperless-GPT instance, and whatever sits in the cleartext allowlist.
     */
    @Volatile
    private var knownHosts: Set<String> = emptySet()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Collected rather than read on demand: a report is often built from the main
        // thread, and a settings read must not happen there.
        //
        // Best-effort by design, and the `catch` says so: these keep the host set warm
        // while the app runs, but they are NOT what the privacy promise rests on — that
        // is [hostsBereitstellen], which reads once before a report and fails closed. A
        // throwing flow here used to kill its collector outright and take the ongoing
        // tracking with it, silently, for the rest of the process.
        scope.launch { tokenManager.serverUrl.catch { }.collect { rememberHost(it) } }
        scope.launch { tokenManager.paperlessGptUrl.catch { }.collect { rememberHost(it) } }
        scope.launch {
            tokenManager.acceptedHttpHostsFlow.catch { }.collect { it.forEach(::rememberHost) }
        }
    }

    /**
     * Reads the stored hosts ONCE and waits for them, before a report is built.
     *
     * The collectors in [init] run on their own IO scope, so a report requested right
     * after start can be built before any of them has emitted — [knownHosts] is then
     * empty and a host that appears in a log line survives into the report, which the
     * report's own privacy statement says it will not. A failure-triggered report does
     * not have that hole (it is handed the attempted URL), the MANUALLY requested one
     * does, and that is the path this entry point exists for.
     *
     * Suspending rather than blocking: the values come from DataStore, and this runs on
     * the IO dispatcher of a caller that is already suspending.
     *
     * **It THROWS, and that is the point.** The first version wrapped each read in a
     * `runCatching` and carried on — which turns a failed read into a report built with
     * incomplete redaction inputs, i.e. exactly the leak this function exists to
     * prevent, minus the error message. `redactKnownHosts` cannot remove a host it was
     * never told about. A privacy promise fails CLOSED: no hosts, no report.
     */
    private suspend fun hostsBereitstellen() {
        rememberHost(serverUrlHolder.current())
        rememberHost(tokenManager.serverUrl.first())
        rememberHost(tokenManager.paperlessGptUrl.first())
        tokenManager.acceptedHttpHostsFlow.first().forEach(::rememberHost)
    }

    @Synchronized
    private fun rememberHost(url: String?) {
        if (url.isNullOrBlank()) return
        knownHosts = knownHosts + url
    }

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
        // Callers that cannot name a URL — the PDF viewer, which does not hold one — used
        // to pass null, and hashServerUrl(null) is the literal "none". Every report from
        // every server then carried the same grouping value, which is the same defect as
        // the undifferentiated "Unknown error" this work started from, one layer down.
        // The holder is an atomic read, so this costs nothing and blocks nothing.
        val effectiveServerUrl = serverUrl ?: serverUrlHolder.current()

        // Before anything is built: the attempted host is the only one that exists during
        // setup, and the report about to be created is full of it.
        rememberHost(effectiveServerUrl)

        val report = DiagnosticReport(
            authType = authType,
            serverUrlHash = DiagnosticReport.hashServerUrl(effectiveServerUrl),
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

        AppLogger.d(TAG, "Diagnostic report created: ${report.reportId}")
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
     * Two passes, and both are needed. [LogSanitizer.sanitizeText] applies the shape rules
     * — credentials, sensitive JSON fields, URL authorities, IPv4 — which the STRUCTURED
     * half of the report never saw: `errorMessage` and the protocol-detection results are
     * printed verbatim and are filled with raw `e.message`, so the same connect failure
     * was reduced to `<ip>` in the log tail while the server's public address stood two
     * sections above it. [LogSanitizer.redactKnownHosts] then removes what no shape rule
     * can find, by value.
     *
     * Non-blocking: [knownHosts] is kept in memory, so this is safe from the main thread.
     */
    private fun withoutKnownHost(text: String): String =
        LogSanitizer.redactKnownHosts(LogSanitizer.sanitizeText(text), knownHosts)

    /** A shareable report string for manual sharing (mail, clipboard, a GitHub issue). */
    fun createShareableReport(): String = withoutKnownHost(buildShareableReport())

    /**
     * The full report for a MANUALLY requested copy — host redaction seeded first.
     *
     * Same reason as [sendFullReport]: without [hostsBereitstellen] a report built right
     * after start can carry a host the redaction did not know about yet.
     */
    suspend fun createFullReportForSharing(): String? = try {
        hostsBereitstellen()
        createFullReport()
    } catch (e: CancellationException) {
        // Never swallowed: a cancelled job must not look like a failed one, and the
        // caller's scope is entitled to end here.
        throw e
    } catch (e: Exception) {
        // null, not a report: the seeding failed, so the redaction inputs are
        // incomplete and the text could carry a bare host. The caller shows the
        // could-not-be-copied message instead.
        crashlyticsHelper.recordException(e)
        null
    }

    private fun formatTime(epochMillis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(epochMillis))

    /**
     * The report head is built unconditionally; the failure section only when there IS a
     * failure.
     *
     * This method used to open with `?: return "No debug report available."`, and that
     * single line quietly defeated the one entry point a user reaches on purpose: a
     * report requested from Settings, with nothing broken yet, carried no app version, no
     * device and no network state — the reader could not even tell which build wrote it.
     * The version was in the mail SUBJECT only, so the clipboard path carried it nowhere.
     *
     * Device and network are therefore taken from the recorded failure when one exists —
     * they describe the moment that matters — and measured fresh otherwise.
     */
    private fun buildShareableReport(): String {
        val report = _lastReport.value
        val deviceInfo = report?.deviceInfo ?: DiagnosticReport.DeviceInfo()
        val networkInfo = report?.networkInfo ?: getNetworkInfo()

        return buildString {
            appendLine("## Paperless Scanner diagnostic report")
            appendLine("- App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("- Report ID: `${report?.reportId ?: "none"}`")
            // TWO timestamps, and the distinction is load-bearing: the recorded failure
            // survives until the process dies, so a report requested from Settings hours
            // later still describes it. A single "Timestamp:" then showed the FAILURE's
            // time on a mail whose subject says it was requested by hand — two different
            // moments under one label.
            appendLine("- Requested at: ${formatTime(System.currentTimeMillis())}")
            report?.let { appendLine("- Failure at: ${formatTime(it.timestamp)}") }
            appendLine()
            // "Failure", not "Auth Attempt": the same report now covers a failed document
            // download and a render failure, and a heading that names the wrong cause is
            // exactly the class of defect this whole change set exists to remove.
            appendLine("### Failure")
            if (report == null) {
                // Stated rather than omitted: an absent section reads like a bug in the
                // report, while this line tells the reader the log below is the payload.
                appendLine("- No failure recorded — this report was requested manually.")
            } else {
                appendLine("- Source: ${report.authType}")
                appendLine("- Server Hash: `${report.serverUrlHash}`")
                appendLine("- HTTP Status: ${report.httpStatusCode ?: "N/A"}")
                appendLine("- Error Type: ${report.errorType ?: "N/A"}")
                appendLine("- Error Message: ${report.errorMessage ?: "N/A"}")
            }
            appendLine()
            appendLine("### Device Info")
            appendLine("- Android: ${deviceInfo.androidRelease} (API ${deviceInfo.androidVersion})")
            appendLine("- Device: ${deviceInfo.manufacturer} ${deviceInfo.model}")
            appendLine()
            appendLine("### Network")
            appendLine("- Type: ${networkInfo.networkType}")
            appendLine("- VPN Active: ${networkInfo.isVpnActive}")
            appendLine("- Has Internet: ${networkInfo.hasInternet}")

            report?.serverDetection?.let { sd ->
                appendLine()
                appendLine("### Server Detection")
                appendLine("- HTTPS Attempted: ${sd.httpsAttempted}")
                sd.httpsResult?.let { appendLine("- HTTPS Result: $it") }
                appendLine("- HTTP Attempted: ${sd.httpAttempted}")
                sd.httpResult?.let { appendLine("- HTTP Result: $it") }
                appendLine("- Cloudflare Detected: ${sd.isCloudflare}")
                sd.cfRayHeader?.let { appendLine("- CF-Ray: `$it`") }
            }

            if (report != null && report.responseHeaders.isNotEmpty()) {
                appendLine()
                appendLine("### Response Headers")
                report.responseHeaders.forEach { (key, value) ->
                    appendLine("- $key: $value")
                }
            }

            report?.responseBodyPreview?.let {
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

        // BOTH, in their own sections. Until now logcat won whole and the ring buffer
        // was dropped whenever logcat produced anything at all — which threw away the
        // lines this app writes through AppLogger with its own tags, the ones we
        // actually chose to record. They are not interleaved because they have no
        // shared clock; they are labelled instead, so a reader knows which is which.
        if (logcat.isNotEmpty()) {
            appendLine()
            appendLine("#### logcat")
            appendLine("```")
            logcat.forEach { appendLine(it) }
            appendLine("```")
        }

        if (buffered.isNotEmpty()) {
            appendLine()
            appendLine("#### app log buffer")
            appendLine("```")
            buffered.forEach { appendLine(it) }
            appendLine("```")
        }

        if (logcat.isEmpty() && buffered.isEmpty()) {
            appendLine()
            appendLine("_no log lines available on this device_")
        }
    }

    /**
     * Writes [createFullReport] into the FileProvider-scoped reports directory and
     * returns the file, or null if it could not be written.
     *
     * A file rather than only text in the mail body because a mail client will silently
     * truncate a long body, and the log is the part that gets truncated.
     */
    fun writeReportFile(cacheDir: java.io.File, text: String = createFullReport()): java.io.File? = try {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        java.io.File(SharedFileCache.sharedReportsDir(cacheDir), "paperless-report-$stamp.txt")
            // The caller's text, not a second build: [createFullReport] spawns logcat, so
            // building it twice for one tap produced two processes AND two different
            // reports — the mailed attachment and the clipboard copy disagreed about the
            // lines written between them, while carrying the same report id.
            .apply { writeText(text) }
    } catch (e: Exception) {
        crashlyticsHelper.recordException(e)
        null
    }

    /**
     * Builds the full report and hands it to the mail path — the whole journey, guarded.
     *
     * Blocking; call from a background dispatcher.
     *
     * **Why this lives here and not in each ViewModel.** Both callers used to inline the
     * same three steps with no error handling at all, and every step can throw at the
     * user: [createFullReport] runs two regex passes over a logcat tail,
     * `FileProvider.getUriForFile` catches only `IllegalArgumentException`, and
     * `startActivity` catches only `ActivityNotFoundException` — a `SecurityException`
     * from a mail app that cannot be granted the attachment goes straight through. In a
     * `viewModelScope` that reaches the default handler and KILLS THE PROCESS: the one
     * button whose job is to report a failure becomes the failure.
     *
     * A throw is therefore turned into [DiagnosticReportSender.Result.NO_TARGET], which
     * the screens already translate into an honest "could not be sent" message, and the
     * cause is recorded so we learn the path exists at all.
     */
    suspend fun sendFullReport(cacheDir: java.io.File, subjectTag: String): DiagnosticReportSender.Result =
        try {
            hostsBereitstellen()
            val text = createFullReport()
            DiagnosticReportSender.send(
                context = context,
                reportFile = writeReportFile(cacheDir, text),
                reportText = text,
                subjectTag = subjectTag
            )
        } catch (e: CancellationException) {
            // BEFORE the Exception branch — CancellationException IS an Exception, and
            // recording a cancelled job as a send failure both pollutes the telemetry
            // and breaks the caller's structured concurrency.
            throw e
        } catch (e: Exception) {
            crashlyticsHelper.recordException(e)
            DiagnosticReportSender.Result.NO_TARGET
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
    /**
     * Guarded, because the report is the WRONG place to die.
     *
     * `getNetworkCapabilities` and `activeNetwork` throw `SecurityException` on some OEM
     * builds. Until this change that only ran while a failure was being recorded; the
     * manual report now calls it from the clipboard path too, and an escaping throw
     * there reaches the default handler and kills the app while it copies an error
     * report. The fallback says `unavailable` rather than a plausible-looking default —
     * a fabricated "has internet: true" is indistinguishable from a real measurement.
     */
    private fun getNetworkInfo(): DiagnosticReport.NetworkInfo = try {
        messeNetz()
    } catch (e: RuntimeException) {
        crashlyticsHelper.recordException(e)
        DiagnosticReport.NetworkInfo(networkType = "unavailable")
    }

    private fun messeNetz(): DiagnosticReport.NetworkInfo {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return DiagnosticReport.NetworkInfo(networkType = "unavailable")

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
