# Technische Dokumentation

## Paperless Scanner - Android Client

---

## 1. Architektur

### 1.1 Übersicht

Die App folgt der **Clean Architecture** mit **MVVM Pattern**:

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ LoginScreen │  │ ScanScreen  │  │UploadScreen │         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
│         │                │                │                 │
│  ┌──────▼──────┐  ┌──────▼──────┐  ┌──────▼──────┐         │
│  │LoginViewModel│ │ScanViewModel│ │UploadViewModel│        │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
└─────────┼────────────────┼────────────────┼─────────────────┘
          │                │                │
┌─────────▼────────────────▼────────────────▼─────────────────┐
│                      Data Layer                             │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐   │
│  │AuthRepository │  │ TagRepository │  │DocumentRepo   │   │
│  └───────┬───────┘  └───────┬───────┘  └───────┬───────┘   │
└──────────┼──────────────────┼──────────────────┼────────────┘
           │                  │                  │
┌──────────▼──────────────────▼──────────────────▼────────────┐
│                    Infrastructure                           │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐   │
│  │ TokenManager  │  │ PaperlessApi  │  │ MLKit Scanner │   │
│  │  (DataStore)  │  │  (Retrofit)   │  │   (Google)    │   │
│  └───────────────┘  └───────────────┘  └───────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Package-Struktur

```
com.paperless.scanner/
├── di/                          # Hilt Dependency Injection
│   └── AppModule.kt             # Singleton-Providers
│
├── data/
│   ├── api/
│   │   ├── PaperlessApi.kt      # Retrofit Interface
│   │   └── models/
│   │       └── ApiModels.kt     # DTOs
│   │
│   ├── ai/
│   │   ├── AiAnalysisService.kt # Firebase AI (Gemini)
│   │   └── models/
│   │       └── DocumentAnalysis.kt # AI Response DTOs
│   │
│   ├── analytics/
│   │   └── AnalyticsService.kt  # Firebase Analytics
│   │
│   ├── database/
│   │   ├── AppDatabase.kt       # Room Database
│   │   ├── dao/
│   │   │   └── AiUsageDao.kt    # AI Usage Logs
│   │   └── entities/
│   │       └── AiUsageLog.kt    # Usage Tracking
│   │
│   ├── datastore/
│   │   └── TokenManager.kt      # Credentials Storage
│   │
│   └── repository/
│       ├── AuthRepository.kt    # Login/Logout
│       ├── TagRepository.kt     # Tag CRUD
│       ├── DocumentRepository.kt # Upload
│       └── AiUsageRepository.kt # AI Usage Tracking
│
├── ui/
│   ├── theme/
│   │   ├── Color.kt             # Farbpalette
│   │   ├── Theme.kt             # Material 3 Theme
│   │   └── Type.kt              # Typografie
│   │
│   ├── navigation/
│   │   ├── Screen.kt            # Route Definitions
│   │   └── PaperlessNavGraph.kt # NavHost
│   │
│   └── screens/
│       ├── login/
│       │   ├── LoginScreen.kt
│       │   └── LoginViewModel.kt
│       ├── scan/
│       │   ├── ScanScreen.kt
│       │   └── ScanViewModel.kt
│       └── upload/
│           ├── UploadScreen.kt
│           └── UploadViewModel.kt
│
├── MainActivity.kt              # Entry Point
└── PaperlessApp.kt              # Hilt Application
```

---

## 2. Komponenten im Detail

### 2.1 Dependency Injection (Hilt)

