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
│   ├── datastore/
│   │   └── TokenManager.kt      # Credentials Storage
│   │
│   └── repository/
│       ├── AuthRepository.kt    # Login/Logout
│       ├── TagRepository.kt     # Tag CRUD
│       └── DocumentRepository.kt # Upload
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
