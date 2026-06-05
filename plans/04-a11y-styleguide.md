# [plan-04] a11y & Style-Guide Conformance — codify 48dp + label-typography tokens, enforce, fix sites

## Defect

The application's UI components lack consistent compliance with WCAG minimum touch target sizing (48dp) and Material Design 3 typography token standards. Currently:

- **Touch targets**: PageThumbnail rotate/delete controls are 28dp (below 48dp WCAG minimum), creating accessibility friction for motor-impaired users
- **Typography tokens**: labelSmall uses 0sp letterSpacing instead of 0.1em as specified by the style guide, applied inconsistently across tag chips, badges, and indicators throughout the Home screen
- **Affordance signals**: ProcessingTaskCard's disabled state (non-actionable tasks) uses a disabled-clickable Card variant instead of the non-interactive Card overload, masking true interactivity intent

These issues are symptomatic of a missing **codification + enforcement** layer: the style guide exists as documentation (Type.kt tokens, UI pattern docs) but detekt linting does not validate that layouts obey minimumInteractiveComponentSize, nor does CI prevent typographical regression.

A complete fix sequence must (1) land the unmerged local-only fix branch that addresses all three defects, (2) add a detekt rule to fail CI on clickables below 48dp, and (3) verify all screen metadata and button layouts conform before merging to origin/main.

## Children

- #264 — PageThumbnail rotate/delete controls are 28dp < 48dp WCAG minimum; need minimumInteractiveComponentSize wrapper (done-on-branch)
- #266 — Home style/a11y: Type.kt labelSmall letterSpacing 0sp vs 0.1em, tag-chip typography, LastSyncedIndicator live ticker, ProcessingTaskCard disabled-card affordance (done-on-branch)

## Fix sequence

1. **Rebase & validate fix/a11y-home-polish onto origin/main**
   - Check out fix/a11y-home-polish (commit 66bb4a17 — local-only, 1 commit ahead of and 9 behind origin/main)
   - Rebase onto latest origin/main
   - Verify all files compile: PageThumbnail.kt, Type.kt, HomeHeader.kt, ProcessingTasksSection.kt
   - Run unit tests on affected screens (ScanScreen, HomeScreen, ProcessingTasks)

2. **Create & land PR for a11y + style-guide fixes (Closes #264, #266)**
   - Push rebased branch to origin/feature/a11y-polish
   - Open PR with detailed commit message (already written in 66bb4a17)
   - Confirm CI passes (no detekt violations, no test failures)
   - Request code review focusing on: touch target sizing verification, typography token consistency, disability-affordance semantics
   - Merge once approved

3. **Add detekt rule: clickables must respect minimumInteractiveComponentSize (48dp minimum)**
   - Create new custom detekt rule in config/detekt/ (or extend existing) to scan for:
     - `.clickable(...)` modifiers on Box/Row/Column without preceding `.minimumInteractiveComponentSize()`
     - Custom click-handling patterns that bypass Material3 standards
   - Wire rule into CI: detekt.yml maxIssues: 0 (fail on any violation)
   - Add exception list for documented cases (e.g., visual micro-badges like icons inside fixed containers)
   - Run rule against codebase; fix any violations found before merging

4. **Verify all typography sites adopt 0.1em labelSmall letterSpacing**
   - Grep for `labelSmall` usage across all screen components
   - Confirm Type.kt is the single source of truth (no inline overrides of letterSpacing for labels)
   - Review tag-chip styles in DocumentCardBase and similar label-heavy components
   - Add detekt rule (or lint check) to catch direct TextStyle { letterSpacing = ... } overrides on labels

## Test matrix

| Axis | Case | Required behavior |
|---|---|---|
| Touch target (rotate) | 28dp visual, expanded to >=48dp interactive | minimumInteractiveComponentSize wrapper applied; onClickLabel set; can tap outer region |
| Touch target (remove) | 28dp visual, expanded to >=48dp interactive | minimumInteractiveComponentSize wrapper; onClickLabel set; LongPress haptic fires on tap |
| Typography (labelSmall) | Used in LastSyncedIndicator, ProcessingTaskCard status, tag badges | All render with 0.1em letterSpacing (not 0sp); no local overrides |
| Typography (labelMedium) | Used in section headers, action labels | Consistent weight (Medium) and spacing across app |
| Card affordance | ProcessingTaskCard with actionable task (SUCCESS + documentId) | Clickable Card variant; click navigates to document |
| Card affordance | ProcessingTaskCard with non-actionable task (PENDING or FAILURE) | Non-clickable Card variant; no click handler; visual styling only |
| Live ticker | LastSyncedIndicator relative time | Updates every 60s via LaunchedEffect; "2 minutes ago" → "3 minutes ago" without recompose |
| CI enforcement | New code with `Box.clickable()` below 48dp | detekt rule fails; CI blocks merge |

## Reusable seams

- `androidx.compose.material3.minimumInteractiveComponentSize()` — already imported in fix/a11y-home-polish branch; used as a padding/size wrapper modifier that expands touch targets to Material3 spec (48dp) while preserving inner visual size
- `ui/theme/Type.kt` — single source of truth for typography tokens; labelSmall, labelMedium, labelLarge all reference here; fix once, applies app-wide
- `ui/screens/scan/components/PageThumbnail.kt` — demonstrates pattern: outer Box with minimumInteractiveComponentSize + .clickable, inner Box with visual size (28dp) + background; reuse this pattern for all micro-badges
- `ui/screens/home/components/HomeHeader.kt` — LastSyncedIndicator now uses remember + LaunchedEffect(Unit) with delay loop for live ticker; reuse pattern for other time-sensitive labels
- `ui/screens/home/components/ProcessingTasksSection.kt` — ProcessingTaskCardContent now conditionally renders clickable vs. non-clickable Card based on task status; reuse conditional logic for other status-dependent cards
- `ui/components/documentlist/DocumentCardBase.kt` — tag-chip consistency anchor for label typography; ensure tag styles reference Type.kt labelMedium/labelSmall tokens (no hardcoded letterSpacing)

## Out of scope

- **New UI components**: This plan does not design new screens or refactor god-composables beyond Home/Scan (those covered by separate plan-masters)
- **Gesture detection (swipe, long-press)**: ProcessingTaskCard swipe-to-dismiss logic is tested and stable; not re-opened
- **Accessibility audits beyond touch target + typography**: VoiceOver/TalkBack testing deferred to separate a11y testing plan; this plan focuses on deterministic, automatable compliance (sizing, tokens, detekt rules)
- **Custom shapes (Shapes.kt)**: No shape system exists yet; out of scope for this a11y/typography pass
- **Localization impact**: Typography fixes apply app-wide regardless of language; live ticker uses existing localized string resources (no new strings added)


