# Best Practices - Paperless Scanner

Dieses Dokument beschreibt die verbindlichen Best Practices für die Entwicklung des Paperless Scanner Android Clients.

**Stand:** Januar 2026
**Letzte Audit-Migration:** Layer 1-5 abgeschlossen

---

## 1. Room Flow Pattern (Reaktive Datenarchitektur)

### Warum Room Flows?

Room Flows ermöglichen automatische UI-Updates bei Datenbankänderungen. Keine manuellen Refreshes mehr nötig!

**Vorteile:**
- ✅ Single Source of Truth (Room DB)
- ✅ Automatische UI-Updates
- ✅ Kein manuelles Refresh-Management
- ✅ Keine veralteten Daten in UI

### Architektur-Schichten

```
┌─────────────────────────────────────────┐
│  UI Layer (Compose Screens)             │
│  - Kein LaunchedEffect für Daten-Load   │
│  - collectAsState() von ViewModels      │
└─────────────────────────────────────────┘
              ▲
              │ StateFlow<UiState>
              │
┌─────────────────────────────────────────┐
│  ViewModel Layer                         │
│  - observeXXXReactively()               │
│  - Flow.collect { } in viewModelScope   │
│  - StateFlow für UI State               │
└─────────────────────────────────────────┘
              ▲
              │ Flow<List<DomainModel>>
              │
┌─────────────────────────────────────────┐
│  Repository Layer                        │
│  - observeXXX(): Flow<List<T>>          │
│  - map cached entities → domain models  │
└─────────────────────────────────────────┘
              ▲
              │ Flow<List<CachedEntity>>
              │
┌─────────────────────────────────────────┐
│  DAO Layer (Room)                        │
│  - observeXXX(): Flow<List<Entity>>     │
│  - @Query with Flow return type         │
└─────────────────────────────────────────┘
```

### Implementation Pattern

#### Layer 1: DAO (Room Database)

```kotlin
@Dao
interface CachedDocumentDao {
    // ✅ BEST PRACTICE: Reactive Flow method
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 ORDER BY created DESC LIMIT :limit OFFSET :offset")
    fun observeDocuments(limit: Int, offset: Int): Flow<List<CachedDocument>>

    // Legacy suspend method - kept for backward compatibility
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 ORDER BY created DESC LIMIT :limit OFFSET :offset")
    suspend fun getDocuments(limit: Int, offset: Int): List<CachedDocument>
}
```

**Key Points:**
- Flow-Methode für reaktive Beobachtung
- Suspend-Methode optional behalten (für On-Demand Abfragen)
- Dokumentation mit "Reactive Flow" Kommentar

#### Layer 2: Repository

```kotlin
class DocumentRepository @Inject constructor(
    private val cachedDocumentDao: CachedDocumentDao
) {
    /**
     * BEST PRACTICE: Reactive Flow for automatic UI updates.
     * Observes cached documents and automatically notifies when data changes.
     */
    fun observeDocuments(
        page: Int = 1,
        pageSize: Int = 25
    ): Flow<List<Document>> {
        return cachedDocumentDao.observeDocuments(
            limit = pageSize,
            offset = (page - 1) * pageSize
        ).map { cachedList ->
            cachedList.map { it.toCachedDomain() }
        }
    }

    // Legacy method for force refresh
    suspend fun getDocuments(...): Result<List<Document>> { ... }
}
```

**Key Points:**
- Flow vom DAO durchreichen
- Cached Entities → Domain Models mappen
- Legacy suspend methods für Network Refresh behalten

#### Layer 3: ViewModel

```kotlin
@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val documentRepository: DocumentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    init {
        observeDocumentsReactively()
    }

    /**
     * BEST PRACTICE: Reactive Flow-based observation.
     * Automatically updates UI when documents are added/modified/deleted in DB.
     * No manual refresh logic needed!
     */
    private fun observeDocumentsReactively() {
        viewModelScope.launch {
            documentRepository.observeDocuments(
                page = 1,
                pageSize = 25
            ).collect { documents ->
                val uiDocuments = documents.map { doc ->
                    // Transform to UI model
                    DocumentItem(...)
                }

                _uiState.update {
                    it.copy(
                        documents = uiDocuments,
                        isLoading = false
                    )
                }
            }
        }
    }
}
```

