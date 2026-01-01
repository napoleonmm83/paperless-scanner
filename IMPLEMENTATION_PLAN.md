# Paperless-ngx Android Client - MVP Implementation Plan

## Projektübersicht

**Ziel:** Android-App zum Scannen und Hochladen von Dokumenten zu einer selbstgehosteten Paperless-ngx Instanz.

**Tech Stack:**
- Kotlin
- Jetpack Compose (UI)
- MLKit Document Scanner (Edge Detection)
- Retrofit + OkHttp (API)
- Hilt (Dependency Injection)
- DataStore (Token-Speicherung)
- Coil (Bildanzeige)

---

## Phase 1: Projekt-Setup

### 1.1 Android Studio Projekt erstellen
```
- minSdk: 26 (Android 8.0)
- targetSdk: 34 (Android 14)
- Package: com.paperless.scanner
```

### 1.2 Dependencies (build.gradle.kts)
```kotlin
// Compose
implementation("androidx.compose.ui:ui:1.6.0")
implementation("androidx.compose.material3:material3:1.2.0")
implementation("androidx.navigation:navigation-compose:2.7.6")

// Networking
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

// MLKit Document Scanner
implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")

// DI
implementation("com.google.dagger:hilt-android:2.50")
kapt("com.google.dagger:hilt-compiler:2.50")

// DataStore
implementation("androidx.datastore:datastore-preferences:1.0.0")

// Image Loading
implementation("io.coil-kt:coil-compose:2.5.0")
```

### 1.3 Projektstruktur
```
app/src/main/java/com/paperless/scanner/
├── di/                     # Dependency Injection
│   └── AppModule.kt
├── data/
│   ├── api/               # Retrofit API
│   │   ├── PaperlessApi.kt
│   │   └── models/
│   ├── repository/
│   │   ├── AuthRepository.kt
│   │   ├── DocumentRepository.kt
│   │   └── TagRepository.kt
│   └── datastore/
│       └── TokenManager.kt
├── domain/
│   └── models/            # Domain Models
├── ui/
│   ├── theme/
│   ├── navigation/
│   │   └── NavGraph.kt
│   ├── screens/
│   │   ├── login/
│   │   ├── scan/
│   │   ├── preview/
│   │   └── upload/
│   └── components/
└── PaperlessApp.kt
```

---

## Phase 2: API-Layer

### 2.1 API Models
```kotlin
// TokenResponse.kt
data class TokenResponse(
    val token: String
)

// Tag.kt
data class Tag(
    val id: Int,
    val name: String,
    val color: String?,
    val match: String?,
    val matching_algorithm: Int?
)

// TagsResponse.kt
data class TagsResponse(
    val count: Int,
    val results: List<Tag>
)

// DocumentType.kt
data class DocumentType(
    val id: Int,
    val name: String
)

// UploadResponse.kt
data class UploadResponse(
    val task_id: String
)
```

### 2.2 Retrofit API Interface
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

    @POST("api/tags/")
    suspend fun createTag(
        @Body tag: CreateTagRequest
    ): Tag

    @GET("api/document_types/")
    suspend fun getDocumentTypes(): DocumentTypesResponse

    @Multipart
    @POST("api/documents/post_document/")
    suspend fun uploadDocument(
        @Part document: MultipartBody.Part,
        @Part("title") title: RequestBody?,
        @Part("tags") tags: List<Int>?,
        @Part("document_type") documentType: Int?
    ): UploadResponse
}
```

### 2.3 Token Manager (DataStore)
```kotlin
class TokenManager(private val context: Context) {

    private val dataStore = context.dataStore

    val token: Flow<String?> = dataStore.data.map { it[TOKEN_KEY] }
    val serverUrl: Flow<String?> = dataStore.data.map { it[SERVER_URL_KEY] }

    suspend fun saveCredentials(serverUrl: String, token: String) {
        dataStore.edit { prefs ->
            prefs[SERVER_URL_KEY] = serverUrl
            prefs[TOKEN_KEY] = token
        }
    }

    suspend fun clearCredentials() {
        dataStore.edit { it.clear() }
    }
}
```

---

## Phase 3: Auth-Modul

### 3.1 Login Screen
```kotlin
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit
) {
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Paperless Scanner",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://paperless.example.com") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.login(serverUrl, username, password) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Login")
        }
    }
}
```

### 3.2 Login ViewModel
```kotlin
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState

    fun login(serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading
            try {
                val token = authRepository.login(serverUrl, username, password)
                tokenManager.saveCredentials(serverUrl, token)
                _uiState.value = LoginUiState.Success
            } catch (e: Exception) {
                _uiState.value = LoginUiState.Error(e.message ?: "Login failed")
            }
        }
    }
}

sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    object Success : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}
```

---

## Phase 4: Kamera-Modul (MLKit Document Scanner)

### 4.1 Document Scanner Integration
```kotlin
class DocumentScannerManager(private val activity: ComponentActivity) {

