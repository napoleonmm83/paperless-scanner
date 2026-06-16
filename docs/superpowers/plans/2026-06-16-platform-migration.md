# Platform-Migration Plan — Kotlin 2.4 / compileSdk 36 / AGP 9

**Datum:** 2026-06-16
**Ausgangslage:** main @ v1.5.230, clean & green, 0 offene Issues
**Ziel:** Die drei zurückgestellten Dependabot-PRs entsperren — #382 (coil 3.0.4→3.5.0), #383 (kotlin 2.1.10→2.4.0), #384 (hilt 2.53.1→2.59.2)
**Analyse-Quelle:** Workflow `wf_58f08e90-470` (21 Agenten, 10 Recherche-Dimensionen, jede adversarial gegen Primärquellen verifiziert)

> **Leitprinzip (zweimal schmerzhaft gelernt):** Kein „drop-in safe"-Changelog-Read wird geglaubt. Jede Stage wird durch `scripts/validate-ci.sh` (testReleaseUnitTest + assembleRelease + lintRelease) als hartes Gate bewiesen. Lokale Release-CI ist die einzige Wahrheit.

---

## 1. Kernerkenntnis: Die drei PRs teilen NICHT dieselbe Wand

Die ursprüngliche Annahme war „alle drei brauchen eine koordinierte Plattform-Session". **Verifiziert ist das Gegenteil — AGP 9 lässt sich sauber isolieren:**

| Bump | Echter Floor (verifiziert) | Braucht AGP 9? |
|------|---------------------------|----------------|
| **compileSdk 36** | AGP ≥ 8.9.0-rc01; AGP 8.13 unterstützt API bis 36.1 | ❌ **Nein** — läuft auf 8.13.2 |
| **Coil 3.5.0** | compileSdk 36 **+ kotlin-stdlib 2.4.0** (transitiv!) | ❌ Nein |
| **Kotlin 2.4.0** | min AGP 8.5.2, Gradle 7.6.3–9.5.0 | ❌ Nein — 8.13.2/8.13 liegen im Fenster |
| **Hilt 2.59.2** | **AGP 9 + Gradle 9.1** (Plugin-scoped: das Hilt-Gradle-Plugin erzwingt es) | ✅ **Ja — der einzige** |

