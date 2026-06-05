# [plan-01] Data↔Domain↔UI Layer Boundary — one HTTP surface, domain-owned models, pinned API contract

## Defect

The app currently exposes raw data.api layer models, exceptions, and utilities directly to the UI, violating layer boundaries and making refactoring the HTTP transport difficult. Specifically:

- **Issue #48**: AuthRepository injects OkHttpClient directly, allowing raw protocol detection (.newCall() sites at lines 282 & 435) to live alongside Retrofit-managed authentication. This creates a parallel HTTP surface outside the Retrofit layer.
- **Issue #153**: 8+ data.api.* types leak into the UI layer — DTOs (ServerStatusResponse, DatabaseInfo, TasksInfo, CustomField), exception hierarchy (PaperlessException + userMessage), and enums (ServerOfflineReason) — forcing ViewModels to parse nested API structures directly.
- **Issue #132**: ApiModels lack a canonical JSON fixture + round-trip test, making it unsafe to evolve the @SerializedName contract without catching serialization breakage.

**Current reality**: The domain/mapper package has 6 existing DTO→domain mappers (TagMapper, DocumentMapper, etc.) established as the seam pattern, but they don't yet cover the diagnostics/status models. ServerStatusRepository (from #41) introduced a façade precedent but doesn't yet hide the ServerStatusResponse DTO at the UI boundary.

## Children

- #48 — AuthRepository injects OkHttpClient directly for raw protocol detection — parallel HTTP layer beside Retrofit (open)
- #153 — 8+ data.api.* imports leak into ui/: ServerStatusResponse/DatabaseInfo/TasksInfo/CustomField DTOs, PaperlessException+userMessage, ServerOfflineReason enum (open)
- #132 — ApiModels lack a live-API @SerializedName contract test (open)

## Fix sequence

1. **Extract ProtocolDetector service** (#48)
   - Create `data/service/ProtocolDetector.kt` with the protocol-detection logic from AuthRepository.tryProtocol() and verifyPaperlessWithDocumentsEndpoint()
   - Move the lazy `detectionClient` initialization (L85–90) and timeout configuration into ProtocolDetector
   - Inject ProtocolDetector into AuthRepository; replace .newCall() sites (L282, L435) with delegated calls
   - AuthRepository becomes a pure Retrofit façade; OkHttpClient injection only applies to Retrofit setup, not raw request control

2. **Move data.api types to domain/** (#153)
   - Create domain/model/ subtypes for API-specific models not yet covered:
     - `domain/model/ServerStatus.kt` — wraps ServerStatusResponse fields, domain-owned structure
     - `domain/model/CustomField.kt` — mirrors data.api.models.CustomField but in domain namespace
   - Migrate PaperlessException + userMessage deprecation, ServerOfflineReason from data/api/ to domain/error/
   - Create domain/mapper/ServerStatusMapper.kt — maps ServerStatusResponse → ServerStatus
   - Update ~24 ui/ import sites (9 files across login, diagnostics, scan, upload, document-detail ViewModels + components)
   - ServerStatusRepository returns Result<ServerStatus> instead of Result<ServerStatusResponse>

3. **Commit canonical JSON fixtures + add round-trip test** (#132)
   - Create `app/src/test/fixtures/api-models/` with representative JSON samples:
     - `tag.json`, `document.json`, `custom-field.json`, `server-status.json`, `tasks-info.json` (one fixture per DTO type)
   - Create `app/src/test/.../data/api/ApiModelsRoundTripTest.kt`:
     - Load each fixture JSON, deserialize via Gson (the app's serializer), re-serialize, and assert identity
     - Validates that @SerializedName mappings round-trip without loss
     - Runs on every build; catches silent @SerializedName renames / missing annotations

## Test matrix

| Axis | Case | Required behavior |
|---|---|---|
| **ProtocolDetector extraction** | HTTP protocol detection | ProtocolDetector.detectProtocol() returns success URL or typed error (CleartextBlocked, CertificatePinMismatch, NetworkError, etc.) |
| | HTTPS preferred, HTTP fallback | HTTPS tried first; on failure, HTTP fallback is attempted (when user scheme is null) |
| | User-explicit scheme | If user types `http://`, only HTTP is tried (no TLS handshake against plaintext); if `https://`, only HTTPS |
| | Paperless verification | /api/ and /api/documents/ endpoints checked; non-Paperless servers return typed error |
| **Domain-layer DTO migration** | ServerStatus DTO exists | domain/model/ServerStatus wraps response fields; UI never sees ServerStatusResponse |
| | Mapper coverage | ServerStatusMapper.toServerStatus() converts all response fields; unit test confirms shape |
| | UI import audit | All 24 ui/ import sites updated; grep `com.paperless.scanner.data.api` in ui/ returns 0 non-deprecated hits |
| | ViewModels accept domain types | DiagnosticsViewModel.uiState uses ServerStatus, not ServerStatusResponse |
| **API contract test** | JSON round-trip | Gson deserialize(fixture.json) → serialize → matches fixture (whitespace-agnostic) |
| | All DTO types covered | At least one fixture per ApiModels.kt top-level data class |
| | CI integration | Test runs on every commit; breaks build if serialization contract is broken |

## Reusable seams

- `domain/mapper/TagMapper.kt` — established DTO→domain mapper pattern; extends to ServerStatusMapper
- `data/repository/ServerStatusRepository.kt` — façade precedent from #41; updated to return domain/ServerStatus instead of API DTO
- `app/src/test/java/com/paperless/scanner/data/api/` — existing MockWebServer test suite (DynamicBaseUrlInterceptor, CloudflareDetectionInterceptor); reuse for ProtocolDetector unit tests
- `app/src/main/java/com/paperless/scanner/data/repository/AuthRepository.kt` — protocol detection code at lines 270–485 (tryProtocol(), verifyPaperlessWithDocumentsEndpoint(), isPaperlessApiResponse()); extract into ProtocolDetector service

## Out of scope

- **Retrofit HTTP client consolidation**: Future work (plan-02) to unify the OkHttpClient injection across all repositories, ensuring a single HTTP instrumentation point (caching, logging, retry).
- **AuthRepository.login() exception mapping**: While login() returns PaperlessException, mapping it to domain/error is deferred; currently returns via Result<> and UI handles mapping. If #55 (context.getString in data layer) expands to require domain-owned exceptions, revisit.
- **Direct API DTO usage in repositories**: Mappers sit in domain/mapper/ and are called by repositories; repositories may still receive/parse raw API DTOs, but UI never does.
- **Full error-code contract test**: #132 focuses on @SerializedName round-trip only; HTTP error responses (401, 429, 5xx) are tested separately under #47 (exception hierarchy).