**AppModule.kt** stellt alle Singleton-Dependencies bereit:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTokenManager(context: Context): TokenManager

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenManager: TokenManager): OkHttpClient

    @Provides
    @Singleton
    fun providePaperlessApi(client: OkHttpClient, tokenManager: TokenManager): PaperlessApi

    @Provides
    @Singleton
    fun provideAuthRepository(tokenManager: TokenManager): AuthRepository

    @Provides
    @Singleton
    fun provideTagRepository(api: PaperlessApi): TagRepository

    @Provides
    @Singleton
    fun provideDocumentRepository(context: Context, api: PaperlessApi): DocumentRepository
}
```

### 2.2 Token Management

**TokenManager** verwendet Jetpack DataStore für sichere Token-Speicherung:

```kotlin
class TokenManager(private val context: Context) {

    // Reactive Flows
    val token: Flow<String?>
    val serverUrl: Flow<String?>

    // Suspend Functions
    suspend fun saveCredentials(serverUrl: String, token: String)
    suspend fun clearCredentials()

    // Blocking (für OkHttp Interceptor)
    fun getTokenSync(): String?
    fun getServerUrlSync(): String?
}
```

**Wichtig:** `getTokenSync()` verwendet `runBlocking` und sollte nur im OkHttp Interceptor verwendet werden.

### 2.3 API Layer

**PaperlessApi** definiert alle REST-Endpoints:

```kotlin
interface PaperlessApi {

    @POST("api/token/")
    @FormUrlEncoded
    suspend fun getToken(
        @Field("username") username: String,
        @Field("password") password: String
    ): TokenResponse

    @GET("api/tags/")
    suspend fun getTags(): TagsResponse

    @Multipart
    @POST("api/documents/post_document/")
    suspend fun uploadDocument(
        @Part document: MultipartBody.Part,
        @Part("title") title: RequestBody? = null,
        @Part("tags") tags: RequestBody? = null
    ): ResponseBody  // Gibt Task-ID als String zurück
}
```

**Bekannte API-Eigenheiten:**

| Endpoint | Response-Format |
|----------|-----------------|
| `/api/token/` | `{"token": "..."}` |
| `/api/tags/` | `{"count": N, "results": [...]}` |
| `/api/documents/post_document/` | `"task-uuid"` (Plain String!) |

### 2.4 MLKit Document Scanner

**Integration in ScanScreen:**

```kotlin
val scannerOptions = GmsDocumentScannerOptions.Builder()
    .setGalleryImportAllowed(true)
    .setPageLimit(1)
    .setResultFormats(RESULT_FORMAT_JPEG)
    .setScannerMode(SCANNER_MODE_FULL)
    .build()

val scanner = GmsDocumentScanning.getClient(scannerOptions)

// Scanner starten
scanner.getStartScanIntent(activity)
    .addOnSuccessListener { intentSender ->
        scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
    }
```

**Scanner Modes:**

| Mode | Beschreibung |
|------|--------------|
| SCANNER_MODE_FULL | Vollständige UI mit Preview |
| SCANNER_MODE_BASE | Minimale UI |
| SCANNER_MODE_BASE_WITH_FILTER | Mit Bildfiltern |

---

### 2.5 Firebase AI (Gemini) Integration

**AI-gestützte Dokumentanalyse** für automatische Tag-Vorschläge, Titel-Extraktion und Metadaten-Erkennung.

#### Backend-Architektur

```kotlin
@Singleton
class AiAnalysisService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val generativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.firebaseAI())
            .generativeModel(
                modelName = "gemini-2.0-flash",
                generationConfig = generationConfig {
                    temperature = 0.3f
                    maxOutputTokens = 1024
                }
            )
    }
}
```

#### Warum Firebase AI (nicht Google AI direkt)?

| Aspekt | Firebase AI ✅ | Google AI |
|--------|---------------|-----------|
| **Setup** | Nutzt bestehendes Firebase Projekt | Benötigt separaten API Key |
| **Sicherheit** | Kein API Key im Code nötig | API Key in BuildConfig (Risiko) |
| **Billing** | Zentrales Firebase Billing | Separates Billing-Setup |
| **Monitoring** | Firebase Console (Analytics + AI) | Separate Console |
| **Kosten** | 1500 Anfragen/Tag (Free) | 1500 Anfragen/Tag (Free) |
| **Funktion** | Gleiche Gemini Modelle | Gleiche Gemini Modelle |

**Entscheidung:** Firebase AI Backend für einfachere Integration und zentrales Management.

#### Analyse-Flow

```
┌──────────────┐    ┌─────────────────────┐    ┌──────────────────┐
│ UploadScreen │───▶│ UploadViewModel     │───▶│ AiAnalysisService│
│ (User tappt  │    │ analyzeDocument()   │    │ analyzeImage()   │
│  "AI Scan")  │    └─────────────────────┘    └────────┬─────────┘
└──────────────┘                                         │
                                                         ▼
                    ┌────────────────────────────────────────────┐
                    │ Firebase AI (Gemini 2.0 Flash)             │
                    │ - OCR Texterkennung                        │
                    │ - Tag-Matching gegen verfügbare Tags      │
                    │ - Titel-Extraktion                         │
                    │ - Datum/Correspondent Erkennung            │
                    └────────────┬───────────────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │ DocumentAnalysis        │
                    │ - suggestedTitle        │
                    │ - suggestedTags[]       │
                    │ - suggestedCorrespondent│
                    │ - confidence: 0.0-1.0   │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │ UI: Tag Chips anzeigen  │
                    │ (User kann übernehmen)  │
                    └─────────────────────────┘
