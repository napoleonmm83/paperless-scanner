package com.paperless.scanner.data.analytics

import android.os.Build
import com.paperless.scanner.util.LogSanitizer
import java.security.MessageDigest
import java.util.Date
import java.util.UUID

/**
 * Anonymized debug report for authentication issues.
 *
 * **Privacy Guarantees:**
 * - Server URL is hashed (SHA-256, first 16 chars)
 * - No usernames, passwords, or tokens stored
 * - No PII collected
 * - Report ID is random UUID (not linked to user)
 *
 * **Usage:**
 * Created when auth fails, optionally sent to Firestore for analysis.
 */
data class DiagnosticReport(
    val reportId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),

    // Auth attempt info (anonymized)
    val authType: Source,
    val serverUrlHash: String,
    val httpStatusCode: Int?,
    val errorType: String?,
    val errorMessage: String?,

    // Response details (anonymized)
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBodyPreview: String? = null, // First 200 chars, sanitized

    // Device info
    val deviceInfo: DeviceInfo = DeviceInfo(),

    // Network info
    val networkInfo: NetworkInfo = NetworkInfo(),

    // Server detection results
    val serverDetection: ServerDetectionInfo? = null
) {
    enum class Source {
        PASSWORD_LOGIN,
        TOKEN_LOGIN,
        TOKEN_VALIDATION,
        SERVER_DETECTION,

        // Added when this report stopped being auth-only. The class was called
        // AuthDebugReport and carried an AuthType; keeping those names while it
        // reported PDF download failures would have been a name that lies, and the
        // next reader would have had to discover that by reading the call sites.
        DOCUMENT_DOWNLOAD,
        DOCUMENT_RENDER
    }

    data class DeviceInfo(
        val androidVersion: Int = Build.VERSION.SDK_INT,
        val androidRelease: String = Build.VERSION.RELEASE,
        val manufacturer: String = Build.MANUFACTURER,
        val model: String = Build.MODEL,
        val device: String = Build.DEVICE
    )

    data class NetworkInfo(
        val networkType: String = "unknown", // wifi, mobile, none
        val isVpnActive: Boolean = false,
        val hasInternet: Boolean = true
    )

    data class ServerDetectionInfo(
        val httpsAttempted: Boolean = false,
        val httpsResult: String? = null,
        val httpAttempted: Boolean = false,
        val httpResult: String? = null,
        val detectedProtocol: String? = null,
        val isCloudflare: Boolean = false,
        val cfRayHeader: String? = null
    )

    /**
     * Convert to Map for Firestore storage.
     */
    fun toFirestoreMap(): Map<String, Any?> = mapOf(
        "reportId" to reportId,
        "timestamp" to timestamp,
        "authType" to authType.name,
        "serverUrlHash" to serverUrlHash,
        "httpStatusCode" to httpStatusCode,
        "errorType" to errorType,
        "errorMessage" to errorMessage,
        "responseHeaders" to responseHeaders,
        "responseBodyPreview" to responseBodyPreview,
        "deviceInfo" to mapOf(
            "androidVersion" to deviceInfo.androidVersion,
            "androidRelease" to deviceInfo.androidRelease,
            "manufacturer" to deviceInfo.manufacturer,
            "model" to deviceInfo.model,
            "device" to deviceInfo.device
        ),
        "networkInfo" to mapOf(
            "networkType" to networkInfo.networkType,
            "isVpnActive" to networkInfo.isVpnActive,
            "hasInternet" to networkInfo.hasInternet
        ),
        "serverDetection" to serverDetection?.let {
            mapOf(
                "httpsAttempted" to it.httpsAttempted,
                "httpsResult" to it.httpsResult,
                "httpAttempted" to it.httpAttempted,
                "httpResult" to it.httpResult,
                "detectedProtocol" to it.detectedProtocol,
                "isCloudflare" to it.isCloudflare,
                "cfRayHeader" to it.cfRayHeader
            )
        }
    )

    companion object {
        /**
         * Hash a server URL for privacy.
         */
        fun hashServerUrl(url: String?): String {
            if (url.isNullOrBlank()) return "none"
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hashBytes = digest.digest(url.toByteArray(Charsets.UTF_8))
                hashBytes.joinToString("") { "%02x".format(it) }.take(16)
            } catch (e: Exception) {
                "error"
            }
        }

        /**
         * Sanitize response body - remove potential PII, limit length.
         */
        fun sanitizeResponseBody(body: String?): String? =
            // Delegated rather than duplicated. The two regexes that stood here used the
            // naive value matcher `[^"]*`, which LogSanitizer's own comment describes as a
            // leak: an escaped quote inside the value ends the match early and the rest of
            // the secret runs on into the report. They also knew neither `api_key` nor
            // `authorization` nor `passwd`, so the class doc's "response bodies are
            // sanitized" held for half the list.
            LogSanitizer.sanitizeErrorBody(body)

        /**
         * Extract safe headers for logging (exclude sensitive ones).
         */
        fun extractSafeHeaders(headers: Map<String, List<String>>): Map<String, String> {
            val safeHeaderNames = setOf(
                "content-type", "content-length", "server", "date",
                "cf-ray", "cf-cache-status", "x-frame-options",
                "x-content-type-options", "x-request-id", "x-powered-by"
            )

            return headers
                .filterKeys { it.lowercase() in safeHeaderNames }
                .mapValues { (_, values) -> values.firstOrNull() ?: "" }
        }
    }
}
