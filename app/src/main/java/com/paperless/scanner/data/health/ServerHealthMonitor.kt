package com.paperless.scanner.data.health

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.api.ServerOfflineReason
import com.paperless.scanner.data.network.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.math.min
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server Status sealed class representing the current state of the Paperless server.
 */
sealed class ServerStatus {
    /**
     * Server status is unknown (initial state).
     */
    data object Unknown : ServerStatus()

    /**
     * Server is online and reachable.
     * @param lastCheckTimestamp Timestamp of the last successful health check.
     */
    data class Online(val lastCheckTimestamp: Long) : ServerStatus()

    /**
     * Server is offline and unreachable.
     * @param reason The specific reason why the server is offline.
     */
    data class Offline(val reason: ServerOfflineReason) : ServerStatus()
}

/**
 * Server Health Check Result sealed class.
 * Represents the result of a server health check operation.
 */
sealed class ServerHealthResult {
    /**
     * Health check succeeded - server is reachable.
     */
    data object Success : ServerHealthResult()

    /**
     * No internet connection available.
     */
    data object NoInternet : ServerHealthResult()

    /**
     * Server request timed out.
     */
    data object Timeout : ServerHealthResult()

    /**
     * DNS resolution failed - server hostname not found.
     */
    data object DnsFailure : ServerHealthResult()

    /**
     * Connection refused - server port not reachable.
     */
    data object ConnectionRefused : ServerHealthResult()

    /**
     * SSL/TLS certificate error (expired, self-signed, hostname mismatch).
     */
    data object SslError : ServerHealthResult()

    /**
     * VPN required - server is on a private network that requires VPN access.
     */
    data object VpnRequired : ServerHealthResult()

    /**
     * Other error occurred during health check.
     * @param message Error message describing what went wrong.
     */
    data class Error(val message: String) : ServerHealthResult()
}

/**
 * Server Health Monitor - Singleton service for monitoring Paperless server reachability.
 *
 * Features:
 * - Lightweight health checks using existing API endpoints
 * - Reactive server status updates via StateFlow
 * - Automatic health check on network reconnect
 * - Intelligent polling: 30s foreground, 5min background
 * - Exponential backoff on repeated failures
 * - 5-second timeout for health checks
 * - Distinguishes between network issues and actual server offline
 *
 * @param api Paperless API client for making health check requests
 * @param tokenManager Token manager for server configuration
 * @param networkMonitor Network connectivity monitor
 */