```

#### Prompt-Engineering

Der AI-Prompt ist optimiert für **präzise Tag-Matching**:

```kotlin
private fun buildPrompt(availableTags: List<Tag>): String {
    val tagList = availableTags.joinToString(", ") { tag ->
        val desc = tag.match?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        "${tag.name}$desc"
    }

    return """
        Analysiere dieses Dokument und extrahiere Metadaten.

        VERFÜGBARE TAGS: $tagList

        Antworte NUR mit JSON:
        {
          "title": "Kurzer Titel",
          "tags": ["tag1", "tag2"],
          "correspondent": "Absender oder null",
          "document_type": "Typ oder null",
          "date": "YYYY-MM-DD oder null",
          "confidence": 0.0-1.0
        }

        REGELN:
        1. Wähle Tags NUR aus der verfügbaren Liste
        2. Confidence = Sicherheit der Analyse (0.0-1.0)
        3. Bei Rechnungen: Suche nach Firma, Betrag, Datum
    """.trimMargin()
}
```

#### AI Usage Tracking & Limits

**Motivation:** Kostenmanagement für Firebase AI API.

**Implementierung:**

1. **Room Database für Usage Logs:**
```kotlin
@Entity(tableName = "ai_usage_logs")
data class AiUsageLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val featureType: String, // "document_analysis"
    val inputTokens: Int,
    val outputTokens: Int,
    val success: Boolean,
    val subscriptionType: String // "free" or "premium"
)
```

2. **Monatliche Limits:**
   - Free Tier: **300 AI-Aufrufe/Monat**
   - Soft Limit @ 100: Info-Meldung
   - Soft Limit @ 200: Warnung
   - Hard Limit @ 300: AI deaktiviert, Fallback auf Paperless Suggestions

3. **Reactive Monitoring:**
```kotlin
// In UploadViewModel:
aiUsageRepository.observeCurrentMonthCallCount()
    .collect { callCount ->
        _remainingCalls.update { (300 - callCount).coerceAtLeast(0) }
        _usageLimitStatus.update {
            when {
                callCount >= 300 -> UsageLimitStatus.HARD_LIMIT_REACHED
                callCount >= 200 -> UsageLimitStatus.SOFT_LIMIT_200
                callCount >= 100 -> UsageLimitStatus.SOFT_LIMIT_100
                else -> UsageLimitStatus.WITHIN_LIMITS
            }
        }
    }
