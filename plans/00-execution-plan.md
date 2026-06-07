# 00 — Master Execution Plan (Stand 2026-06-05)

> Reconciled, code-verifizierter Ausführungsplan für die **letzten offenen Issues**.
> Erstellt durch Phase-0-Discovery (10 Agenten, jeder Design-Doc gegen den echten Code/Git-Stand
> auf `origin/main` @ `4c775045` geprüft). Dieser Plan **ersetzt die Wellen-Sequenz in `README.md`**,
> weil diese teils veraltet ist (siehe „Roadmap-Korrekturen"). Die `0X-*.md` Design-Docs bleiben die
> Detail-Specs; dieses Dokument ist die Reihenfolge + die Stolperfallen-Leitplanken pro Phase.

## 1. Reconciled Status — was *wirklich* noch offen ist

| Plan | Master | Reststand (verifiziert) | Aufwand | Risiko | Blockiert? |
|------|--------|--------------------------|---------|--------|------------|
| plan-04 | #322 | Runtime-Fixes **gemerged** (#327); nur **detekt-Regeln + detekt blockierend** fehlen | M (S ohne Custom-Rule) | mittel | nein |
| plan-08 | #45 | Factories **vorhanden**; nur **KDoc + detekt-Route-Regel + Test** fehlen | M (S ohne Custom-Rule) | niedrig | nein |
| plan-05 | #323 | Online-Pfad **gemerged** (#65/#126); **Offline-Tag-Count (#65)** + **#66 kdoc** fehlen | M | mittel | nein |
| plan-06 | #324 | 3 unabhängige Slices (#82, #142, #50) — alle offen | L | mittel | nein |
| plan-01 | #319 | Komplett offen, sequenziert **#48 → #153 → #132** | L | mittel | nein |
| plan-03 | #321 | Komplett offen (Interface-Extraktion → #239, #202) | XL | mittel | nein |
| plan-02 | #320 | Komplett offen (**#303 → #37**); #37-Krypto-Pfad nur am Gerät voll testbar | L | **hoch** | nein* |
| plan-07b | #325 | #302 **gemerged** (#328); nur **#145 ProGuard-iText** offen | M | mittel | **ja** (Geräte-QA) |
| plan-10 | #287 | #128-Hardening **gemerged**; **Upstream-API-Investigation-Gate** offen | S (NO) / L–XL (YES) | niedrig | **ja** (externe API) |

`#296` bleibt eigenständiger Umbrella-Tracker (verschobene Feature-TODOs) — **kein** Plan, nicht hier.

\* plan-02 ist nicht extern blockiert, aber der echte `AEADBadTagException`-Entschlüsselungspfad ist
unter JVM/Robolectric **nicht** testbar → braucht einen dokumentierten Real-Device-Test.

## 2. Roadmap-Korrekturen (`README.md` ist stale — vor Ausführung beachten)

- **Wave 0 ist KOMPLETT.** `README.md` Z. 15/20/27-28 behauptet plan-04 (#264/#266) und plan-09 (#307)
  lägen „done on local branch — land it" auf un-gemergten Branches. **Falsch:** beide sind via
  Squash-PRs **#327** (`24436128`) und **#326** (`9074130b`) auf `main`. Einziger Rest von Wave 0 =
  die plan-04-**detekt-Regel** (Wave B1/B2 unten).
- **Zwei verwaiste lokale Branches** (`fix/a11y-home-polish`, `fix/scan-shared-images-cleanup`) sind
  obsolet (Inhalt squash-gemerged, Remote serverseitig gelöscht) → **löschen**, nicht rebasen. Sie
  erscheinen NICHT in `git branch --merged` (Squash erzeugt neue SHAs) — Verifikation per Inhalt, nicht Ancestry.
- **detekt ist aktuell wirkungslos in CI:** `android-ci-optimized.yml:296/301` läuft `gradlew detekt`
  mit `|| exit 0` / `|| true`. Auch `scripts/validate-ci.sh` (der Pre-Push-Gate) ruft detekt **gar nicht** auf.
  → Jede neue detekt-Regel braucht zusätzlich das Schärfen dieses Gates, sonst blockiert sie nichts.
- **`config/detekt/baseline.xml`** wird in `app/build.gradle.kts:323` referenziert, **existiert aber nicht**
  auf der Platte → bei detekt-Arbeit entweder anlegen oder die Zeile entfernen.
- Lokales `main` ist 1 Commit hinter `origin/main` — **`origin/main` ist die Wahrheit**.

## 3. Shared-Infra-Erkenntnis (Sequenzierungs-Hebel)

plan-04 **und** plan-08 brauchen dieselbe nicht-existente Infrastruktur: ein **Custom-detekt-Ruleset-
Gradle-Modul** (`RuleSetProvider` + `Rule`-Klassen) plus **blockierendes detekt in CI**. Heute gibt es
weder ein Custom-Modul, noch eine `detektPlugins`-Dependency, noch einen `RuleSetProvider` im Baum.
→ **Modul EINMAL bauen (Wave B1), dann tragen plan-04 und plan-08 nur noch je eine Regel ein.** Das
senkt beide von M auf ~S. Falls ein leichteres ktlint/Regex-Gate akzeptabel ist, entfällt das Modul ganz.

## 4. Ausführungs-Wellen (re-sequenziert nach ROI × Abhängigkeit)

Jede Phase ist als **eigener frischer Kontext** ausführbar. Pflicht-Gate pro PR:
`git rebase origin/main` → `scripts/validate-ci.sh` grün → push. **COPY** aus den Design-Docs, nicht „migrieren".

---

### Wave A — Housekeeping (S, 0 Risiko, sofort)

**Was:** Repo-Hygiene + Roadmap entstauben.
1. Lokales `main` auf `origin/main` synchronisieren (`git fetch --all --prune`, fast-forward).
2. Obsolete Branches löschen: `git branch -D fix/a11y-home-polish fix/scan-shared-images-cleanup`.
3. `README.md` korrigieren: Status-Spalte plan-04/plan-09 = „landed (#327/#326)", Wave-0-Block als erledigt
   markieren, Verweis auf dieses `00-execution-plan.md` ergänzen.

**Verifikation:** `git branch` zeigt die zwei Branches nicht mehr; `git log origin/main..main` ist leer.
**Anti-Pattern-Guard:** Branches NICHT rebasen/mergen — Inhalt ist schon auf main; das 1-ahead-Commit ist der redundante Pre-Squash-Stand.

---

### Wave B — Enforcement-Infra + Doc/Quick-Wins (low risk, hoher ROI)

#### B1 — detekt-rules-Modul + detekt blockierend  *(Shared-Infra für B2 & B3)*
**Was:** Neues Gradle-Modul `config/detekt/rules` (oder `detekt-rules/`) mit `RuleSetProvider`. `detektPlugins(project(...))`
in `app/build.gradle.kts` (detekt-Block Z. 319-325). `baseline.xml` anlegen ODER die Referenz Z. 323 entfernen.
CI scharf stellen: in `android-ci-optimized.yml:296/301` das `|| true` / `|| exit 0` entfernen.
**Verifikation:** `./gradlew detekt` schlägt bei einer Test-Fixture-Verletzung fehl (heute no-op).
**Guard:** Stock-detekt kann diese Compose-UI-Invarianten **nicht** ausdrücken — Custom-Rule ist Pflicht (oder Lint).

#### B2 — plan-04 Enforcement (#322)  → Design-Doc `04-a11y-styleguide.md`
**Was:** Zwei Custom-Regeln ins B1-Modul: `TouchTargetSize` (clickable ohne vorangestelltes
`.minimumInteractiveComponentSize()` an Box/Row/Column) + `LabelLetterSpacingOverride` (inline `letterSpacing=` in Label-TextStyles).
Compliant-Vorlage: `PageThumbnail.kt:123-124/151-152`. Exempt: `PageThumbnail.kt:86/235` (Full-Image / 160dp-Card).
Prüf-/Entscheide-Sites: `WidgetConfigActivity.kt:197/251` (0.1.sp inline), `DiagnosticsScreen.kt:353` (Token-Referenz → nicht flaggen).
**Verifikation:** detekt FAILt bei `Box.clickable` < 48dp, PASSt auf PageThumbnail-Muster.
**Guard:** Runtime-Defekte sind **schon gemerged** (#327) — KEINE Laufzeit-Fixes mehr, nur Enforcement.

#### B3 — plan-08 Typed Navigation (#45)  → Design-Doc `08-typed-navigation.md`
**Was:** (1) Klassen-KDoc über `Screen.kt:5` (Factory-Pflicht, Beispiele `Scan/Upload/PdfViewer.createRoute`).
(4) `Uri.encode` (Z. 14/29/52/78/83/89) über einen Top-of-File-Helper konsolidieren — **PdfViewer encodet nur den
Titel, nicht die id; Scan-Pipe-Delimiter nicht encoden**. (3) detekt-Regel `RawRouteString` (Literal-Arg an `navigate(...)`
mit Route-Form) ins B1-Modul. (5) `ScreenTest.kt` neu (Route-Form-Asserts).
**Verifikation:** `rg 'navigate\("' app/src/main/java/` = 0; `./gradlew testDebugUnitTest --tests '*ScreenTest*'` grün.
**Guard:** `android.net.Uri.encode` ist unter purem JVM ein No-op → Test ggf. Robolectric. `AppLockNavigationInterceptor`
nur **auditieren**, nicht umschreiben; dessen `targetRoute`-Variable darf die neue Regel nicht flaggen. ⚠️ #45 war am Jun-4 (C3)
triage-deferred — vor Ausführung bestätigen, dass der Slice noch gewünscht ist.

#### B4 — plan-05 #66 KDoc-Sweep (S, reine Docs)  → Design-Doc `05-docrepo-integrity.md`
**Was:** Standardisierten `**CACHE & REFRESH POLICY:**`-Block (TTL/Refresh-Trigger/Soft-Delete/Pending-Change) auf die
Repos ohne Cache-Contract-kdoc setzen. `DocumentTypeRepository.kt:24` hat **gar kein** kdoc. Vorlage: `TagRepository.kt:28-68`.
`TagRepository`/`CorrespondentRepository` sind die Referenzen (nur Methoden-Einzeiler prüfen, nicht neu schreiben).
**Verifikation:** `grep 'CACHE' data/repository/*.kt` trifft alle 9 Repos.

---

### Wave C — Daten-Integrität & Coroutine-Hygiene (mittel)

#### C1 — plan-05 #65 Offline-Tag-Count-Atomarität  → Design-Doc `05-docrepo-integrity.md`
**Was:** `SyncManager` zwei Ctor-Params geben: `db: AppDatabase` + `serializer: DocumentSerializer` (fehlen beide).
In `pushDocumentChange` (`SyncManager.kt:489-494`, „update"-Branch) das `withTransaction`+Tag-Delta-Muster aus
`DocumentMetadataRepository.kt:105/118-126` **kopieren**; `getOldTagIds`-try/catch aus `:184-196` spiegeln.
Tests in `SyncManagerTest.kt` (extends `BaseRoomRepositoryTest`, echtes In-Memory-Room): Rollback- + Divergenz-Test
analog `DocumentMetadataRepositoryTest.kt:247-283 / 230-246`.
**Verifikation:** `rg 'withTransaction' SyncManager.kt` trifft jetzt; neue Tests grün; Online-Tests bleiben grün.
**Guard:** `pushDocumentChange` ist `private` → Test über den öffentlichen Sync-Pfad treiben (oder `@VisibleForTesting`).

#### C2 — plan-06 Coroutine-Hygiene (3 unabhängige PRs)  → Design-Doc `06-coroutine-hygiene.md`
Reihenfolge: **#82 → #142 → #50** (jede eigener PR).
- **#82 (read-timeout):** `withReadTimeout(...)` in `ApiExtensions.kt` neu (CancellationException durchreichen wie `safeApiCall`),
  4 `get*()`-Netzwerk-Branches wrappen (Tag/Correspondent/DocumentType/CustomField-Repo). **`READ_TIMEOUT_SECONDS` existiert
  schon** (`NetworkConfig.kt:19`) — NICHT neu anlegen. **Kein globaler OkHttp `callTimeout`** (würde adaptive Upload-Timeouts brechen).
- **#142 (scope-teardown):** `destroy()` auf `NetworkMonitor`/`ServerHealthMonitor` (mit `scope.cancel()`), `scope.cancel()` in
  `BillingManager.destroy():838` ergänzen, alle drei aus `PaperlessApp.kt` aufrufen. **Guard:** `ProcessLifecycleOwner` hat
  KEIN process-level `onDestroy` — realen Teardown-Seam wählen (`Application.onTerminate`/Best-Effort-Hook) und dokumentieren.
- **#50 (shareIn):** `observeCorrespondents`/`observeDocumentTypes` (rohe Room-Flows) `.shareIn(scope, WhileSubscribed(5000))`,
  CoroutineScope in die Repos injizieren. **Guard:** `CustomFieldRepository.observeCustomFields` ist **schon StateFlow** → shareIn
  redundant. `TagRepository:88-91` hat KEIN repo-level stateIn (das liegt in `TagSuggestionsViewModel:88-89`) — Doc ist hier falsch.
**Verifikation:** `grep 'fun destroy'`/`'scope.cancel'`/`'withReadTimeout'`/`'shareIn'` treffen; `grep 'callTimeout'` bleibt leer; Leak-/Timeout-/ShareIn-Tests grün.

---

### Wave D — Architektur (groß)

#### D1 — plan-01 Layer Boundary (#319)  → Design-Doc `01-layer-boundary.md`
Strikt sequenziert **#48 → #153 → #132**:
- **#48:** `ProtocolDetector.kt` (neu, `data/service/`) — `detectionClient`/`tryProtocol`/`verifyPaperlessWithDocumentsEndpoint`/
  `isPaperlessApiResponse` aus `AuthRepository.kt` herausziehen; die zwei Detection-`newCall()` (L282/L435) delegieren.
  **Guard:** die zwei NICHT-Detection-`newCall()` (L520/L762, echte Auth) **bleiben** in AuthRepository.
- **#153:** Domain-Modelle + Mapper (`ServerStatus`, `CustomField`, `domain/error/`-Paket für `PaperlessException`/`userMessage`/
  `ServerOfflineReason`), 9 ui/-Import-Sites umbiegen, `ServerStatusRepository.getServerStatus()` auf `Result<ServerStatus>`.
  Deprecated typealias-Shim in `data/api` lassen, damit Nicht-ui-Caller nicht in einem Commit brechen.
- **#132:** JSON-Round-Trip-Test gegen `@SerializedName` mit `GsonProvider.instance` (NICHT frisches Gson); Fixtures je DTO.
**Verifikation:** `rg 'com.paperless.scanner.data.api' app/.../ui/` = 0 nicht-deprecated Treffer (heute 17 über 9 Dateien, `HttpAllowlistInterceptor` ausgenommen); Round-Trip-Test grün.
**Guard:** Doc sagt „~24 ui-Sites" — real **17**. `getLocalizedMessage` nimmt `Context` (streift deferred #55) — Context-Variante behalten, `userMessage` an UI exposen.

#### D2 — plan-03 Test-Double-Foundation (#321)  → Design-Doc `03-test-interfaces.md`
**Phase 1 (harte Voraussetzung):** `*Contract`-Interfaces **adjazent** zu den 10 Impl-Klassen extrahieren, Hilt umverdrahten,
Produktions-Injektionsstellen auf Contracts umstellen, grün kompilieren. **Dann** parallel: **#239** (ServerHealthViewModelTest)
und **#202** (4 Worker-Tests) auf typed Fakes.
**Guards (Doc hat mehrere Fehler):** (a) Contracts liegen **adjazent**, nicht in `di/`. (b) `@Binds` bricht für die 3 bereits-
`@Provides`-Collaborators (UploadQueueRepository/SyncManager/TokenManager) → **Rückgabetyp** auf `*Contract` ändern, kein @Binds.
(c) `recordException` liegt auf **CrashlyticsHelper**, nicht AnalyticsService. (d) `pendingCount` ist **Flow**, nicht StateFlow.
(e) `fix/missing-worker-tests`-Warnung ist gegenstandslos (#139 via #201 gemerged). `SyncWorkerTest`→`data/sync/`, `WidgetUpdateWorkerTest`→`widget/`.
**Verifikation:** `compileDebugKotlin` löst alle Bindings; alle Tests grün; `rg 'mockk\(relaxed' <5 Dateien>` = 0; ~10 `*Contract.kt` + ~9 `Fake*.kt`.
**Guard:** `UploadWorkerTest` zuletzt/vorsichtig (30 Tests, 37KB, coVerify/spyk/mockkStatic). `SyncManager.stop()` + `DocumentRepository.upload*`-Signaturen vor Contract-Deklaration greppen.

---

### Wave E — Security-Hardening (hohes Risiko, sorgfältig)

#### E1 — plan-02 Token-Failure-Taxonomy (#320)  → Design-Doc `02-token-taxonomy.md`
Strikt sequenziell **Phase 1 (#303) → Phase 2 (#37)**:
- **Phase 1 (#303):** `@Volatile lastRecoveredCryptoFailure: AEADBadTagException?` in `SecureTokenStorage`; im Catch
  (`:44-49`) klassifizieren **bevor** `recoverCorruptedStorage` (`:77`) läuft — Wipe nur bei bestätigtem `AEADBadTagException`.
  `TokenManager.init` Signal-Flag setzen.
- **Phase 2 (#37):** `sealed TokenStorageResult` + `enum TokenStorageFailureKind` (in `TokenStorage.kt`, Muster `ServerHealthResult:58`),
  `getTokenResult`/`saveTokenResult`, Backup-Snapshot + **Restore-statt-Wipe**, `getTokenSync` pattern-matched. Alte API als deprecated Delegatoren.
**Verifikation:** `rg 'TokenStorageResult|classifyFailure'` trifft; Unit-Test beweist **kein** Wipe bei `KEYSTORE_UNAVAILABLE`; TokenManager-Mapping-Tests grün.
**Guards:** Child-Issues #303/#37 sind auf GitHub als `not_planned` **geschlossen** (konsolidiert, **nicht** implementiert) — Arbeit steht aus.
Der echte `AEADBadTagException`-Decrypt-Pfad ist unter Robolectric **nicht** testbar (Shadow entschlüsselt nicht) → Signal/Klassifikation
via Fake/Mock testen, **realen Device-Test dokumentieren** (adb-Keystore-Korruption + Neustart). Sicherheitskritischer Credential-Pfad → Backup/Restore sorgfältig reviewen.

---

### Deferred / Blocked (nicht autonom abschließbar)

#### plan-07b — #145 ProGuard-iText-Narrowing  → Design-Doc `07-tooling-hardening.md`  *(blockiert: Geräte-QA)*
Code machbar: `proguard-rules.pro:46-61` die 4 Blanket-`-keep com.itextpdf.**` durch gezielte Keeps der **6 real genutzten**
Klassen ersetzen (einziger Consumer: `PdfGeneratorService.kt:5-10`). `-printusage`/`-printseeds` temporär für Evidenz, dann entfernen.
**Blocker:** Pflicht-Geräte-Smoketest (Multi-Page-PDF erzeugen, Render/Crash prüfen) — nicht CI-automatisierbar.

#### plan-10 — #287 Upload-Idempotency  → Design-Doc `10-upload-idempotency.md`  *(blockiert: Upstream-API)*
**Investigation-Gate ZUERST:** Paperless-ngx `post_document/` auf Idempotency-Key/Dedup-Token prüfen (Docs + Server-Source),
YES/NO-Findings als Kommentar an #287. **NO (wahrscheinlich)** → `docs/KNOWN_ISSUES.md` **Sektion 8** (nicht 10!) + Cross-Link
in `UploadWorker.kt:317` + #287 als `wontfix` schließen (S). **YES** → `idempotencyKey` auf `PendingUpload` (Room-Migration!),
`@Header`/`@Query` in `PaperlessApi.kt:272-281` (NICHT DocumentRepository — Doc ist stale), durchfädeln (L/XL).

## 5. Empfohlene Reihenfolge (Kurzfassung)

```
A (housekeeping)
  → B1 (detekt-Modul) → B2 (a11y-Regel) + B3 (nav) ‖ B4 (kdoc)
    → C1 (offline tag-count) ‖ C2 (#82→#142→#50)
      → D1 (#48→#153→#132) ‖ D2 (interfaces → #239‖#202)
        → E1 (#303→#37, device-gated)
Parallel/deferred: plan-10 Investigation-Gate (cheap), plan-07b (device-QA)
```

Pflicht je PR: `git rebase origin/main` → `scripts/validate-ci.sh` grün → push.