**Key Points:**
- Flow im `init {}` block starten
- `viewModelScope.launch { ... .collect { } }`
- Keine manuellen `loadDocuments()` Aufrufe nach Änderungen

#### Layer 4: UI (Compose Screen)

```kotlin
@Composable
fun DocumentsScreen(
    viewModel: DocumentsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // ✅ BEST PRACTICE: No manual refresh needed!
    // Room Flow automatically updates UI when documents change in DB.

    LazyColumn {
        items(uiState.documents, key = { it.id }) { document ->
            DocumentCard(document = document)
        }
    }
}
```

**Key Points:**
- ❌ **KEIN** `LaunchedEffect(Unit) { viewModel.loadDocuments() }`
- ❌ **KEIN** `DisposableEffect` mit `LifecycleObserver`
- ✅ Nur `collectAsState()` von ViewModel StateFlow

---

## 2. Wann NICHT Room Flow nutzen

**Ausnahmen, wo suspend functions OK sind:**

### ✅ API Polling (z.B. Processing Tasks)

```kotlin
// HomeViewModel.kt
fun refreshTasks() {
    viewModelScope.launch {
        val tasks = loadProcessingTasks() // Suspend function
        _uiState.update { it.copy(processingTasks = tasks) }
    }
}
```

**Warum:** Tasks kommen von API, nicht aus Room DB. Keine lokale Cache-Kopie.

### ✅ One-Time Stats Loading

```kotlin
private suspend fun loadStats(): DocumentStat {
    var totalDocuments = 0
    documentRepository.getDocumentCount().onSuccess { count ->
        totalDocuments = count
    }
    return DocumentStat(totalDocuments = totalDocuments)
}
```

**Warum:** Stats sind read-only aggregates, ändern sich nicht häufig.

### ✅ Network Force Refresh

```kotlin
suspend fun forceRefreshFromNetwork(): Result<Unit> {
    return documentRepository.getDocuments(forceRefresh = true)
}
```

**Warum:** Expliziter User-Action (Pull-to-Refresh).

---

## 3. State Management

### StateFlow vs LiveData

✅ **IMMER StateFlow verwenden:**

```kotlin
private val _uiState = MutableStateFlow(UiState())
val uiState: StateFlow<UiState> = _uiState.asStateFlow()
```

❌ **NIEMALS LiveData:**
```kotlin
// VERALTET - nicht verwenden!
private val _uiState = MutableLiveData<UiState>()
val uiState: LiveData<UiState> = _uiState
```

**Warum StateFlow?**
- Modern, Kotlin-first
- Kompatibel mit Kotlin Coroutines
- Type-safe (vs LiveData)
- Compose-ready (`collectAsState()`)

### Sealed Classes für UI States

```kotlin
sealed class UploadUiState {
    data object Idle : UploadUiState()
    data class Uploading(val progress: Float) : UploadUiState()
    data class Success(val taskId: String) : UploadUiState()
    data class Error(val message: String) : UploadUiState()
    data object Queued : UploadUiState()
}
```

**Vorteile:**
- Type-safe exhaustive `when`
- Klare State-Maschine
- Unmöglich inkonsistente States

---

## 4. Offline-First Architecture

### Cache-First Strategie

```kotlin
suspend fun getTags(forceRefresh: Boolean = false): Result<List<Tag>> {
    return try {
        // 1. Versuche Cache zuerst (außer forceRefresh)
        if (!forceRefresh || !networkMonitor.checkOnlineStatus()) {
            val cachedTags = cachedTagDao.getAllTags()
            if (cachedTags.isNotEmpty()) {
                return Result.success(cachedTags.map { it.toDomain() })
            }
        }

        // 2. Network Fetch (wenn online)
        if (networkMonitor.checkOnlineStatus()) {
            val response = api.getTags()
            cachedTagDao.insertAll(response.results.toCachedEntities())
            Result.success(response.results.toDomain())
        } else {
            // 3. Offline, kein Cache
            Result.success(emptyList())
        }
    } catch (e: Exception) {
        Result.failure(PaperlessException.from(e))
    }
}
```

**Prinzipien:**
1. **Cache First** - Immer zuerst lokale Daten
2. **Network as Update** - API nur für Sync
3. **Graceful Degradation** - App funktioniert offline
4. **Room als Single Source of Truth**

