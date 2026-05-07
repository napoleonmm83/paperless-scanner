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

    /**
     * Default write timeout for non-upload write operations (seconds).
     *
     * Document uploads use the adaptive timeout from
     * [AdaptiveWriteTimeoutInterceptor] instead, computed from
     * [WRITE_TIMEOUT_BASE_SECONDS], [WRITE_TIMEOUT_PER_MB_SECONDS], and
     * [WRITE_TIMEOUT_CLOUDFLARE_CAP_SECONDS].
     */
    const val WRITE_TIMEOUT_SECONDS = 60L

    /** Base write timeout for adaptive document uploads (seconds). */
    const val WRITE_TIMEOUT_BASE_SECONDS = 120L

    /** Additional seconds added to the upload write timeout per megabyte of payload. */
    const val WRITE_TIMEOUT_PER_MB_SECONDS = 2L

    /**
     * Cap applied to adaptive upload write timeouts when the server is
     * detected to sit behind Cloudflare.
     *
     * Cloudflare's edge enforces a 100-second timeout on individual HTTP
     * requests, so values above this would be wasted; 90s leaves a buffer
     * for client/server clock drift.
     */
    const val WRITE_TIMEOUT_CLOUDFLARE_CAP_SECONDS = 90L

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

    /** Initial delay between retry attempts (milliseconds) — exponential backoff base */
    const val RETRY_DELAY_MS = 1_000L

    /** Upper cap for exponential backoff between retry attempts (milliseconds) */
    const val RETRY_MAX_DELAY_MS = 10_000L
}
