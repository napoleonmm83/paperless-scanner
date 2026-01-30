package com.paperless.scanner.util

/**
 * Centralized network configuration constants.
 *
 * All timeout values and network-related configuration should be defined here
 * to avoid magic numbers scattered throughout the codebase.
 */
object NetworkConfig {

    // ============================================================
    // HTTP Client Timeouts (OkHttp)
    // ============================================================

    /** Connection timeout for standard API requests (seconds) */
    const val CONNECT_TIMEOUT_SECONDS = 30L

    /** Read timeout for standard API requests (seconds) */
    const val READ_TIMEOUT_SECONDS = 30L

    /** Write timeout for upload operations (seconds) */
    const val WRITE_TIMEOUT_SECONDS = 60L

    // ============================================================
    // Server Detection Timeouts
    // ============================================================

    /** Timeout for protocol detection (HTTPS/HTTP) during server setup (seconds) */
    const val DETECTION_TIMEOUT_SECONDS = 10L

    /** Connection timeout for thumbnail loading (milliseconds) */
    const val THUMBNAIL_TIMEOUT_MS = 10_000

    // ============================================================
    // Health Check Configuration
    // ============================================================

    /** Timeout for server health check requests (milliseconds) */
    const val HEALTH_CHECK_TIMEOUT_MS = 5_000L

    // ============================================================
    // AI/Analysis Timeouts
    // ============================================================

    /** Timeout for AI image analysis operations (milliseconds) */
    const val AI_ANALYSIS_TIMEOUT_MS = 30_000L

    // ============================================================
    // Retry Configuration
    // ============================================================

    /** Maximum number of retry attempts for failed requests */
    const val MAX_RETRIES = 3

    /** Delay between retry attempts (milliseconds) */
    const val RETRY_DELAY_MS = 1_000L
}
