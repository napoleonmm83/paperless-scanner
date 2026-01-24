# Server Offline Detection - Best Practices

## Aktueller Status

### Bestehende Infrastruktur ✅

1. **NetworkMonitor** (`data/network/NetworkMonitor.kt`)
   - Prüft Internet-Konnektivität (validated connection)
   - Unterscheidet WiFi vs. Mobile Data
   - Reactive StateFlows: `isOnline`, `isWifiConnected`
   - Auto-Sync bei Reconnect

2. **NetworkUtils** (`util/NetworkUtils.kt`)
   - Synchrone Network-Checks
   - Network-Typ-Erkennung

3. **PaperlessException** (`data/api/PaperlessException.kt`)
   - `NetworkError` - Netzwerkfehler (DNS, Timeout, no connection)
   - `ServerError` - Server-Fehler (5xx)
   - `ClientError` - Client-Fehler (4xx, inkl. 404)

### Problem: Server vs. Internet

**NetworkMonitor erkennt nur:** "Ist Internet verfügbar?"
**Nicht erkannt:** "Ist mein Paperless-Server erreichbar?"

**Beispiel:**
- Internet: ✅ Verfügbar
- Paperless Server: ❌ Offline (z.B. Docker Container gestoppt, VPN disconnected, Port-Forwarding fehlt)
- **Resultat:** 404/Timeout-Fehler bei Tag-Erstellung, aber unklare Fehlermeldung

---

## Best Practice: 3-Layer Health Check System

### Layer 1: Internet Connectivity (✅ Bereits implementiert)

**NetworkMonitor** prüft ob Internet verfügbar ist.

```kotlin
// Bereits vorhanden
networkMonitor.isOnline.collect { isOnline ->
    if (!isOnline) {
        // Show "No Internet" banner
    }
}
```

### Layer 2: Server Reachability (🆕 NEU)

**ServerHealthMonitor** prüft ob Paperless-Server erreichbar ist.

#### Implementierung

```kotlin
@Singleton
class ServerHealthMonitor @Inject constructor(
    private val api: PaperlessApi,
    private val tokenManager: TokenManager,
    private val networkMonitor: NetworkMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Server-Status
    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Unknown)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    // Combined: Internet + Server
    val isServerReachable: StateFlow<Boolean> = combine(
        networkMonitor.isOnline,
        serverStatus
    ) { isOnline, status ->
        isOnline && status is ServerStatus.Online
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Auto-check on network reconnect
        scope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                if (isOnline) {
                    checkServerHealth()
                }
            }
        }
    }

    /**
     * Check if Paperless server is reachable.
     * Uses lightweight ping endpoint (e.g., /api/ui_settings/ or custom health check).
     */
    suspend fun checkServerHealth(): ServerHealthResult {
        // Skip if no internet
        if (!networkMonitor.checkOnlineStatus()) {
            _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.NO_INTERNET)
            return ServerHealthResult.NoInternet
        }

        return try {
            // Option 1: Use existing lightweight endpoint
            withTimeout(5000) { // 5 second timeout
                api.getUISettings() // Or api.getTags(page=1, pageSize=1)
            }

            _serverStatus.value = ServerStatus.Online(System.currentTimeMillis())
            ServerHealthResult.Success

        } catch (e: TimeoutCancellationException) {
            _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.TIMEOUT)
            ServerHealthResult.Timeout

        } catch (e: UnknownHostException) {
            _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.DNS_FAILURE)
            ServerHealthResult.DnsFailure

        } catch (e: ConnectException) {
            _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.CONNECTION_REFUSED)
            ServerHealthResult.ConnectionRefused

        } catch (e: SocketTimeoutException) {
            _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.TIMEOUT)
            ServerHealthResult.Timeout

        } catch (e: Exception) {
            // HTTP errors (401, 403, 500, etc.) mean server IS reachable
            when (e) {
                is HttpException -> {
                    _serverStatus.value = ServerStatus.Online(System.currentTimeMillis())
                    ServerHealthResult.Success
                }
                else -> {
                    _serverStatus.value = ServerStatus.Offline(ServerOfflineReason.UNKNOWN)
                    ServerHealthResult.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Start periodic health checks (optional, for background monitoring).
     * Use WorkManager for battery efficiency.
     */
    fun startPeriodicHealthChecks(intervalMinutes: Int = 15) {
        // TODO: Implement with WorkManager PeriodicWorkRequest
    }
}

sealed class ServerStatus {
    data object Unknown : ServerStatus()
    data class Online(val lastCheckTimestamp: Long) : ServerStatus()
    data class Offline(val reason: ServerOfflineReason) : ServerStatus()
}

enum class ServerOfflineReason {
    NO_INTERNET,        // No internet connection
    DNS_FAILURE,        // Server hostname not found
    CONNECTION_REFUSED, // Server port not reachable
    TIMEOUT,            // Request timeout
    VPN_REQUIRED,       // Server requires VPN (detected via specific patterns)
    UNKNOWN
}

sealed class ServerHealthResult {
    data object Success : ServerHealthResult()
    data object NoInternet : ServerHealthResult()
    data object Timeout : ServerHealthResult()
    data object DnsFailure : ServerHealthResult()
    data object ConnectionRefused : ServerHealthResult()
    data class Error(val message: String) : ServerHealthResult()
}
```