```

#### Firebase Analytics Integration

**Events getrackt:**
- `ai_feature_usage` - Jede AI-Anfrage mit Token-Counts
- `ai_suggestion_accepted` - User übernimmt AI-Vorschlag
- `ai_suggestion_rejected` - User ignoriert Vorschlag
- `ai_monthly_report` - Monatliches Nutzungs-Summary

**Parameter:**
```kotlin
analyticsService.trackAiFeatureUsage(
    featureType = "document_analysis",
    inputTokens = 1000,
    outputTokens = 200,
    subscriptionType = "free"
)
```

#### Fallback-Strategie (Free Tier)

Wenn AI-Limit erreicht oder Premium nicht aktiv:

1. **Paperless Server Suggestions API**
   - `GET /api/documents/{id}/suggestions/`
   - Nutzt Paperless-ngx Neural Network
   - Keine Kosten für App

2. **Lokales Tag-Matching**
   - Fuzzy Match auf Tag-Namen
   - Keyword-Match auf `Tag.match` Feld
   - Rein lokal (offline-fähig)

3. **Dokument-Historie**
   - "Ähnliche Dokumente hatten Tag X"
   - Basiert auf Room Cache

#### Kosten-Kalkulation

**Annahmen:**
- Durchschnitt: 30 Scans/Monat pro User
- Input: ~1000 Tokens (1 Bild + Prompt)
- Output: ~200 Tokens (JSON Response)

**Kosten pro Scan:**
- Input: 1000 tokens × $0.000075 = $0.000075
- Output: 200 tokens × $0.0003 = $0.00006
- **Total: ~$0.00014 pro Scan**

**Kosten pro User/Monat:**
- 30 Scans × $0.00014 = **$0.0042** (~€0.004)
- Mit 3× Safety-Puffer: **$0.013** (~€0.012)

**Business Case:**
- Abo-Preis: €1.99/Monat
- Nach Google Play Fee (15%): €1.69
- Nach AI-Kosten: €1.68
- **Marge: ~98%** ✅

---

## 3. Datenfluss

### 3.1 Login Flow

```
┌──────────┐    ┌─────────────────┐    ┌────────────────┐    ┌─────────────┐
│ LoginUI  │───▶│ LoginViewModel  │───▶│ AuthRepository │───▶│ OkHttp POST │
└──────────┘    └─────────────────┘    └────────────────┘    └──────┬──────┘
                        │                      │                    │
                        │                      ▼                    ▼
                        │              ┌────────────────┐    ┌─────────────┐
                        │◀─────────────│  TokenManager  │◀───│ API Response│
                        │              │  (save token)  │    └─────────────┘
                        ▼              └────────────────┘
                ┌───────────────┐
                │ Navigate to   │
                │ ScanScreen    │
                └───────────────┘
```

### 3.2 Upload Flow

```
┌───────────┐    ┌────────────────┐    ┌──────────────────┐
│ ScanScreen│───▶│ MLKit Scanner  │───▶│ Document URI     │
└───────────┘    └────────────────┘    └────────┬─────────┘
                                                │
                                                ▼
┌───────────┐    ┌────────────────┐    ┌──────────────────┐
│UploadUI   │◀───│ UploadViewModel│◀───│ TagRepository    │
│ (Preview) │    │                │    │ (load tags)      │
└─────┬─────┘    └────────┬───────┘    └──────────────────┘
      │                   │
      │  [User clicks     │
      │   Upload]         │
      ▼                   ▼
┌───────────┐    ┌────────────────┐    ┌──────────────────┐
│ Loading   │◀───│DocumentRepo    │───▶│ Multipart POST   │
│ Indicator │    │ (upload)       │    │ to Paperless     │
└───────────┘    └────────────────┘    └──────────────────┘
```

---

## 4. State Management

### 4.1 UI State Pattern

Jeder ViewModel definiert einen Sealed Class für UI States:

```kotlin
sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data object Success : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

