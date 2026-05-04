# Refactoring Roadmap — Code Review 2026-05

> Ordered execution plan derived from `REVIEW_2026-05-04.md`.
> Sequence: Critical → High → Medium → Low, grouped by area for sprint cohesion.

## Effort Estimate

| Bucket | Hours | Days (8h) |
|--------|------:|----------:|
| `XS` | 5.5 | 0.7 |
| `S` | 86.0 | 10.8 |
| `M` | 230.0 | 28.8 |
| `L` | 204.0 | 25.5 |
| `XL` | 90.0 | 11.2 |
| **Total** | **615.5** | **76.9** |

Effort assumes single dev; parallelizable across topics.

## Sprint Plan

### 🚨 Sprint 1 — Critical Security & Correctness (~1–2 weeks)

Focus: AppLock dual-state, layering, cleartext HTTP, auth NPE.

- 🔴 **F-015** [#41](https://github.com/napoleonmm83/paperless-scanner/issues/41) · `M` — UI ViewModels import PaperlessApi directly — Clean Architecture violation
- 🔴 **F-025** [#51](https://github.com/napoleonmm83/paperless-scanner/issues/51) · `XL` — DocumentRepository is a 1349-line God-class with 40+ mixed responsibilities
- 🔴 **F-045** [#86](https://github.com/napoleonmm83/paperless-scanner/issues/86) · `M` — ScanViewModel does not sync pages to Navigation BackStackEntry SavedStateHandle
- 🔴 **F-047** [#71](https://github.com/napoleonmm83/paperless-scanner/issues/71) · `M` — UploadViewModel does not sync document URIs to Navigation BackStackEntry SavedStateHandle
- 🔴 **F-054** [#78](https://github.com/napoleonmm83/paperless-scanner/issues/78) · `S` — DocumentDetailViewModel does not sync documentId to Navigation BackStackEntry
- 🔴 **F-072** [#100](https://github.com/napoleonmm83/paperless-scanner/issues/100) · `S` — Card shape uses 8dp/12dp radius across SuggestionsSection — should be 20dp
- 🔴 **F-106** [#133](https://github.com/napoleonmm83/paperless-scanner/issues/133) · `M` — UploadWorker may exceed 10-min foreground worker limit on slow links
- 🔴 **F-111** [#138](https://github.com/napoleonmm83/paperless-scanner/issues/138) · `L` — ScanViewModel, AppLockManager, TokenManager have zero unit tests

### 🟠 Sprint 2–3 — High-Severity Refactors (~3–4 weeks)

Focus: God-classes (DocumentRepository, HomeViewModel, large screens), reactive Flow migration, Worker resilience.

#### `architecture` (8)

- **F-032** [#58](https://github.com/napoleonmm83/paperless-scanner/issues/58) · `M` — Online/offline branching mixed into CRUD methods — sync coupling
- **F-042** [#68](https://github.com/napoleonmm83/paperless-scanner/issues/68) · `S` — HomeViewModel.tagMap is mutable shared state without thread safety
- **F-043** [#85](https://github.com/napoleonmm83/paperless-scanner/issues/85) · `L` — HomeViewModel: 17+ viewModelScope.launch with inconsistent error handling
- **F-044** [#69](https://github.com/napoleonmm83/paperless-scanner/issues/69) · `M` — ScanViewModel uses imperative .toMutableList() outside StateFlow.update
- **F-046** [#70](https://github.com/napoleonmm83/paperless-scanner/issues/70) · `M` — UploadViewModel race: setDocumentUris vs reactive documentUrisStateFlow
- **F-050** [#74](https://github.com/napoleonmm83/paperless-scanner/issues/74) · `M` — LabelsViewModel exposes mutable vars (allTags, allCorrespondents, …) with race risk
- **F-077** [#104](https://github.com/napoleonmm83/paperless-scanner/issues/104) · `M` — LabelsScreen state held in Composable instead of ViewModel SavedStateHandle
- **F-097** [#124](https://github.com/napoleonmm83/paperless-scanner/issues/124) · `M` — DynamicBaseUrlInterceptor uses runBlocking on every request

#### `data` (3)

- **F-017** [#43](https://github.com/napoleonmm83/paperless-scanner/issues/43) · `L` — Repositories return suspend Result<T> for observable data — should be Flow
- **F-027** [#53](https://github.com/napoleonmm83/paperless-scanner/issues/53) · `M` — Query/paging/filter logic inlined and duplicated across 5 functions
- **F-113** [#140](https://github.com/napoleonmm83/paperless-scanner/issues/140) · `S` — AuthRepository uses !! on potentially-null error variables — NPE risk on login

#### `di` (1)

- **F-016** [#42](https://github.com/napoleonmm83/paperless-scanner/issues/42) · `L` — UploadViewModel constructor explosion (16 dependencies) — SRP violation

#### `performance` (4)

- **F-071** [#99](https://github.com/napoleonmm83/paperless-scanner/issues/99) · `S` — LazyColumn items missing key parameter — recomposition + state corruption risk
- **F-095** [#122](https://github.com/napoleonmm83/paperless-scanner/issues/122) · `M` — Write timeout 60s too tight for large multi-page uploads / Cloudflare paths
- **F-096** [#123](https://github.com/napoleonmm83/paperless-scanner/issues/123) · `L` — RetryInterceptor uses Thread.sleep() — blocks OkHttp dispatcher
- **F-114** [#141](https://github.com/napoleonmm83/paperless-scanner/issues/141) · `M` — MainActivity.onCreate runBlocks on tokenManager.token.first() — startup-blocking

#### `refactor` (9)

- **F-026** [#52](https://github.com/napoleonmm83/paperless-scanner/issues/52) · `L` — Upload pipeline couples PDF creation, compression, IO, API in single 80-line method
- **F-028** [#54](https://github.com/napoleonmm83/paperless-scanner/issues/54) · `M` — Trash/restore/permanent-delete duplicated across single + bulk variants
- **F-041** [#67](https://github.com/napoleonmm83/paperless-scanner/issues/67) · `XL` — Decomposition plan reference (refactor roadmap)
- **F-048** [#72](https://github.com/napoleonmm83/paperless-scanner/issues/72) · `XL` — HomeViewModel is a 1328-line God-VM with 11+ reactive flows
- **F-063** [#91](https://github.com/napoleonmm83/paperless-scanner/issues/91) · `L` — LabelsScreen is a 1495-line god-composable
- **F-064** [#92](https://github.com/napoleonmm83/paperless-scanner/issues/92) · `L` — ScanScreen is a 1450-line god-composable
- **F-065** [#93](https://github.com/napoleonmm83/paperless-scanner/issues/93) · `L` — HomeScreen is a 1414-line god-composable
- **F-066** [#94](https://github.com/napoleonmm83/paperless-scanner/issues/94) · `L` — DocumentTabs is a 1327-line god-composable
- **F-067** [#95](https://github.com/napoleonmm83/paperless-scanner/issues/95) · `M` — SettingsScreen is a 1292-line god-composable

#### `security` (6)

- **F-001** [#28](https://github.com/napoleonmm83/paperless-scanner/issues/28) · `S` — allowBackup enabled — sensitive data may leak via Android Backup
- **F-003** [#30](https://github.com/napoleonmm83/paperless-scanner/issues/30) · `M` — AppLock route reconstruction vulnerable to dual SavedStateHandle desync
- **F-004** [#31](https://github.com/napoleonmm83/paperless-scanner/issues/31) · `L` — network_security_config permits cleartext HTTP for ALL domains
- **F-005** [#32](https://github.com/napoleonmm83/paperless-scanner/issues/32) · `M` — Biometric unlock has no rate limit; bypasses PIN brute-force lockout
- **F-007** [#34](https://github.com/napoleonmm83/paperless-scanner/issues/34) · `M` — BillingManager state machine missing — null-safety + init race risks
- **F-010** [#84](https://github.com/napoleonmm83/paperless-scanner/issues/84) · `L` — No certificate pinning for user-provided Paperless servers (MITM risk)

#### `testing` (2)

- **F-110** [#137](https://github.com/napoleonmm83/paperless-scanner/issues/137) · `L` — Repository tests mock DAOs — violates CLAUDE.md "don't mock the database" rule
- **F-112** [#139](https://github.com/napoleonmm83/paperless-scanner/issues/139) · `M` — SyncWorker, TrashDeleteWorker, WidgetUpdateWorker lack tests

#### `ui` (2)

- **F-060** [#88](https://github.com/napoleonmm83/paperless-scanner/issues/88) · `S` — Inline Color literals bypass theme tokens (ScanScreen)
- **F-068** [#96](https://github.com/napoleonmm83/paperless-scanner/issues/96) · `XS` — Hardcoded color in SuggestionsSection breaks dark mode

#### `widget` (2)

- **F-082** [#109](https://github.com/napoleonmm83/paperless-scanner/issues/109) · `M` — Widget config save: SharedPreferences + Glance state update not atomic
- **F-084** [#111](https://github.com/napoleonmm83/paperless-scanner/issues/111) · `L` — Widget deep-link intents bypass AppLock interception

### 🟡 Sprint 4+ — Medium-Severity Hygiene (rolling)

60 findings — group by area / pair with feature work.

See full index in [`REVIEW_2026-05-04.md`](./REVIEW_2026-05-04.md#findings-index).

### 🔵 Backlog — Low (XS / nice-to-have)

15 findings — bundle into housekeeping PRs.

## ⚡ Quick Wins (high-severity, ≤ 3h effort)

Tackle these first — high impact, low effort:

- 🔴 **F-054** [#78](https://github.com/napoleonmm83/paperless-scanner/issues/78) · `S` — DocumentDetailViewModel does not sync documentId to Navigation BackStackEntry
- 🔴 **F-072** [#100](https://github.com/napoleonmm83/paperless-scanner/issues/100) · `S` — Card shape uses 8dp/12dp radius across SuggestionsSection — should be 20dp
- 🟠 **F-001** [#28](https://github.com/napoleonmm83/paperless-scanner/issues/28) · `S` — allowBackup enabled — sensitive data may leak via Android Backup
- 🟠 **F-042** [#68](https://github.com/napoleonmm83/paperless-scanner/issues/68) · `S` — HomeViewModel.tagMap is mutable shared state without thread safety
- 🟠 **F-060** [#88](https://github.com/napoleonmm83/paperless-scanner/issues/88) · `S` — Inline Color literals bypass theme tokens (ScanScreen)
- 🟠 **F-068** [#96](https://github.com/napoleonmm83/paperless-scanner/issues/96) · `XS` — Hardcoded color in SuggestionsSection breaks dark mode
- 🟠 **F-071** [#99](https://github.com/napoleonmm83/paperless-scanner/issues/99) · `S` — LazyColumn items missing key parameter — recomposition + state corruption risk
- 🟠 **F-113** [#140](https://github.com/napoleonmm83/paperless-scanner/issues/140) · `S` — AuthRepository uses !! on potentially-null error variables — NPE risk on login

## 🏗️ Long-Term Refactors (L/XL effort)

These need dedicated planning + design review:

- 🔴 **F-025** [#51](https://github.com/napoleonmm83/paperless-scanner/issues/51) · `XL` — DocumentRepository is a 1349-line God-class with 40+ mixed responsibilities
- 🔴 **F-111** [#138](https://github.com/napoleonmm83/paperless-scanner/issues/138) · `L` — ScanViewModel, AppLockManager, TokenManager have zero unit tests
- 🟠 **F-004** [#31](https://github.com/napoleonmm83/paperless-scanner/issues/31) · `L` — network_security_config permits cleartext HTTP for ALL domains
- 🟠 **F-010** [#84](https://github.com/napoleonmm83/paperless-scanner/issues/84) · `L` — No certificate pinning for user-provided Paperless servers (MITM risk)
- 🟠 **F-016** [#42](https://github.com/napoleonmm83/paperless-scanner/issues/42) · `L` — UploadViewModel constructor explosion (16 dependencies) — SRP violation
- 🟠 **F-017** [#43](https://github.com/napoleonmm83/paperless-scanner/issues/43) · `L` — Repositories return suspend Result<T> for observable data — should be Flow
- 🟠 **F-026** [#52](https://github.com/napoleonmm83/paperless-scanner/issues/52) · `L` — Upload pipeline couples PDF creation, compression, IO, API in single 80-line method
- 🟠 **F-041** [#67](https://github.com/napoleonmm83/paperless-scanner/issues/67) · `XL` — Decomposition plan reference (refactor roadmap)
- 🟠 **F-043** [#85](https://github.com/napoleonmm83/paperless-scanner/issues/85) · `L` — HomeViewModel: 17+ viewModelScope.launch with inconsistent error handling
- 🟠 **F-048** [#72](https://github.com/napoleonmm83/paperless-scanner/issues/72) · `XL` — HomeViewModel is a 1328-line God-VM with 11+ reactive flows
- 🟠 **F-063** [#91](https://github.com/napoleonmm83/paperless-scanner/issues/91) · `L` — LabelsScreen is a 1495-line god-composable
- 🟠 **F-064** [#92](https://github.com/napoleonmm83/paperless-scanner/issues/92) · `L` — ScanScreen is a 1450-line god-composable
- 🟠 **F-065** [#93](https://github.com/napoleonmm83/paperless-scanner/issues/93) · `L` — HomeScreen is a 1414-line god-composable
- 🟠 **F-066** [#94](https://github.com/napoleonmm83/paperless-scanner/issues/94) · `L` — DocumentTabs is a 1327-line god-composable
- 🟠 **F-084** [#111](https://github.com/napoleonmm83/paperless-scanner/issues/111) · `L` — Widget deep-link intents bypass AppLock interception
- 🟠 **F-096** [#123](https://github.com/napoleonmm83/paperless-scanner/issues/123) · `L` — RetryInterceptor uses Thread.sleep() — blocks OkHttp dispatcher
- 🟠 **F-110** [#137](https://github.com/napoleonmm83/paperless-scanner/issues/137) · `L` — Repository tests mock DAOs — violates CLAUDE.md "don't mock the database" rule
- 🟡 **F-006** [#33](https://github.com/napoleonmm83/paperless-scanner/issues/33) · `L` — TokenManager *Sync() functions runBlocking from OkHttp interceptor — thread starvation risk
- 🟡 **F-023** [#49](https://github.com/napoleonmm83/paperless-scanner/issues/49) · `L` — Dual SavedStateHandle sync pattern lacks compile-time safety
- 🟡 **F-051** [#75](https://github.com/napoleonmm83/paperless-scanner/issues/75) · `L` — HomeViewModelTest covers <20% of reactive flows