### Network Monitor Integration

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    init {
        observeNetworkChanges()
    }

    private fun observeNetworkChanges() {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { currentlyOnline ->
                if (currentlyOnline && wasOffline) {
                    // Auto-refresh when coming back online
                    loadDashboardData()
                }
                wasOffline = !currentlyOnline
            }
        }
    }
}
```

---

## 5. Navigation Results (Optional)

**Hinweis:** Mit Room Flows meist NICHT nötig, da automatisches Update!

### Wann SavedStateHandle verwenden?

Nur wenn:
- Explizite User-Feedback Message nötig (Toast/Snackbar)
- State nicht in DB persistiert wird
- Einmalige Events (z.B. Upload-Success)

### Implementation (Falls nötig)

```kotlin
// Source Screen
LaunchedEffect(uploadSuccess) {
    navController.previousBackStackEntry
        ?.savedStateHandle
        ?.set("upload_success", true)
    onNavigateBack()
}

// Destination Screen
LaunchedEffect(Unit) {
    navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow("upload_success", false)
        ?.collect { wasSuccess ->
            if (wasSuccess) {
                snackbarHostState.showSnackbar("Upload erfolgreich!")
                savedStateHandle.set("upload_success", false) // Reset
            }
        }
}
```

**Aber:** Meist überflüssig dank Room Flow! Dokument erscheint automatisch in Liste.

---

## 6. Error Handling

### Result<T> Pattern

```kotlin
// Repository
suspend fun createTag(name: String): Result<Tag> {
    return try {
        val response = api.createTag(CreateTagRequest(name))

        // Update local cache
        cachedTagDao.insert(response.toCachedEntity())

        Result.success(response.toDomain())
    } catch (e: Exception) {
        Result.failure(PaperlessException.from(e))
    }
}

// ViewModel
fun createLabel(name: String) {
    viewModelScope.launch {
        tagRepository.createTag(name)
            .onSuccess { newTag ->
                // Success - Flow updates UI automatically!
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(error = error.message ?: "Fehler beim Erstellen")
                }
            }
    }
}
```

**Vorteile:**
- Type-safe error handling
- Explizite Success/Failure Pfade
- Kein Exception Throwing in Business Logic

---

## 7. DO's and DON'Ts

### ✅ DO

```kotlin
// Reactive Flow in DAO
@Query("SELECT * FROM tags")
fun observeTags(): Flow<List<CachedTag>>

// Flow in Repository
fun observeTags(): Flow<List<Tag>> {
    return dao.observeTags().map { it.toDomain() }
}

// Flow collect in ViewModel init
init {
    observeTagsReactively()
}

// UI CollectAsState
val tags by viewModel.tags.collectAsState()
```

### ❌ DON'T

```kotlin
// ❌ Manual refresh in UI
LaunchedEffect(Unit) {
    viewModel.loadTags()
}

// ❌ Lifecycle Observer für Daten
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == ON_RESUME) viewModel.loadTags()
    }
}

// ❌ LiveData
private val _tags = MutableLiveData<List<Tag>>()