sealed class UploadUiState {
    data object Idle : UploadUiState()
    data object Uploading : UploadUiState()
    data class Success(val taskId: String) : UploadUiState()
    data class Error(val message: String) : UploadUiState()
}
```

### 4.2 StateFlow Exposition

```kotlin
@HiltViewModel
class LoginViewModel @Inject constructor(...) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun login(serverUrl: String, username: String, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LoginUiState.Loading
            // ...
        }
    }
}
```

---

## 5. Netzwerk-Konfiguration

### 5.1 Network Security Config

`res/xml/network_security_config.xml`:

```xml
<network-security-config>
    <!-- Lokale Entwicklung: HTTP erlaubt -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">10.0.0.0/8</domain>
        <domain includeSubdomains="true">192.168.0.0/16</domain>
        <domain includeSubdomains="true">172.16.0.0/12</domain>
    </domain-config>

    <!-- User-CAs für Self-Signed Certs -->
    <base-config>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

### 5.2 OkHttp Interceptor

Token wird automatisch zu allen Requests hinzugefügt:

```kotlin
OkHttpClient.Builder()
    .addInterceptor { chain ->
        val token = tokenManager.getTokenSync()
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Token $token")
                .build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }
    .build()
```

---

## 6. Testing

### 6.1 Unit Tests

```
app/src/test/java/com/paperless/scanner/
├── repository/
│   ├── AuthRepositoryTest.kt
│   ├── TagRepositoryTest.kt
│   └── DocumentRepositoryTest.kt
└── viewmodel/
    ├── LoginViewModelTest.kt
    └── UploadViewModelTest.kt
```

### 6.2 Instrumented Tests

```
app/src/androidTest/java/com/paperless/scanner/
├── LoginScreenTest.kt
├── ScanScreenTest.kt
└── UploadScreenTest.kt
```

### 6.3 Test-Dependencies

```kotlin
// Unit Tests
testImplementation("junit:junit:4.13.2")
testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.0")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")

// UI Tests
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
androidTestImplementation("com.google.dagger:hilt-android-testing:2.53.1")
```

---

## 7. Build & Release

### 7.1 Build Variants

| Variant | Suffix | Features |
|---------|--------|----------|
| debug | `.debug` | Logging, kein ProGuard |
| release | - | ProGuard, Signing |

### 7.2 Signing

Release-Builds benötigen Keystore in `keystore.properties`:

```properties
storeFile=../release-keystore.jks
storePassword=***
keyAlias=paperless-scanner
keyPassword=***
```

### 7.3 ProGuard

`proguard-rules.pro` enthält Regeln für:
- Retrofit (Annotations behalten)
- Gson (Serialization)
- Hilt (DI)
- API Models (nicht obfuskieren)

---

## 8. Bekannte Einschränkungen

| Problem | Workaround | Geplante Lösung |
|---------|------------|-----------------|
| Kein Multi-Page Scan | Einzelseiten uploaden | Phase 2: PDF-Merge |
| Kein Offline-Mode | Nur mit Netzwerk | Phase 2: Queue |
| Kotlin Daemon GC Issue | `-XX:-UseParallelGC` in gradle.properties | Kotlin Update |
| MLKit nur Google Play | - | Kein Fallback geplant |

---

## 9. Debugging

### 9.1 Logs aktivieren

OkHttp Logging ist im Debug-Build aktiv:

```kotlin
HttpLoggingInterceptor().apply {
    level = HttpLoggingInterceptor.Level.BODY
}
```

### 9.2 Häufige Fehler

| Fehler | Ursache | Lösung |
|--------|---------|--------|
| `Expected BEGIN_OBJECT but was STRING` | API gibt String statt JSON | ResponseBody verwenden |
| `Token not found` | Login fehlgeschlagen | Credentials prüfen |
| `Scanner not available` | Kein Google Play | Gerät mit Play Services |
| `CLEARTEXT not permitted` | HTTP ohne Config | network_security_config prüfen |