### Layer 3: Operation-Level Retry (✅ Teilweise vorhanden)

**RetryUtil** für resiliente API-Calls.

```kotlin
// Bereits vorhanden in utils/RetryUtil.kt
suspend fun <T> retryWithExponentialBackoff(
    maxRetries: Int = 3,
    initialDelayMs: Long = 1000,
    maxDelayMs: Long = 10000,
    block: suspend () -> T
): T {
    // Exponential backoff retry logic
}
```

---

## UI-Patterns für Server-Offline-Szenarien

### Pattern 1: Proactive Banner (Empfohlen)

Zeige Banner wenn Server offline ist, BEVOR User Aktion ausführt.

```kotlin
// In UploadScreen.kt
val isServerReachable by serverHealthMonitor.isServerReachable.collectAsState()

if (!isServerReachable) {
    ServerOfflineBanner(
        reason = serverStatus,
        onRetry = { viewModel.checkServerHealth() },
        onSettings = { /* Navigate to server settings */ }
    )
}
```

**Banner-Komponente:**

```kotlin
@Composable
fun ServerOfflineBanner(
    reason: ServerStatus.Offline,
    onRetry: () -> Unit,
    onSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.server_offline_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (reason.reason) {
                        ServerOfflineReason.NO_INTERNET ->
                            stringResource(R.string.server_offline_no_internet)
                        ServerOfflineReason.DNS_FAILURE ->
                            stringResource(R.string.server_offline_dns)
                        ServerOfflineReason.CONNECTION_REFUSED ->
                            stringResource(R.string.server_offline_connection_refused)
                        ServerOfflineReason.TIMEOUT ->
                            stringResource(R.string.server_offline_timeout)
                        ServerOfflineReason.VPN_REQUIRED ->
                            stringResource(R.string.server_offline_vpn_required)
                        else ->
                            stringResource(R.string.server_offline_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            IconButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry")
            }

            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
    }
}
```

### Pattern 2: Better Error Messages

Verbessere `PaperlessException` um Server-Offline zu unterscheiden.

```kotlin
// In PaperlessException.kt - NEW
data class ServerUnreachable(
    val reason: ServerOfflineReason,
    override val message: String = when (reason) {
        ServerOfflineReason.NO_INTERNET ->
            "Keine Internetverbindung - bitte Netzwerk prüfen"
        ServerOfflineReason.DNS_FAILURE ->
            "Server nicht gefunden - bitte Server-URL prüfen"
        ServerOfflineReason.CONNECTION_REFUSED ->
            "Server nicht erreichbar - bitte prüfen ob Server läuft"
        ServerOfflineReason.TIMEOUT ->
            "Server antwortet nicht - bitte später erneut versuchen"
        ServerOfflineReason.VPN_REQUIRED ->
            "VPN-Verbindung erforderlich - bitte VPN aktivieren"
        ServerOfflineReason.UNKNOWN ->
            "Server nicht erreichbar - bitte Verbindung prüfen"
    }
) : PaperlessException(message)

// Update PaperlessException.from() to detect server offline
companion object {
    fun from(throwable: Throwable): PaperlessException {
        return when (throwable) {
            is PaperlessException -> throwable
            is UnknownHostException -> ServerUnreachable(ServerOfflineReason.DNS_FAILURE)
            is ConnectException -> ServerUnreachable(ServerOfflineReason.CONNECTION_REFUSED)
            is SocketTimeoutException -> ServerUnreachable(ServerOfflineReason.TIMEOUT)
            is IOException -> NetworkError(throwable)
            is retrofit2.HttpException -> {
                val code = throwable.code()
                val errorBody = throwable.response()?.errorBody()?.string()
                fromHttpCode(code, errorBody)
            }
            else -> UnknownError(throwable)
        }
    }
}
```

### Pattern 3: Smart Retry Button

Zeige Retry-Button nur wenn sinnvoll.

