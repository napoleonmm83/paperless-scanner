# 00 — Master Execution Plan (Stand 2026-06-10)

> Code-verifizierter Ausführungsplan für die **letzten offenen Issues**. Verifiziert gegen
> `origin/main` @ `8f8537e2` (v1.5.201) durch 5 parallele Prüf-Agenten (je Issue-Cluster einer).
> Ersetzt den Stand 2026-06-05. Die `0X-*.md` Design-Docs bleiben die Detail-Specs; dieses
> Dokument ist Reihenfolge + verifizierte Stolperfallen-Leitplanken.

## 0. Was seit 2026-06-05 erledigt wurde

Wave A (Housekeeping), B1–B4 (detekt-Modul, plan-04 #322, plan-08 #45, plan-05-kdoc), C1/C2
(plan-05 #323, plan-06 #324), D1 (plan-01 **#319 komplett**: #48→PR#339, #153→PR#340/341,
#343→PR#344, #342→PR#345, #132 closed-tautological) und plan-07a (#302→PR#328) sind **gemerged
und deployed** (Internal v1.5.182–v1.5.201). Übrig: **5 Issues + 8 Dependabot-PRs.**

## 1. Reconciled Status — was *wirklich* noch offen ist

| Item | Was | Aufwand | Risiko | Blockiert? |
|------|-----|---------|--------|------------|
| **#334** | tag-count delta: unknown ≠ empty old-tag-set (plan-05 Follow-up, P3) | **S** | niedrig | nein |
| **Dependabot #346/347/348** | github_actions Bumps (cache v5, gh-release v3, upload-artifact v7) — Batch-PR wie #305 | **S** | niedrig | nein |
| **#321 plan-03** | Test-Double-Foundation: ~9 `*Contract`-Interfaces → #239 + #202 | **XL** | mittel | nein |
| **#320 plan-02** | SecureTokenStorage Failure-Taxonomy (Phase 1 = ex-#303, Phase 2 = ex-#37) | **L** | **hoch** | nein* |
| **#325 plan-07** | Rest = NUR ProGuard-iText-Narrowing (#302 ✅ gelandet, #145 closed/not_planned) | **S** (+Geräte-Smoke) | mittel | **Entscheidung + Geräte-QA** |
| **#296** | Umbrella-Tracker: 6 Feature-TODOs (3 unblocked S/M, 3 premium/produkt-gated) | optional | niedrig | teilweise (Produkt) |
| Dependabot #13–17 | cameraX/workManager/firebase/coroutines-test/ben-manes | M | **runtime-riskant** | Geräte-QA |

\* plan-02 ist nicht extern blockiert, aber der echte `AEADBadTagException`-Pfad ist unter
JVM/Robolectric nicht testbar → dokumentierter Real-Device-Test als Abschluss-Gate.

## 2. Verifizierte Korrekturen an den Design-Docs (2026-06-10 — VOR Ausführung lesen!)

### plan-03 / #321 (`03-test-interfaces.md` + Issue-Body haben 4 Fehler)
1. **`SyncManager.pendingChangesCount` ist `StateFlow<Int>`** (SyncManager.kt:109), nicht `Flow`.
   `UploadQueueRepository.pendingCount` ist dagegen `Flow<Int>` (:71). Contracts müssen die
   ECHTEN Typen deklarieren — vorher je Property greppen, nicht dem Doc glauben.
2. **`recordException` existiert NUR auf `CrashlyticsHelper`** (:101). `AnalyticsService` hat
   stattdessen `logError()` (:108) / `logMessage()` (:120) / `isAnalyticsEnabled()` (:129).
   `FakeAnalyticsService` für #239 muss logError/logMessage aufzeichnen, nicht recordException.
3. **`SyncManager.stop()` existiert NICHT.** Der Contract darf keine stop()-Methode deklarieren.
4. **3 Collaborators sind `@Provides` in AppModule** → für sie KEIN `@Binds`, sondern
   Rückgabetyp des Providers auf `*Contract` ändern: `provideUploadQueueRepository` (:576),
   `provideSyncManager` (:598, 9 DAO-Params), `provideTokenManager` (:164; TokenManager hat
   keinen `@Inject`-Konstruktor). Die übrigen 6 (`SyncHistoryRepository`, `DocumentRepository`,
   `TrashRepository`, `NetworkMonitor`, `ServerHealthMonitor`, `CrashlyticsHelper` —
   plus `AnalyticsService`) sind `@Inject`-auto-wired → neues `@Binds`-Modul funktioniert.

Verifizierte Injektionsstellen (alle müssen auf Contract-Typ flippen):
- `UploadWorker` (:39–49): uploadQueueRepository, documentRepository, taskRepository,
  networkMonitor, serverHealthMonitor, syncHistoryRepository, crashlyticsHelper
- `SyncWorker` (:21–26): syncManager, crashlyticsHelper
- `TrashDeleteWorker` (:36–44): trashRepository, tokenManager, syncHistoryRepository,
  crashlyticsHelper (— `cachedDocumentDao` bleibt DAO, kein Contract)
- `WidgetUpdateWorker` (:35–39): uploadQueueRepository
- `ServerHealthViewModel` (:55–62): networkMonitor, serverHealthMonitor, uploadQueueRepository,
  syncHistoryRepository, syncManager, analyticsService

Weitere Fakten: kein `*Contract.kt`/`Fake*.kt` existiert; 207× `mockk(relaxed=true)` in 20+
Test-Dateien (Scope hier: nur die 5 Ziel-Dateien); Test-Pfade wie im Doc
(`SyncWorkerTest`→`data/sync/`, `WidgetUpdateWorkerTest`→`widget/`).

### plan-02 / #320 (Zitate stimmen noch)
Verifiziert: `getOrCreateEncryptedPrefs` :38–51 (catch-all → Recovery :46–48),
`recoverCorruptedStorage` :77–110 (löscht Prefs-File :82 + Master-Key :90–95),
getToken/saveToken-Swallowing :118–141, `TokenStorage`-Interface :14–18 (5 Methoden),
`TokenManager.init` :84–136 (ruft `isMigrationCompleted()` :102, KEIN Corruption-Signal),
`getTokenSync` :324–326. Sealed-Vorbild: `ServerHealthResult` in ServerHealthMonitor.kt:60–101.
`TokenManagerTest` mockt das Interface (Robolectric + MockK) → Taxonomy ist JVM-testbar.
Es existiert noch KEINE TokenStorageResult/FailureKind-Vorarbeit.

### #334 (Befund bestätigt, Fix-Geometrie)
- Sites: `SyncManager.getOldTagIds` :534–543 (Call-Site :490, Delta :510–515) und
  `DocumentMetadataRepository.getOldTagIds` :191–200 (Call-Site :112, Delta :127–131).
- `DocumentSerializer.deserializeCachedTagIds` :35–41 gibt `emptyList()` für null/blank UND
  unparseable zurück (dokumentierter Contract :20) — **dritter Consumer `TagRepository:249`**
  darf nicht brechen → neue nullable Variante (`deserializeCachedTagIdsOrNull`, null = Parse-
  Fehler) ergänzen statt Semantik der bestehenden Methode zu ändern.
- Fix: beide `getOldTagIds` → `List<Int>?`; `null` wenn (a) cached row fehlt (`cached == null`)
  oder (b) Parse-Fehler/Exception. `tags == "[]"` bleibt `emptyList()` (echte leere Menge,
  Delta soll laufen). Die bestehenden Guards `if (tags != null && oldTagIds != null)` greifen
  dann automatisch. Kdoc-Kommentare (:487–489, :109–111, :529–533) mit-aktualisieren.

### plan-07 / #325 (nur noch ProGuard übrig)
- **#302-Slice vollständig gelandet** (PR #328, `ae0338b9`): pre-push liest stdin-Refs und
  rebased nur `main` (:15–22); `scripts/check-lazy-keys.sh` existiert und wird von
  `validate-ci.sh:206` UND `android-ci-optimized.yml:279` aufgerufen. ✔ verifiziert.
- **Rest:** `app/proguard-rules.pro:46–52` hat weiterhin 3 Blanket-Keeps (`com.itextpdf.**`
  class/interface/abstract-class); einziger iText-Consumer ist `PdfGeneratorService.kt:5–10`
  mit exakt 6 Klassen (ImageDataFactory, PageSize, PdfDocument, PdfWriter, Document, Image).
  Reflection-Guard für PdfObject-Subklassen (:59–61) existiert bereits separat — behalten.
- #145 wurde als not_planned geschlossen (Geräte-QA-gated) → #325 braucht eine **Entscheidung**
  (siehe Wave 4).

### #296 (alle 6 TODOs verifiziert vorhanden, KNOWN_ISSUES.md §Known Debt aktuell)
Unblocked: `LabelsScreen:291` isCreating-State (**S**, CreateEntityDialog-Param existiert),
`UploadComponents:500` DatePicker (**S**, Material3-DatePicker verfügbar),
`StepByStepMetadataScreen:231` Tag-Dialog (**M**, CreateEntityDialog-Muster wiederverwendbar).
Produkt-/Premium-gated: `MainActivity:110` + `AnalyzeDocumentUseCase:105` (Billing-Tier),
`AnalyzeDocumentUseCase:82` OCR-Extraktion (**L**, ML-Kit-Pipeline + Produktentscheid).

## 3. Ausführungs-Wellen

Pflicht-Gate je PR (unverändert): `git rebase origin/main` → `scripts/validate-ci.sh` grün →
`codex review` PASS → PR → CodeRabbit abarbeiten → squash-merge → Auto-Deploy Internal.
Changelog `<versionCode>.txt` (DE+EN, ≤500 Zeichen) **zur Merge-Zeit** berechnen (Memory:
feedback_changelog_version_alignment). Self-hosted-Runner = diese Maschine → kein lokales
Gradle während aktiver Deploys.

---

### Wave 1 — Quick Wins (S, ~½ Session)

**PR-1: #334 unknown-vs-empty Tag-Delta.** Fix-Geometrie wie oben in §2/#334. Tests: je
Implementierung 2 neue Fälle (cached row fehlt → kein Delta; invalid JSON → kein Delta) +
Bestands-Fall leer→nonempty inkrementiert weiter — spiegeln an
`DocumentMetadataRepositoryTest` (Rollback/Divergenz-Tests) und `SyncManagerTest`.
→ schließt #334, plan-05-Faden komplett zu.

**PR-2: Dependabot-Batch #346+#347+#348** (actions/cache v5, softprops/action-gh-release v3,
actions/upload-artifact v7) in EINEM PR (Präzedenz: PR #305 — vermeidet 3 redundante Deploys +
kaskadierende Rebases). Die 3 Einzel-PRs danach schließen. **Achtung gh-release 2→3 ist ein
Major** — vor Merge Release-Notes des Actions-Repos auf Breaking-Inputs prüfen (`release.yml`
+ `auto-deploy-internal.yml` nutzen es); der nächste Auto-Deploy testet den Bump end-to-end.

---

### Wave 2 — plan-03 #321 Test-Double-Foundation (XL, 2–3 Sessions, 4 PRs)

Design-Doc `03-test-interfaces.md`, MIT den §2-Korrekturen. Strikt sequenziert:

**PR-A — Contracts + Hilt-Rewiring (Phase 1, reine Produktion, keine Test-Migration):**
~9 `*Contract`-Interfaces **adjazent** zu den Impls (nicht in `di/`). Signaturen 1:1 aus den
ECHTEN Klassen kopieren (StateFlow/Flow-Typen exakt, kein stop()). 6 auto-wired → neues
`@Binds`-Modul; 3 `@Provides` → Rückgabetyp flippen. Alle §2-Injektionsstellen auf Contracts.
Bestehende Tests müssen UNVERÄNDERT grün bleiben (Compile-Anpassungen erlaubt, keine
Semantik-Änderung). Gate: `compileReleaseKotlin` löst alle Hilt-Bindings.

**PR-B — #239 ServerHealthViewModelTest auf typed Fakes:** FakeNetworkMonitor,
FakeServerHealthMonitor, FakeUploadQueueRepository, FakeSyncManager, FakeSyncHistoryRepository,
FakeAnalyticsService (logError/logMessage-Recording!) unter `app/src/test/.../testing/fakes/`.
Turbine für Flow-Asserts (`.coderabbit.yaml` mandatiert Turbine). → schließt #239.

**PR-C — #202 leichte Worker-Tests:** SyncWorkerTest (2 Mocks), TrashDeleteWorkerTest,
WidgetUpdateWorkerTest auf Fakes (FakeTrashRepository, FakeCrashlyticsHelper, FakeTokenManager
neu). DAO-Mocks bleiben (kein Contract für DAOs).

**PR-D — #202 UploadWorkerTest ZULETZT:** 30 Tests / 37KB, coVerify/spyk/mockkStatic — der
riskanteste Brocken bewusst isoliert. Verhalten-für-Verhalten migrieren, Testanzahl darf nicht
sinken. → schließt #202, dann **#321 komplett zu**.

---

### Wave 3 — plan-02 #320 Token-Failure-Taxonomy (L, HOCH-Risiko, 1–2 Sessions, 2 PRs)

Design-Doc `02-token-taxonomy.md`. Sicherheitskritischer Credential-Pfad → bewusst NACH
Wave 2 (Fakes/Contracts aus plan-03 können TokenManager-Tests stützen). Strikt Phase 1 → 2:

**PR-E — Phase 1 Signal-Threading (ex-#303-Scope):** `@Volatile lastRecoveredCryptoFailure` in
SecureTokenStorage; im Catch (:44–48) klassifizieren BEVOR Recovery läuft — Wipe nur bei
bestätigter `AEADBadTagException`, transiente Fehler (Keystore unavailable, IO) rethrow.
`TokenManager.init` (:84–136) pollt das Signal und setzt ein Flag für den Login-Flow
(„Anmeldedaten beschädigt, bitte neu einloggen"). Tests via gemocktem Interface/Fake.

**PR-F — Phase 2 Result-Taxonomy + Restore-statt-Wipe (ex-#37-Scope):** `sealed
TokenStorageResult` + `TokenStorageFailureKind` (Vorbild ServerHealthResult :60–101),
`getTokenResult`/`saveTokenResult` im Interface (alte API als deprecated Delegatoren),
Backup-Snapshot beim ersten Open + Restore bei Korruption statt unbedingtem Wipe.
Kern-Beweis-Test: **KEIN Wipe bei KEYSTORE_UNAVAILABLE.**

**Abschluss-Gate:** dokumentierter Real-Device-Test (adb-Keystore-Korruption + Neustart →
Re-Login-Dialog statt stillem Token-Verlust) — Anleitung in `docs/` ablegen, User führt aus.
→ schließt #320.

---

### Wave 4 — plan-07 #325 abschließen (ENTSCHEIDUNG nötig)

**Option A (empfohlen): ProGuard-Narrowing doch bauen (S) + Geräte-Smoke-Test.**
3 Blanket-Keeps (:46–52) durch gezielte Keeps der 6 realen Klassen ersetzen; PdfObject-
Reflection-Guard (:59–61) behalten; `-printusage`/`-printseeds` temporär für Evidenz, APK-Delta
im Commit dokumentieren. **Merge-Gate: User-Smoke-Test auf echtem Gerät** (Multi-Page-PDF
erzeugen + rendern) mit dem Internal-Build — Deploy geht ohnehin erst auf Internal Track.
→ danach #325 schließen.

**Option B: #325 ohne Code schließen** — Rationale: #302 gelandet, #145 bewusst not_planned
(Risiko/Nutzen: nur APK-Größe vs. Release-only-PDF-Korruptionsrisiko). 1 Kommentar, 0 Aufwand.

---

### Wave 5 — #296 Feature-TODOs (OPTIONAL, Feature- nicht Bugfix-Arbeit)

Nur nach User-Freigabe (neue Features → Rückfrage-Pflicht): **PR-G** mit den 3 unblocked
TODOs — LabelsScreen `isCreating` (S), UploadComponents DatePicker (S),
StepByStepMetadataScreen Tag-Dialog (M, CreateEntityDialog-Muster). Die 3 premium-/produkt-
gated Sites (Billing-Tier ×2, OCR-Extraktion) bleiben im Tracker bis Produktentscheid.
#296 bleibt offen, schrumpft aber auf die gated Hälfte.

---

### Deferred (bewusst NICHT in dieser Runde)

- **Dependabot #13–17** (cameraX 1.4.1→1.5.2, workManager 2.10→2.11, firebase-bom,
  kotlinx-coroutines-test 1.9→1.10.2, ben-manes): runtime-riskant, CI-grün ≠ runtime-safe.
  Falls gewünscht: je Bump eigener PR + Geräte-QA-Session, beginnend mit den harmlosesten
  (ben-manes = reines Build-Tooling, coroutines-test = nur Test-Scope).
- **OCR-Extraktion** (`AnalyzeDocumentUseCase:82`): L, braucht ML-Kit-Text-Recognition-Pipeline
  + Produktentscheid (free vs. premium).

## 4. Empfohlene Reihenfolge (Kurzfassung)

```
Wave 1 (PR-1 #334 ‖ PR-2 deps-batch)
  → Wave 2 (PR-A contracts → PR-B #239 → PR-C worker-light → PR-D UploadWorkerTest) → #321 zu
    → Wave 3 (PR-E signal → PR-F taxonomy, device-gated) → #320 zu
      → Wave 4 (#325: Option A narrowing+smoke ODER Option B schließen)
        → Wave 5 (optional, nach Freigabe: #296 unblocked-Trio)
Ende-Zustand: 0–2 offene Issues (#296 geschrumpft, ggf. #325 zu), Backlog faktisch leer.
```