// ❌ Manual list update nach Create
tagRepository.createTag(name).onSuccess { newTag ->
    _tags.update { current -> current + newTag } // ❌ FALSCH!
}
// ✅ RICHTIG: Flow aktualisiert automatisch!
```

---

## 8. Testing Guidelines

### ViewModel Tests

```kotlin
@Test
fun `observeTagsReactively updates UI when tags change in DB`() = runTest {
    // Given
    val testTags = listOf(Tag(1, "Invoice"), Tag(2, "Receipt"))
    fakeTagRepository.emitTags(testTags)

    // When
    val viewModel = LabelsViewModel(fakeTagRepository)

    // Then
    val uiState = viewModel.uiState.value
    assertEquals(2, uiState.labels.size)
    assertEquals("Invoice", uiState.labels[0].name)
}
```

### Repository Tests

```kotlin
@Test
fun `observeTags emits updates when DAO data changes`() = runTest {
    // Given
    val dao = FakeCachedTagDao()
    val repository = TagRepository(api, dao, ...)

    // When
    val emissions = mutableListOf<List<Tag>>()
    backgroundScope.launch {
        repository.observeTags().toList(emissions)
    }

    dao.insert(CachedTag(1, "Tag1"))
    advanceUntilIdle()

    // Then
    assertEquals(1, emissions.size)
    assertEquals("Tag1", emissions[0][0].name)
}
```

---

## 9. Migration Checklist

Für neue Features oder Refactoring:

- [ ] **DAO:** Flow-Methode hinzugefügt?
- [ ] **Repository:** Flow-Methode mappt cached → domain?
- [ ] **ViewModel:** observeXXXReactively() im init{}?
- [ ] **UI:** Keine LaunchedEffect für loadXXX()?
- [ ] **UI:** Keine DisposableEffect für Refresh?
- [ ] **Tests:** Flow-basierte Tests geschrieben?
- [ ] **Dokumentation:** BEST PRACTICE Kommentare?

---

## 10. Code Examples

### Complete Feature: Tags Management

**DAO:**
```kotlin
@Dao
interface CachedTagDao {
    @Query("SELECT * FROM cached_tags WHERE isDeleted = 0 ORDER BY name ASC")
    fun observeTags(): Flow<List<CachedTag>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: CachedTag)

    @Update
    suspend fun update(tag: CachedTag)

    @Query("UPDATE cached_tags SET isDeleted = 1 WHERE id = :id")
    suspend fun softDelete(id: Int)
}
```

**Repository:**
```kotlin
class TagRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedTagDao: CachedTagDao
) {
    fun observeTags(): Flow<List<Tag>> {
        return cachedTagDao.observeTags()
            .map { cachedList -> cachedList.map { it.toDomain() } }
    }

    suspend fun createTag(name: String): Result<Tag> {
        return try {
            val response = api.createTag(CreateTagRequest(name))
            cachedTagDao.insert(response.toCachedEntity())
            Result.success(response.toDomain())
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun deleteTag(id: Int): Result<Unit> {
        return try {
            api.deleteTag(id)
            cachedTagDao.softDelete(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }
}
```

**ViewModel:**
```kotlin
@HiltViewModel
class LabelsViewModel @Inject constructor(
    private val tagRepository: TagRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LabelsUiState())
    val uiState: StateFlow<LabelsUiState> = _uiState.asStateFlow()

    init {
        observeTagsReactively()
    }

    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags().collect { tags ->
                val labelItems = tags.map { tag ->
                    LabelItem(id = tag.id, name = tag.name)
                }

                _uiState.update {
                    it.copy(labels = labelItems, isLoading = false)
                }
            }
        }
    }

    fun createLabel(name: String) {
        viewModelScope.launch {
            tagRepository.createTag(name)
                .onSuccess {
                    // No manual refresh needed! Flow updates automatically.
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message) }
                }
        }
    }

    fun deleteLabel(id: Int) {
        viewModelScope.launch {
            tagRepository.deleteTag(id)
                .onSuccess {
                    // No manual refresh needed! Flow updates automatically.
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message) }
                }
        }
    }
}
```

**UI Screen:**
```kotlin
@Composable
fun LabelsScreen(
    viewModel: LabelsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // No manual loading - Flow handles everything!

    LazyColumn {
        items(uiState.labels, key = { it.id }) { label ->
            LabelItem(
                label = label,
                onDelete = { viewModel.deleteLabel(label.id) }
            )
        }
    }
}
```

---

## 11. Zusammenfassung

**Kern-Prinzipien:**

1. **Room als Single Source of Truth** - Alle Daten durch Room DB
2. **Reactive Flows** - Automatische Updates, kein Manual Refresh
3. **Offline-First** - Cache first, Network as Update
4. **StateFlow über LiveData** - Modern, Kotlin-first
5. **Result<T> Pattern** - Type-safe Error Handling
6. **Sealed Classes** - Type-safe States

**Architektur-Flow:**
```
DAO (Flow) → Repository (Flow) → ViewModel (collect) → UI (collectAsState)
```

**Entwickler-Mantra:**
> "Wenn du manuell refresh() aufrufst, machst du es falsch!"

---

**Version:** 1.0
**Letzte Änderung:** 06.01.2026
**Autor:** Claude Sonnet 4.5 (Best Practice Audit Migration)