```kotlin
// In Error UI State
when (val error = uiState.error) {
    is PaperlessException.ServerUnreachable -> {
        // Show banner + retry button
        ErrorMessage(
            message = error.message,
            actionLabel = stringResource(R.string.retry),
            onAction = { viewModel.retry() }
        )
    }
    is PaperlessException.NetworkError -> {
        // Show network error + retry button
    }
    is PaperlessException.AuthError -> {
        // Show auth error + login button
        ErrorMessage(
            message = error.message,
            actionLabel = stringResource(R.string.login),
            onAction = { navigateToLogin() }
        )
    }
    is PaperlessException.ClientError -> {
        // Show error, NO retry (client error won't fix itself)
        ErrorMessage(message = error.message)
    }
}
```

---

## Implementierungs-Strategie

### Phase 1: Bessere Error Messages (Quick Win)

1. ✅ Update `PaperlessException.from()` um Server-Offline zu erkennen
2. ✅ Neue `ServerUnreachable` Exception mit klaren Gründen
3. ✅ UI zeigt spezifische Fehler statt generisches "Ressource nicht gefunden"

**Aufwand:** 1-2 Stunden
**Impact:** Sofort bessere User Experience

### Phase 2: Server Health Monitor (Medium)

1. Erstelle `ServerHealthMonitor` Singleton
2. Integration in ViewModels (Upload, DocumentDetail, etc.)
3. Proactive Banner in Upload-/Scan-Screens

**Aufwand:** 4-6 Stunden
**Impact:** Verhindert viele Fehlversuche

### Phase 3: Periodic Health Checks (Optional)

1. WorkManager Periodic Task
2. Background Sync nur wenn Server online
3. Push-Notification bei Server-Reconnect

**Aufwand:** 2-3 Stunden
**Impact:** Verbesserte Background-Sync-Zuverlässigkeit

---

## Empfohlene Priorisierung

### Must-Have (Jetzt)
- [x] Phase 1: Bessere Error Messages ← **START HIER**

### Should-Have (Nächste 2 Wochen)
- [ ] Phase 2: Server Health Monitor
- [ ] Proactive Banner in Upload/Scan Screens

### Nice-to-Have (Später)
- [ ] Phase 3: Periodic Health Checks
- [ ] Smart Caching für Offline-Mode

---

## String Resources (Neue Strings)

```xml
<!-- errors.xml -->
<string name="server_offline_title">Server nicht erreichbar</string>
<string name="server_offline_no_internet">Keine Internetverbindung verfügbar</string>
<string name="server_offline_dns">Server-Adresse konnte nicht aufgelöst werden. Bitte Server-URL in Einstellungen prüfen.</string>
<string name="server_offline_connection_refused">Server ist nicht erreichbar. Bitte prüfen ob der Server läuft.</string>
<string name="server_offline_timeout">Server antwortet nicht. Bitte später erneut versuchen.</string>
<string name="server_offline_vpn_required">VPN-Verbindung erforderlich. Bitte VPN aktivieren und erneut versuchen.</string>
<string name="server_offline_unknown">Server-Verbindung fehlgeschlagen. Bitte Einstellungen prüfen.</string>
<string name="retry">Erneut versuchen</string>
<string name="check_server_settings">Server-Einstellungen prüfen</string>
```

---

## Testing-Szenarien

### Manuelles Testing

1. **Server offline:**
   - Docker Container stoppen: `docker stop paperless`
   - Erwartung: "Server nicht erreichbar - bitte prüfen ob Server läuft"

2. **Falsche URL:**
   - Server-URL auf `http://invalid.local` ändern
   - Erwartung: "Server-Adresse konnte nicht aufgelöst werden"

3. **VPN disconnected:**
   - VPN trennen (wenn Server hinter VPN)
   - Erwartung: "Server nicht erreichbar"

4. **Timeout:**
   - Netzwerk sehr langsam simulieren
   - Erwartung: "Server antwortet nicht"

5. **Kein Internet:**
   - Flugmodus aktivieren
   - Erwartung: "Keine Internetverbindung verfügbar"

---

## Architektur-Diagramm

```
┌─────────────────┐
│  UI Layer       │
│  (Screens)      │
└────────┬────────┘
         │ observes serverStatus
         │
┌────────▼────────────────────────┐
│  ServerHealthMonitor            │
│  - serverStatus: StateFlow      │
│  - isServerReachable: StateFlow │
│  - checkServerHealth()          │
└────────┬────────────────────────┘
         │ uses
         │
┌────────▼────────┐    ┌──────────────┐
│  NetworkMonitor │    │ PaperlessApi │
│  - isOnline     │    │ - getTags()  │
└─────────────────┘    └──────────────┘
```

---

## Fazit

**Minimaler Ansatz (Quick Win):**
→ Phase 1: Bessere Error Messages implementieren

**Empfohlener Ansatz (Best Practice):**
→ Phase 1 + Phase 2: Error Messages + Server Health Monitor

**Future-Proof (Optional):**
→ Alle 3 Phasen + Offline-First Caching