@Singleton
class ServerHealthMonitor @Inject constructor(
    private val api: PaperlessApi,
    private val tokenManager: TokenManager,
    private val networkMonitor: NetworkMonitor
) {
    companion object {
        private const val TAG = "ServerHealthMonitor"
        private const val FOREGROUND_POLL_INTERVAL_MS = 30_000L // 30 seconds
        private const val BACKGROUND_POLL_INTERVAL_MS = 300_000L // 5 minutes
        private const val MAX_BACKOFF_INTERVAL_MS = 600_000L // 10 minutes max backoff
        private const val BACKOFF_MULTIPLIER = 2 // Double interval on each failure
        private const val MAX_CONSECUTIVE_FAILURES = 5 // Stop polling after 5 failures
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pollingJob: Job? = null
    private var isInForeground = false
    private var consecutiveFailures = 0

    /**
     * Current server status.
     * Emits updates whenever server status changes.
     */
    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Unknown)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    /**
     * Combined status: Internet connectivity AND server reachability.
     * True only when both internet is available AND server is online.
     */
    val isServerReachable: StateFlow<Boolean> = combine(
        networkMonitor.isOnline,
        serverStatus
    ) { isOnline, status ->
        isOnline && status is ServerStatus.Online
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Auto-check server health when network reconnects
        scope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                android.util.Log.d(TAG, "=== NETWORK STATE CHANGE ===")
                android.util.Log.d(TAG, "NetworkMonitor.isOnline changed to: $isOnline")
                android.util.Log.d(TAG, "Current serverStatus: ${_serverStatus.value}")
                android.util.Log.d(TAG, "Current isServerReachable: ${isServerReachable.value}")

                if (isOnline) {
                    android.util.Log.d(TAG, "Network is online - checking server health")
                    checkServerHealth()
                    // Restart polling when network reconnects
                    if (isInForeground) {
                        startPolling(inForeground = true)
                    }
                } else {
                    android.util.Log.d(TAG, "Network is offline - stopping polling")
                    // Stop polling when no internet
                    stopPolling()
                }
                android.util.Log.d(TAG, "============================")
            }
        }
    }

    /**
     * Start server health polling.
     *
     * Call this when app enters foreground or when you want to begin monitoring.
     *
     * @param inForeground True if app is in foreground (30s interval), false for background (5min interval)
     */
    fun startPolling(inForeground: Boolean = true) {
        isInForeground = inForeground
        consecutiveFailures = 0

        // Cancel existing polling job
        pollingJob?.cancel()

        pollingJob = scope.launch {
            while (isActive) {
                // Check server health
                val result = checkServerHealth()

                // Update failure counter
                when (result) {
                    is ServerHealthResult.Success -> {
                        consecutiveFailures = 0 // Reset on success
                    }
                    else -> {
                        consecutiveFailures++
                    }
                }

                // Stop polling after too many consecutive failures
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    break
                }

                // Calculate delay with exponential backoff
                val baseInterval = if (inForeground) {
                    FOREGROUND_POLL_INTERVAL_MS
                } else {
                    BACKGROUND_POLL_INTERVAL_MS
                }

                val backoffMultiplier = if (consecutiveFailures > 0) {
                    // Exponential backoff: 2^failures
                    min(
                        BACKOFF_MULTIPLIER.toLong().shl(consecutiveFailures - 1),
                        MAX_BACKOFF_INTERVAL_MS / baseInterval
                    )
                } else {
                    1L
                }

                val delayMs = min(baseInterval * backoffMultiplier, MAX_BACKOFF_INTERVAL_MS)

                delay(delayMs)
            }
        }
    }

    /**
     * Stop server health polling.
     *
     * Call this when app goes to background or you want to pause monitoring.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Notify that app entered foreground.
     * Switches to foreground polling interval (30s).
     */
    fun onForeground() {
        startPolling(inForeground = true)
    }

    /**
     * Notify that app entered background.
     * Switches to background polling interval (5min).
     */
    fun onBackground() {
        startPolling(inForeground = false)
    }

    /**
     * Check if Paperless server is reachable.
     *
     * Uses a lightweight API endpoint (getTags with minimal page size) to verify
     * server connectivity without transferring large amounts of data.
     *
     * Updates serverStatus StateFlow with the result:
     * - ServerStatus.Online if server responds (even with HTTP errors like 401/403)
     * - ServerStatus.Offline with specific reason if server is unreachable
     *
     * @return ServerHealthResult indicating the outcome of the health check
     */
    suspend fun checkServerHealth(): ServerHealthResult {
        // Skip health check if no internet connection
        if (!networkMonitor.checkOnlineStatus()) {
            _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.NO_INTERNET)
            return ServerHealthResult.NoInternet
        }

        return try {
            // Use lightweight endpoint: getTags with page size 1
            // 5-second timeout to avoid hanging
            withTimeout(5000) {
                api.getTags(page = 1, pageSize = 1)
            }

            // Server responded successfully
            _serverStatus.value = ServerStatus.Online(System.currentTimeMillis())
            ServerHealthResult.Success

        } catch (e: TimeoutCancellationException) {
            // Request timed out
            _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.TIMEOUT)
            ServerHealthResult.Timeout

        } catch (e: UnknownHostException) {
            // DNS resolution failed - server hostname not found
            _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.DNS_FAILURE)
            ServerHealthResult.DnsFailure

        } catch (e: ConnectException) {
            // Connection refused - server port not reachable
            // Check if this might be a VPN requirement (private network URL)
            if (isPrivateNetworkUrl()) {
                android.util.Log.w(TAG, "Connection refused to private network - VPN may be required")
                _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.VPN_REQUIRED)
                ServerHealthResult.VpnRequired
            } else {
                _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.CONNECTION_REFUSED)
                ServerHealthResult.ConnectionRefused
            }

        } catch (e: SocketTimeoutException) {
            // Socket timeout (different from coroutine timeout)
            _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.TIMEOUT)
            ServerHealthResult.Timeout

        } catch (e: SSLHandshakeException) {
            // SSL handshake failed - certificate issues (expired, self-signed, hostname mismatch)
            android.util.Log.w(TAG, "SSL handshake failed: ${e.message}")
            _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.SSL_ERROR)
            ServerHealthResult.SslError

        } catch (e: SSLException) {
            // General SSL/TLS error
            android.util.Log.w(TAG, "SSL error: ${e.message}")
            _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.SSL_ERROR)
            ServerHealthResult.SslError

        } catch (e: Exception) {
            // HTTP errors need careful handling:
            // - 404: Likely reverse proxy responding, but Paperless not running → OFFLINE
            // - 401/403: Server running, but authentication issue → ONLINE
            // - 500+: Server running, but internal error → ONLINE
            when (e) {
                is HttpException -> {
                    val statusCode = e.code()
                    android.util.Log.d(TAG, "Health check HTTP error: $statusCode ${e.message}")

                    if (statusCode == 404) {
                        // HTTP 404 on /api/tags/ means Paperless is not running
                        // (Reverse proxy/CDN responds, but backend is offline)
                        android.util.Log.w(TAG, "HTTP 404 on health check - treating as server offline")
                        _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.UNKNOWN)
                        ServerHealthResult.Error("Server returned 404 - Paperless may be offline")
                    } else {
                        // Other HTTP errors (401, 403, 500, etc.) mean server IS reachable
                        android.util.Log.d(TAG, "HTTP $statusCode - server is online but returned error")
                        _serverStatus.value = ServerStatus.Online(System.currentTimeMillis())
                        ServerHealthResult.Success
                    }
                }
                else -> {
                    // Unknown error - treat as offline
                    android.util.Log.e(TAG, "Health check unknown error: ${e.message}", e)
                    _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.UNKNOWN)
                    ServerHealthResult.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Checks if the configured server URL points to a private network address.
     * Used to detect if VPN might be required when connection fails.
     *
     * Private IP ranges:
     * - 10.0.0.0/8 (Class A)
     * - 172.16.0.0/12 (Class B)
     * - 192.168.0.0/16 (Class C)
     * - Localhost (127.0.0.0/8)
     */
    private fun isPrivateNetworkUrl(): Boolean {
        val serverUrl = tokenManager.getServerUrlSync() ?: return false
        return try {
            val host = java.net.URL(serverUrl).host
            // Check for private IP patterns
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.startsWith("172.16.") || host.startsWith("172.17.") ||
            host.startsWith("172.18.") || host.startsWith("172.19.") ||
            host.startsWith("172.20.") || host.startsWith("172.21.") ||
            host.startsWith("172.22.") || host.startsWith("172.23.") ||
            host.startsWith("172.24.") || host.startsWith("172.25.") ||
            host.startsWith("172.26.") || host.startsWith("172.27.") ||
            host.startsWith("172.28.") || host.startsWith("172.29.") ||
            host.startsWith("172.30.") || host.startsWith("172.31.") ||
            host.startsWith("127.") ||
            host == "localhost" ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host.endsWith(".lan")
        } catch (e: Exception) {
            false
        }
    }
}