**Konsequenz:** Coil (#382) + Kotlin (#383) shippen **komplett auf AGP 8.13.2 / Gradle 8.13**. Nur Hilt 2.59.2 braucht AGP 9 — und Hilt kann bis dahin gefahrlos auf **2.58** (letztes AGP-8-kompatibles Dagger-Release) vorrücken. Damit wird das riskanteste Stück (AGP 9 + Gradle 9.1 + R8-Default-Flips + built-in-Kotlin-DSL) aus dem kritischen Pfad herausgelöst.

### Wichtige Verifikations-Korrekturen (was die Erst-Recherche falsch hatte)

1. **Coil-3.5.0-Floor war UNTERSCHÄTZT:** Coil 3.5.0 pinnt laut offiziellem Changelog auf **Kotlin 2.4.0 + compileSdk 36**, nicht „nur 2.1.20+". Es gibt **keine** stabile-detekt-kompatible Kotlin-Version, die auch Coil 3.5.0 erfüllt.
2. **mockk-Versionen waren falsch:** 1.14.4 zielt nur auf Kotlin 2.1.20 (zu niedrig). Für Kotlin 2.4 → höchstes 1.14.x (**1.14.11**).
3. **detekt-Zahlen leicht daneben:** stabiles detekt = 1.23.8 (auf Kotlin 2.0.x, liest Metadaten nur bis ~2.1). detekt 2.0 existiert nur als **2.0.0-alpha.4**.

---

## 2. Die echte bindende Beschränkung: detekt

Das ist NICHT AGP und NICHT Kotlin selbst — es ist **detekt**:

- Stabiles **detekt 1.23.8** liest Kotlin-Metadaten nur **bis ~2.1**. Auf einem Kotlin-2.4-/Coil-3.5-Classpath kann es nicht laufen.
- Die einzige detekt-Linie, die Kotlin 2.4 unterstützt, ist **2.0.0-alpha.4**.
- detekt ist ein **hartes Per-PR-CI-Gate** (`validate-ci.sh` + `android-ci-optimized.yml` fahren `:detekt-rules:test` + `:app:detektComposeRules`).
- Das eigene **`:detekt-rules`-Modul** (4 Rule-Files + 4 Tests + ServiceLoader-Ressource + build.gradle.kts) muss bei detekt 2.0 umgebaut werden:
  - Maven-Group `io.gitlab.arturbosch.detekt` → **`dev.detekt`** (Plugin-ID + `detekt-api`/`detekt-test`-Deps)
  - Package `io.gitlab.arturbosch.detekt.api.*` → **`dev.detekt.api.*`**
  - Ressource `META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` → `…dev.detekt.api.RuleSetProvider`
  - `Rule`/`CodeSmell`/`Issue`-API → 2.0 `Finding`-Familie (exakte `report()`-Signaturen **unverifiziert** → durch Kompilieren gegen das Alpha beweisen)

→ **Diese Entscheidung (Alpha auf Prod-CI akzeptieren?) muss VOR der Kotlin-Zahl fallen.** Siehe §5.

---

## 3. Der gestaffelte Plan (4–5 separate PRs, KEIN Big-Bang)

> Jede Stage = ein shippbarer PR, einzeln durch `validate-ci.sh` gegated. Staffeln trennt Compiler-Lockstep-Fehler von Coil/Hilt-Fehlern → bisektierbar (genau die dokumentierte Failure-Mode).

### Stage 0 — *(optional)* Coil 3.0.4 → 3.1.0 (Null-Toolchain-Drop-in)

- **Ziel:** Coil-Call-Site-Risiko aus dem großen PR herausziehen.
- **Änderung:** `coil = 3.1.0` in `libs.versions.toml`. 3.1.0 ist das höchste Coil, dessen stdlib-Floor exakt das Projekt-Kotlin 2.1.10 ist. compileSdk bleibt 35. Keine Code-Edits (App nutzt nur `AsyncImagePainter.State.Error`, eigenen Hilt-`ImageLoader`, `ImageRequest.Builder/crossfade` — alle unverändert).
- **Risiko:** niedrig. **14 Coil-Files** bleiben unberührt.

### Stage 1 — Hilt 2.53.1 → 2.58 (AGP-8-Decke)

- **Ziel:** Den Großteil von #384 als Drop-in auf der aktuellen Toolchain liefern.
- **Änderung:** `hilt = 2.58` (deckt hilt-android, hilt-compiler, hilt-android-testing + das `com.google.dagger.hilt.android`-Plugin via version.ref). **`androidx.hilt`-Artefakte (hilt-navigation-compose, hilt-work, hilt-work-compiler) BLEIBEN auf 1.2.0** — eigenständig versioniert, NICHT auf die Dagger-Version aligned.
- **Achtung:** 2.58 schaltet `dagger.useBindingGraphFix` per Default ein → ein bisher tolerierter Duplicate/Ambiguous-Binding würde erst beim Compile auffallen. Graph ist konventionell (nur 2 @InstallIn/@EntryPoint-Sites) → erwarteter Impact nil, aber beweisen.
- **Risiko:** niedrig. Eigener kleiner PR, unabhängig, kann zuerst landen.

### Stage 2 — compileSdk 35 → 36 (targetSdk BLEIBT 35)

- **Ziel:** Die SDK-Hälfte von Coils Wand auf AGP 8.13.2 räumen, ohne Runtime-Verhaltensänderung.
- **Änderung:** `compileSdk = 36` (eine Zeile in `app/build.gradle.kts`). **`targetSdk` bleibt 35.** Ggf. `lint-baseline.xml` neu generieren, falls API-36-Stubs neue Deprecation-Warnungen bringen (`lintRelease` hat `abortOnError=true`).
- **Warum targetSdk 35 bleibt:** Alle Android-16-Verhaltensänderungen (Edge-to-Edge-Enforcement, Predictive-Back-Default, Large-Screen-Orientation-Enforcement) sind **targetSdk-36-gegated**. `AndroidManifest.xml` deklariert `screenOrientation`, es gibt `BackHandler`-Nutzung → der targetSdk-36-Audit ist eine **separate spätere Session mit Geräte-Test**, hier explizit out-of-scope.
- **Voraussetzung:** SDK Platform 36 + Build-Tools 36.x auf dem self-hosted Windows-Runner (verifiziert vorhanden: android-36, build-tools 36.1.0) **und** auf dem ubuntu-PR-CI-Image (prüfen!).
- **Risiko:** niedrig.

### Stage 3 — **KEYSTONE:** Kotlin 2.4.0 + KSP2 + Tooling + Coil 3.5.0

- **Ziel:** Die gesamte Compiler-Toolchain auf Kotlin 2.4.0, KSP1→KSP2-Migration, Kotlin-gekoppeltes Test/Lint-Tooling refreshen, Coil 3.5.0 landen — **alles OHNE AGP 9.** Schließt **#382 + #383 vollständig.**
- **Änderungen (alle im selben Commit, weil compiler-version-locked):**
  - `kotlin = 2.4.0` (compose-compiler-Plugin trackt automatisch via `version.ref=kotlin` — kein Extra-Edit)
  - `ksp = 2.3.9` (decoupled KSP2; das alte `2.1.10-1.0.29`-Format ist toter Hard-Blocker für Kotlin ≥2.3; treibt **hilt-compiler UND room-compiler**)
  - **PFLICHT-Workaround** für den Kotlin-2.4.0-Module-Name-Doppelpunkt-Bug (KSP #2964, offen, kein Release-Fix): `kotlin { compilerOptions.moduleName(project.name) }` in `app/build.gradle.kts` **und** `detekt-rules/build.gradle.kts`. Ohne ihn schlägt KSP+Hilt/Dagger-Codegen fehl: `Can't escape identifier …:implFactory … illegal characters: :`
  - Den toten KSP1-`byRounds`-JavaCompile-Exclude-Hack (`app/build.gradle.kts` ~Z. 181–193) entfernen (unter KSP2 obsolet; harmlos falls belassen)
  - `coil = 3.5.0` — jetzt beide Floors erfüllt (compileSdk 36 aus Stage 2 + stdlib 2.4.0). Keine App-Code-Edits.
  - `robolectric = 4.16` (Pflicht für SDK 36; 30 Test-Files). Daemon-JDK 21 reicht. Prüfen ob der `forkEvery=1`-Native-Font-Race-Workaround unter 4.16 (`ResourcesMode.NATIVE`) noch nötig ist.
  - `mockk = 1.14.11` (1.14.4 zielt nur auf Kotlin 2.1.20; höchstes 1.14.x nehmen; 70 Test-Files). `mockkStatic(Log)`-UnsatisfiedLinkError-Pattern gegenprüfen.
  - `detekt = 2.0.0-alpha.4` — **erforderlich** (s. §2). Group/Package/Services-Rename + Rule/Finding-API-Rewrite aller 4 Rules + 4 Tests. **Durch Kompilieren von `:detekt-rules` gegen das Alpha beweisen.**
  - **Room BLEIBT 2.8.4** (läuft mit Kotlin 2.4 via KSP2; nur das KSP-Re-Pin zählt). `:app:kspReleaseKotlin` + Schemas regenerieren → KSP2-Codegen-Regressionen fangen.
  - **ktlint-Plugin BLEIBT 12.1.2** (decoupled, eigener Classloader; optionales 14.x deferred → Formatting-Churn vermeiden).
  - **Compose BOM BLEIBT 2024.12.01** (decoupled vom compose-compiler/Kotlin-Bump; kein Runtime-Floor für Kotlin 2.4. Optionaler Refresh = separater Follow-up-PR, muss < Compose 1.12.0 bleiben — das bräuchte compileSdk 37 + AGP 9).
  - `paparazzi = 2.0.0-alpha04` (1.3.4 liest entfernte `BaseExtension`; kein stabiles Release unterstützt Kotlin 2.1+. alpha04 bundlet Kotlin 2.3.0/AGP 8.13.2/LayoutLib 16.1.1. **Screenshot-Tests sind `@Ignore`'d → Golden-Diff-Risiko ~0; Risiko = nur Plugin-Apply/Build.** alpha04 bundlet Kotlin 2.3.0 → toleranz gegen Projekt-Kotlin 2.4.0 beim Build beweisen.)
- **Voraussetzung:** Stage 2 gemerged/gefolded; detekt-Alpha-Entscheidung (§5) getroffen.
- **Risiko:** **hoch.** Das ist der echte Plattform-Sprung. Jeder Sub-Bump MUSS einzeln grün durch `validate-ci.sh` bewiesen werden. **AGP bleibt 8.13.2 / Gradle 8.13. Hilt bleibt 2.58.**

### Stage 4 — *(OPTIONAL, höchstes Risiko, deferrable)* AGP 9 + Gradle 9.1 → Hilt 2.59.2

- **Ziel:** Die AGP-9/Gradle-9-Wand allein für den finalen Hilt-Schritt 2.58→2.59.2 (#384 wie eingereicht) überqueren.
- **Änderungen:**
  - `AGP 8.13.2 → 9.0.1` (Einstieg) oder `9.2.0` (latest); Gradle-Wrapper `8.13 → 9.1.0` (für AGP 9.0) bzw. `9.4.1` (für AGP 9.2)
  - **kotlin-android-Plugin entfernen:** `alias(libs.plugins.kotlin.android)` aus `app/build.gradle.kts` + apply-false in `build.gradle.kts` droppen (AGP 9 built-in Kotlin ist default-on; kotlin-android inkompatibel mit der neuen DSL). `kotlinOptions{ jvmTarget=17 }` → `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` oder droppen (Default = compileOptions-Target 17).
  - `hilt = 2.59.2` (direkt .2 — plain 2.59 hat den #5099 ComponentTreeDeps-Packaging-Bug unter AGP 9)
  - `paparazzi → 2.0.0-alpha05` (alpha04 ist AGP-8.13-only; alpha05 zielt auf AGP-9-Ära — AGP-9-Support durch Build beweisen)
  - detekt: `android.builtInKotlin=false` in `gradle.properties` (detekt-2.0-alpha verliert Variant-Tasks wie `detektRelease` unter AGP-9-built-in-Kotlin, #8908) ODER im gewählten Alpha als gefixt bestätigen
  - ktlint-Plugin `12.1.2 →` AGP-9-built-in-Kotlin-kompatibles Release (12.1.2 erzeugt keine `ktlint*SourceSet`-Tasks unter built-in Kotlin, #1008)
  - `gradle-versions 0.53.0 → 0.54.0`; `dependencyUpdates` mit `--no-parallel`
  - **R8/minify-Revalidierung:** AGP **9.0** (nicht 9.2) schaltet `optimizedResourceShrinking` + `strictFullModeForKeepRules` auf true und ändert `-keepattributes`-Wildcard-Semantik (`-keep class A` impliziert nicht mehr den Default-Konstruktor). Release hat minify+shrinkResources an mit reflection-heavy Deps (Retrofit/Gson, Hilt, Room, Firebase, iText, billing) → **`proguard-rules.pro` reviewen + voller `assembleRelease` + manueller On-Device-Smoke-Test.**
- **Voraussetzung:** Stage 3 gemerged; paparazzi-alpha05-AGP-9-Support per Build bestätigt; Entscheidung „AGP 9 jetzt vs. später" (§5).
- **Risiko:** **hoch.** Strikt optional & isolierbar. Nichts außer Hilt 2.59.2 braucht AGP 9. **Gate = voller `validate-ci.sh` PLUS manueller On-Device-Smoke-Test** (wegen R8-Flips).

---

## 4. Tooling-Bumps in Lockstep (Übersicht)

| Tool | Von | Nach | Stage | Warum |
|------|-----|------|-------|-------|
| KSP | 2.1.10-1.0.29 | **2.3.9** | 3 | KSP2; altes Format = Hard-Blocker für Kotlin ≥2.3 |
| compose-compiler | 2.1.10 | 2.4.0 (auto) | 3 | trackt kotlin via version.ref |
| detekt + :detekt-rules | 1.23.7 | **2.0.0-alpha.4** | 3 | einzige Linie mit Kotlin-2.4-Support; Group/Package/API-Rewrite |
| Robolectric | 4.14.1 | **4.16** | 3 | erste mit SDK-36-Shadows |
| mockk | 1.13.13 | **1.14.11** | 3 | Kotlin-2.4-Metadaten |
| paparazzi | 1.3.4 | 2.0.0-alpha04 → alpha05 | 3 → 4 | kein stabiles Release > Kotlin 2.1 |
| ktlint-Plugin | 12.1.2 | 12.1.2 → AGP9-kompat. | (4) | decoupled; nur bei AGP 9 nötig |
| Compose BOM | 2024.12.01 | unverändert | — | decoupled; optionaler Follow-up |
| Hilt/Dagger | 2.53.1 | 2.58 → 2.59.2 | 1 → 4 | 2.58 = AGP-8-Decke; 2.59 erzwingt AGP 9 |
| Gradle | 8.13 | 8.13 → 9.1.0/9.4.1 | (4) | nur AGP 9 erzwingt es |
| AGP | 8.13.2 | 8.13.2 → 9.0.1/9.2.0 | (4) | nur Hilt 2.59.2 braucht es |

**JDK:** Kein Bump. compileOptions 17 + jvmTarget 17 bleiben gültig; AGP-9-Floor = 17; Gradle 9 läuft auf 17–26; Robolectric-SDK36 braucht JDK 21 → Daemon-JDK 21 erfüllt alles.

---

## 5. Getroffene Entscheidungen (2026-06-16, Marcus bestätigt)

1. **Scope — Stage 4 (AGP 9 + Hilt 2.59.2): ✅ DEFERRED.** Stages 0–3 shippen jetzt (#382 + #383 vollständig zu, Hilt auf 2.58). AGP 9 + Hilt 2.59.2 = separate spätere Session, wenn paparazzi-AGP9 + ktlint-AGP9 stabil sind.

2. **Kotlin-Ziel — ✅ 2.4.0 + `compilerOptions.moduleName(project.name)`-Workaround.** Coil 3.5.0 pinnt auf stdlib 2.4.0; KSP 2.3.9. (2.3.20 verworfen — würde Coil bei 3.4.0 stoppen und #382 verfehlen.)

3. **detekt-Pfad — ✅ detekt 2.0.0-alpha.4 adoptieren** + voller `:detekt-rules`-Rewrite (`dev.detekt`). Fallback falls Alpha auf CI flaky: Custom-Rules-Gate temporär deaktivieren (NICHT Kotlin-Bump aufgeben).

4. **Stage 0 (Coil 3.1.0 Pre-Step) — ✅ WIRD GEMACHT.** Billiges De-Risking vor dem Keystone-PR.

**→ Auszuführende Reihenfolge:** Stage 0 → Stage 1 → Stage 2 → Stage 3 (Keystone). Stage 4 nicht in diesem Durchlauf.

---

## 6. Kritisches Risiko-Register (Build-Time-Landminen)

| Risiko | Mitigation |
|--------|-----------|
| **Kotlin-2.4.0-Module-Name-Doppelpunkt** (KSP #2964, offen) bricht Hilt/Room-Codegen | PFLICHT: `compilerOptions.moduleName(project.name)` in app + detekt-rules; `assembleRelease` grün beweisen |
| **Stiller KSP1→KSP2-Engine-Swap** (hilt+room+hilt-work+kspAndroidTest) | Wie Migration behandeln: `kspReleaseKotlin` + voller Test + assembleRelease; Room-Schemas regenerieren; byRounds-Hack entfernen |
| **detekt 2.0 ist ALPHA + hartes CI-Gate** + ungeprüfte 2.0-`report()`-Signaturen | Pfad VOR Kotlin-Zahl entscheiden; bei Adoption :detekt-rules gegen Alpha kompilieren beweisen |
| **mockk/Robolectric-Floors** (70+30 Files, nur zur Test-Compile-Zeit) | mockk 1.14.11 + Robolectric 4.16 im selben Stage-3-Commit; testReleaseUnitTest bestätigt |
| **Stage-4 R8/minify-Regression** (AGP 9.0 Default-Flips, minify an, reflection-heavy) | proguard-rules.pro reviewen; voller assembleRelease + On-Device-Smoke-Test |
| **Hilt-2.58-BindingGraphFix-Flip** | Stage 1 via validate-ci.sh gaten |
| **compileSdk-36-Lint-Regression** (abortOnError=true) | lint-baseline.xml ggf. neu generieren; SDK 36 auf ubuntu-CI bestätigen |
| **targetSdk-Drift** (36 würde Predictive-Back/Orientation stumm aktivieren) | targetSdk = 35 in ALLEN Stages halten; targetSdk-36-Audit = separate Session |

---

## 7. Empfohlene Reihenfolge

```text
(Stage 0 coil 3.1.0)  →  Stage 1 hilt 2.58  →  Stage 2 compileSdk 36
   →  Stage 3 KEYSTONE (Kotlin 2.4 + KSP2 + Tooling + coil 3.5.0)   [schließt #382 + #383]
   →  (Stage 4 OPTIONAL: AGP 9 + Gradle 9.1 + hilt 2.59.2)          [schließt #384]
```

Stages 0–2 sind low-risk, unabhängig, in beliebiger Reihenfolge landbar und verkleinern die Keystone-PR-Fläche. Stage 3 ist die unvermeidbare große koordinierte Änderung — überquert aber **keine AGP-Wand**. Stage 4 ist genuin optional und deferrable.

**Jede Stage: PR + lokale CI grün + `codex review` + CodeRabbit (Projekt-Workflow). Stage 4 zusätzlich: On-Device-Smoke-Test.**
