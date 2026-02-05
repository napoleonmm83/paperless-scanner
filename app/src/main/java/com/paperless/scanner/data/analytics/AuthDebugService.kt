package com.paperless.scanner.data.analytics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for collecting and sending anonymized authentication debug reports.
 *
 * **Features:**
 * - Collects detailed auth failure info (anonymized)
 * - Logs to Crashlytics for immediate visibility
 * - Creates shareable reports for GitHub issues
 * - Respects user consent (only if analytics enabled)
 *
 * **Privacy:**
 * - All server URLs are hashed
 * - No usernames, passwords, or tokens stored
 * - Response bodies are sanitized
 * - Only safe headers are logged
 */
@Singleton
class AuthDebugService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val analyticsService: AnalyticsService,
    private val crashlyticsHelper: CrashlyticsHelper
) {
    companion object {
        private const val TAG = "AuthDebugService"
    }

    // Last report for potential manual send
    private val _lastReport = MutableStateFlow<AuthDebugReport?>(null)
    val lastReport: StateFlow<AuthDebugReport?> = _lastReport.asStateFlow()

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
    fun logAuthFailure(
        authType: AuthDebugReport.AuthType,
        serverUrl: String?,
        httpStatusCode: Int? = null,
        errorType: String? = null,
        errorMessage: String? = null,
        response: Response? = null,
        responseBody: String? = null,
        serverDetection: AuthDebugReport.ServerDetectionInfo? = null
    ) {
        if (!analyticsService.isAnalyticsEnabled()) {
            Log.d(TAG, "Auth debug logging skipped (analytics disabled)")
            return
        }

        val report = AuthDebugReport(
            authType = authType,
            serverUrlHash = AuthDebugReport.hashServerUrl(serverUrl),
            httpStatusCode = httpStatusCode,
            errorType = errorType,
            errorMessage = errorMessage,
            responseHeaders = response?.let {
                AuthDebugReport.extractSafeHeaders(it.headers.toMultimap())
            } ?: emptyMap(),
            responseBodyPreview = AuthDebugReport.sanitizeResponseBody(responseBody),
            networkInfo = getNetworkInfo(),
            serverDetection = serverDetection
        )

        _lastReport.value = report

        // Log detailed info to Crashlytics
        logToCrashlytics(report)

        Log.d(TAG, "Auth debug report created: ${report.reportId}")
    }

    /**
     * Create a shareable debug report string for manual sharing (e.g., GitHub issue).
     */
    fun createShareableReport(): String {
        val report = _lastReport.value ?: return "No debug report available."

        return buildString {
            appendLine("## Auth Debug Report")
            appendLine("Report ID: `${report.reportId}`")
            appendLine("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(report.timestamp))}")
            appendLine()
            appendLine("### Auth Attempt")
            appendLine("- Type: ${report.authType}")
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
     * Log detailed report to Crashlytics for non-fatal tracking.
     */
    private fun logToCrashlytics(report: AuthDebugReport) {
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

        crashlyticsHelper.logStateBreadcrumb("AUTH_DEBUG", details)

        // Set custom keys for this failure
        Firebase.crashlytics.apply {
            setCustomKey("last_auth_type", report.authType.name)
            setCustomKey("last_auth_http_code", report.httpStatusCode ?: -1)
            setCustomKey("last_auth_error", report.errorType ?: "none")
            setCustomKey("last_auth_network", report.networkInfo.networkType)
            report.serverDetection?.let {
                setCustomKey("last_auth_cloudflare", it.isCloudflare)
            }
        }
    }

    /**
     * Get current network info.
     */
    private fun getNetworkInfo(): AuthDebugReport.NetworkInfo {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return AuthDebugReport.NetworkInfo()

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

        return AuthDebugReport.NetworkInfo(
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