    private val scanner = GmsDocumentScanning.getClient(
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(5)
            .setResultFormats(
                RESULT_FORMAT_JPEG,
                RESULT_FORMAT_PDF
            )
            .setScannerMode(SCANNER_MODE_FULL)
            .build()
    )

    fun startScanner(
        onSuccess: (GmsDocumentScanningResult) -> Unit,
        onError: (Exception) -> Unit
    ) {
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener { onError(it) }
    }
}
```

### 4.2 Scan Screen
```kotlin
@Composable
fun ScanScreen(
    onDocumentScanned: (Uri) -> Unit,
    viewModel: ScanViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.firstOrNull()?.imageUri?.let { uri ->
                onDocumentScanned(uri)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.DocumentScanner,
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Dokument scannen",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { viewModel.startScanner(context, scannerLauncher) }
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Kamera öffnen")
        }
    }
}
```

---

## Phase 5: Upload-Modul

### 5.1 Preview & Upload Screen
```kotlin
@Composable
fun UploadScreen(
    documentUri: Uri,
    viewModel: UploadViewModel = hiltViewModel(),
    onUploadSuccess: () -> Unit
) {
    val tags by viewModel.tags.collectAsState()
    val selectedTags = remember { mutableStateListOf<Int>() }
    var title by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // Document Preview
        AsyncImage(
            model = documentUri,
            contentDescription = "Document Preview",
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Title Input
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Titel (optional)") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Tag Selection
        Text(
            "Tags auswählen",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tags) { tag ->
                FilterChip(
                    selected = selectedTags.contains(tag.id),
                    onClick = {
                        if (selectedTags.contains(tag.id)) {
                            selectedTags.remove(tag.id)
                        } else {
                            selectedTags.add(tag.id)
                        }
                    },
                    label = { Text(tag.name) }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Upload Button
        Button(
            onClick = {
                viewModel.uploadDocument(
                    uri = documentUri,
                    title = title.ifBlank { null },
                    tagIds = selectedTags.toList()
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Icon(Icons.Default.CloudUpload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Hochladen")
        }
    }
}
```

### 5.2 Upload ViewModel
```kotlin
@HiltViewModel
class UploadViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository
) : ViewModel() {

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState

    init {
        loadTags()
    }

    private fun loadTags() {
        viewModelScope.launch {
            try {
                _tags.value = tagRepository.getTags()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun uploadDocument(uri: Uri, title: String?, tagIds: List<Int>) {
        viewModelScope.launch {
            _uploadState.value = UploadState.Uploading
            try {
                documentRepository.uploadDocument(uri, title, tagIds)
                _uploadState.value = UploadState.Success
            } catch (e: Exception) {
                _uploadState.value = UploadState.Error(e.message ?: "Upload failed")
            }
        }
    }
}
```

---

## Phase 6: Navigation

### 6.1 Navigation Graph
```kotlin
@Composable
fun PaperlessNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate("scan") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        composable("scan") {
            ScanScreen(
                onDocumentScanned = { uri ->
                    navController.navigate("upload/${Uri.encode(uri.toString())}")
                }
            )
        }

        composable(
            route = "upload/{documentUri}",
            arguments = listOf(navArgument("documentUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val documentUri = Uri.parse(backStackEntry.arguments?.getString("documentUri"))
            UploadScreen(
                documentUri = documentUri,
                onUploadSuccess = {
                    navController.navigate("scan") {
                        popUpTo("scan") { inclusive = true }
                    }
                }
            )
        }
    }
}
```

---

## Phase 7: App Flow Zusammenfassung

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Login     │ ──▶ │    Scan     │ ──▶ │   Preview   │ ──▶ │   Success   │
│   Screen    │     │   Screen    │     │  + Upload   │     │   Screen    │
└─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘
      │                   │                    │                    │
      │                   │                    │                    │
      ▼                   ▼                    ▼                    ▼
 - Server URL        - MLKit Scanner      - Vorschau            - Zurück zum
 - Username          - Auto Edge          - Titel eingeben        Scan Screen
 - Password            Detection          - Tags auswählen
 - Token speichern   - Crop/Rotate        - Upload Button
```

---

## Zeitschätzung & Meilensteine

| Phase | Aufgabe | Priorität |
|-------|---------|-----------|
| 1 | Projekt-Setup + Dependencies | P0 |
| 2 | API-Layer + Models | P0 |
| 3 | Auth/Login | P0 |
| 4 | MLKit Document Scanner | P0 |
| 5 | Upload-Modul | P0 |
| 6 | Tag-Auswahl | P0 |
| 7 | Navigation + Polish | P1 |
| 8 | Testing | P1 |

---

## Spätere Erweiterungen (Post-MVP)

- [ ] Neuen Tag inline erstellen
- [ ] Dokumenttyp-Auswahl
- [ ] Korrespondent-Auswahl
- [ ] Multi-Page Scan → PDF
- [ ] Offline-Queue
- [ ] Biometrische Authentifizierung
- [ ] Dark Mode
- [ ] Widget für Quick-Scan
- [ ] Dokumenten-Suche
