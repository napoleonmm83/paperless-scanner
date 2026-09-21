package com.paperless.scanner.util

import android.util.Log
import com.paperless.scanner.BuildConfig

/**
 * Centralized logging utility for Paperless Scanner.
 *
 * Features:
 * - Debug logs only appear in debug builds (BuildConfig.DEBUG guard)
 * - Lazy message evaluation for performance
 * - Consistent tag prefix for easy filtering: "Paperless.{tag}"
 * - Error/Warning logs always active for production debugging
 *
 * Usage:
 * ```kotlin
 * // Simple debug log (only in debug builds)
 * AppLogger.d("SyncManager", "Starting sync...")
 *
 * // Lazy evaluation (expensive operations only executed in debug)
 * AppLogger.d("Repository") { "Loaded ${documents.size} documents" }
 *
 * // Error logging (always active)
 * AppLogger.e("Upload", "Failed to upload", exception)
 *
 * // Warning logging (always active)
 * AppLogger.w("Network", "Connection unstable")
 * ```
 *
 * Filter in logcat: `adb logcat | grep Paperless`
 */
object AppLogger {

    private const val TAG_PREFIX = "Paperless"

    /**
     * Maximum tag length for Android (23 chars).
     * We use 12 chars for prefix + dot, leaving 11 for component name.
     */
    private const val MAX_TAG_LENGTH = 23

    /**
     * Format tag with prefix, respecting Android's max tag length.
     * @suppress Internal API - exposed for inline functions
     */
    @PublishedApi
    internal fun formatTag(tag: String): String {
        val fullTag = "$TAG_PREFIX.$tag"
        return if (fullTag.length > MAX_TAG_LENGTH) {
            fullTag.take(MAX_TAG_LENGTH)
        } else {
            fullTag
        }
    }

    // ==================== Debug Logging ====================

    /**
     * Debug log - only visible in debug builds.
     *
     * @param tag Component name (e.g., "SyncManager", "UploadWorker")
     * @param message Log message
     */
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(formatTag(tag), message)
        }
        // Buffered in EVERY build, unlike the logcat echo above.
        //
        // Measured on a device 2026-09-21: under app identity logcat returns ZERO lines
        // — three runs, with --uid, with --pid and unfiltered, while the privileged
        // shell saw 222-300 lines for the same uid in the same moment. The ring buffer
        // is therefore the ONLY log a diagnostic report can ever contain, and a debug
        // line that reaches neither is simply gone. That is what made the report's
        // history look so short: it saw 56 of 692 logging call sites.
        DiagnosticsLog.append(tag, message)
    }

    /**
     * Debug log with lazy message evaluation.
     * Use when message construction is expensive.
     *
     * @param tag Component name
     * @param message Lambda that produces the message (only called in debug builds)
     */
    inline fun d(tag: String, message: () -> String) {
        // The lambda is evaluated in every build now, which is the point of the
        // overload turned on its head: it existed to SKIP building the string in
        // release. Keeping that saving would mean the buffer never sees these lines.
        // Callers with a genuinely expensive message should say so with a plain `if`.
        d(tag, message())
    }

    // ==================== Info Logging ====================

    /**
     * Info log - visible in all builds.
     * Use sparingly for important state changes.
     *
     * @param tag Component name
     * @param message Log message
     */
    fun i(tag: String, message: String) {
        Log.i(formatTag(tag), message)
        DiagnosticsLog.append(tag, message)
    }

    // ==================== Warning Logging ====================

    /**
     * Warning log - visible in all builds.
     * Use for recoverable issues that should be investigated.
     *
     * @param tag Component name
     * @param message Log message
     * @param throwable Optional exception
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(formatTag(tag), message, throwable)
        } else {
            Log.w(formatTag(tag), message)
        }
        DiagnosticsLog.append(tag, throwable?.let { "$message | ${it::class.java.simpleName}: ${it.message}" } ?: message)
    }

    // ==================== Error Logging ====================

    /**
     * Error log - visible in all builds.
     * Use for errors that affect functionality.
     *
     * @param tag Component name
     * @param message Log message
     * @param throwable Optional exception
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(formatTag(tag), message, throwable)
        } else {
            Log.e(formatTag(tag), message)
        }
        // Mirrored into DiagnosticsLog so a user-sent report carries the context a
        // failure happened in, not just the failure. i/w/e only — d and v are already
        // BuildConfig.DEBUG-gated and would be empty in the build users run anyway.
        DiagnosticsLog.append(tag, throwable?.let { "$message | ${it::class.java.simpleName}: ${it.message}" } ?: message)
    }

    // ==================== Verbose Logging (rarely used) ====================

    /**
     * Verbose log - only visible in debug builds.
     * Use for very detailed debugging information.
     *
     * @param tag Component name
     * @param message Log message
     */
    fun v(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.v(formatTag(tag), message)
        }
        // Same reason as [d]: the buffer is the only sink the report can read.
        DiagnosticsLog.append(tag, message)
    }

    /**
     * Verbose log with lazy message evaluation.
     *
     * @param tag Component name
     * @param message Lambda that produces the message
     */
    inline fun v(tag: String, message: () -> String) {
        v(tag, message())
    }
}
