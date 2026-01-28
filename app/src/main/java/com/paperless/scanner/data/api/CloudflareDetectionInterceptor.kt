package com.paperless.scanner.data.api

import com.paperless.scanner.data.datastore.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interceptor that detects Cloudflare usage by checking for cf-ray header in responses.
 * This information is used to show appropriate timeout warnings for large uploads.
 *
 * Cloudflare Detection Strategy:
 * - Checks for "cf-ray" header presence (most reliable Cloudflare indicator)
 * - Stores detection result in TokenManager (persisted across sessions)
 * - Thread-safe detection using AtomicBoolean
 * - Resets detection on server URL changes (logout/login)
 * - Also checks 4xx responses (Cloudflare proxies return 404 for missing endpoints)
 *
 * Why Cloudflare Detection Matters:
 * - Cloudflare has a 100-second timeout limit for HTTP requests
 * - Large PDF uploads (>20 pages) over slow connections may timeout
 * - App shows warnings to users when Cloudflare is detected
 */
class CloudflareDetectionInterceptor(
    private val tokenManager: TokenManager,
    private val applicationScope: CoroutineScope
) : Interceptor {

    // Thread-safe flag to detect only once per session
    private val hasDetected = AtomicBoolean(false)

    // Track current server URL to reset detection on server change
    private var lastServerUrl: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        // Reset detection if server URL changed (e.g., logout/login to different server)
        val currentServerUrl = tokenManager.getServerUrlSync()
        if (currentServerUrl != lastServerUrl) {
            android.util.Log.d("CloudflareDetection", "Server URL changed - resetting detection")
            hasDetected.set(false)
            lastServerUrl = currentServerUrl
        }

        // Check on successful responses OR 4xx client errors (Cloudflare proxies can return 404)
        // Only detect once per session for performance
        if (!hasDetected.get() && (response.isSuccessful || response.code in 400..499)) {
            val cfRayHeader = response.header("cf-ray")
            val usesCloudflare = cfRayHeader != null

            // Atomically set flag to prevent duplicate detection on concurrent requests
            if (hasDetected.compareAndSet(false, true)) {
                if (usesCloudflare) {
                    android.util.Log.d("CloudflareDetection", "Cloudflare detected (cf-ray: $cfRayHeader)")

                    // Store detection result asynchronously using application scope (survives interceptor lifecycle)
                    applicationScope.launch {
                        tokenManager.setServerUsesCloudflare(true)
                    }
                } else {
                    android.util.Log.d("CloudflareDetection", "No Cloudflare detected (status: ${response.code})")

                    applicationScope.launch {
                        tokenManager.setServerUsesCloudflare(false)
                    }
                }
            }
        }

        return response
    }

    /**
     * Reset detection flag - called when user logs out or changes server.
     * This ensures detection runs again on next login.
     */
    fun resetDetection() {
        android.util.Log.d("CloudflareDetection", "Detection reset manually")
        hasDetected.set(false)
        lastServerUrl = null
    }
}
