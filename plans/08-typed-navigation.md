# [plan-08] Typed Navigation Routes — kill untyped string concatenation

## Defect

Navigation routes in Compose are currently defined as raw string templates (e.g., `"scan?pageUris={pageUris}"`) and then instantiated by string concatenation at call sites. This pattern has no compile-time type safety: typos in parameter names fail silently (wrong query string = wrong route, no compiler error), URL encoding is manual and easy to skip (leading to silent routing failures on special characters), and there is no centralized record of "what parameters does Screen X accept."

The codebase has *partially* addressed this via factory methods on each Screen subclass (e.g., `Screen.Scan.createRoute(pageUris)`, `Screen.Upload.createRoute(documentUri)`). These methods exist at `/games/Git/paperless client/app/src/main/java/com/paperless/scanner/ui/navigation/Screen.kt` L12–92, and all call sites in `PaperlessNavGraph.kt` and `MainScreen.kt` use them (verified as of 2026-06-05). However, no lint rule prevents raw route literals from appearing at `navigate()` call sites, and the factory method pattern remains undocumented.

## Children

- #45 — Routes like `"scan?pageUris={pageUris}"` are strings; missing URL-encoding fails silently, typos compile; typed factory methods already exist but not enforced (open)

## Fix sequence

1. **Document the sealed-class factory-method pattern** on Screen.kt: add a KDoc block to the Screen sealed class explaining that all routes must be instantiated via factory methods, not string literals. Cite examples: `Screen.Scan.createRoute(pageUris)`, `Screen.Upload.createRoute(documentUri)`, `Screen.PdfViewer.createRoute(documentId, documentTitle)`. Include the rationale: URL encoding is centralized, parameter types are checked at compile time, and typos in factory names fail loudly.

2. **Audit remaining raw route calls** (if any) for compliance. Current scan of `navigate(` call sites in `/games/Git/paperless client/app/src/main/java/com/paperless/scanner/ui/navigation/PaperlessNavGraph.kt` (L77, 127, 133, 139, 145, 151, 175, 179, etc.) and `/games/Git/paperless client/app/src/main/java/com/paperless/scanner/ui/screens/main/MainScreen.kt` (L32, 45, 48, 52, 55, 58, 61, 80, 86, 89) show all use either `Screen.<Name>.route` (for screens with no parameters) or `Screen.<Name>.createRoute(…)` (for screens with parameters); AppLockNavigationInterceptor.kt L239 uses a reconstructed targetRoute from SavedStateHandle, which is correct. No raw literals detected; commit this finding.

3. **Add a lint rule (detekt custom rule)** forbidding raw string literals in `navigate(…)` calls. The rule should match patterns like `navigate("screen?param={param}")` and suggest using the Screen factory method instead. Integrate into the project's existing detekt config (referenced in app/build.gradle.kts L10, already using alias for detekt plugin). Document the rule in the project's lint configuration.

4. **Consolidate URL encoding into Screen.kt factory methods** as a reusable pattern: all factory methods should use `android.net.Uri.encode(…)` consistently. Current methods do this (e.g., L14, 29, 52, 78, 83, 89); verify no future routes bypass it by making Uri.encode a helper function at the top of Screen.kt or by annotating all factory methods with a custom @UrlEncoded marker (optional, but improves discoverability).

5. **Defer Navigation 2.8 @Serializable variant**: Jetpack Navigation 2.8+ supports typed route arguments via @Serializable annotation on data classes, which would replace the factory-method pattern entirely. Current project uses navigationCompose 2.9.6 (gradle/libs.versions.toml L16) with composeBom 2024.12.01 (L15), which is new enough to support it. However, adoption requires bumping Compose BOM and rewriting route definitions; defer this to a future plan to keep scope tight. Note: factory methods provide the same type safety for now.

## Test matrix

| Axis | Case | Required behavior |
|---|---|---|
| Factory method signature | `Screen.Scan.createRoute(pageUris: List<Uri>)` | Returns `"scan?pageUris=<encoded-uris>"` with pipe-delimiter and `Uri.encode()` applied to each URI |
| Factory method signature | `Screen.Upload.createRoute(documentUri: Uri)` | Returns `"upload/<encoded-uri>"` with `Uri.encode()` applied |
| Factory method signature | `Screen.PdfViewer.createRoute(documentId: Int, documentTitle: String)` | Returns `"pdf-viewer/<id>/<encoded-title>"` with `Uri.encode()` applied to title only (id is numeric, no encoding needed) |
| No-parameter screen | `Screen.Home.route` | Returns `"home"` (no factory method needed, static route) |
| Lint rule violation | Call site: `navigate("custom-route")` where no Screen factory exists | Detekt rule reports: "Raw route string detected; use Screen.<Name>.createRoute() or Screen.<Name>.route instead" |
| Lint rule pass | Call site: `navigate(Screen.Home.route)` | No warning (screen constant) |
| Lint rule pass | Call site: `navigate(Screen.Scan.createRoute(uris))` | No warning (factory method) |
| AppLock interceptor | `reconstructRouteWithArgs()` in AppLockNavigationInterceptor.kt L28–94 | Correctly rebuilds route from SavedStateHandle arguments; special-cased for Scan (L62–77) and MultiPageUpload (L43–61) to read current URIs from AppLockRouteArgsHolder, not stale args |

## Reusable seams

- **Screen.kt sealed class** (`/games/Git/paperless client/app/src/main/java/com/paperless/scanner/ui/navigation/Screen.kt`) — all route definitions and factory methods live here; add documentation and any new patterns here first.
- **Uri.encode(…)** — already imported and used consistently in Screen.kt factory methods (lines 14, 29, 52, 78, 83, 89); consolidate as helper or mark with annotation if needed.
- **detekt config** (app/build.gradle.kts L10) — add custom rule for raw route literals here.
- **AppLockRouteArgsHolder** (`/games/Git/paperless client/app/src/main/java/com/paperless/scanner/ui/navigation/AppLockRouteArgsHolder.kt`) — holds single-source URI state for locked-screen route reconstruction; already integrated into AppLockNavigationInterceptor.kt L30, 45, 64.

## Out of scope

- **Navigation 2.8 @Serializable refactor**: Deferred to future plan; requires Compose BOM bump and full rewrite of Screen definitions. Current factory-method pattern provides type safety and centralized encoding.
- **Deep-link handling improvements**: Deep links in MainActivity.kt and PaperlessNavGraph.kt use DeepLinkAction enum (issue #48, separate plan); this plan focuses on compile-time route construction only.
- **Route template mismatch in popUpTo()**: PaperlessNavGraph.kt L533 comment notes that `navigate()` with `popUpTo` doesn't work on route templates (e.g., `"document/{documentId}"`) because instantiated routes don't match the template. This is a separate architectural issue (routing stack design) deferred to plan-XX.

