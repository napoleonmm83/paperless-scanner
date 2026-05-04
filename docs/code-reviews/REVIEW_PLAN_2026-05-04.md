# Review Plan & State Tracker — 2026-05-04

> **Purpose:** Live state tracking so this review is **resumable across Claude sessions**.
> A new session can read this file and know exactly where work left off.

## How to Resume

If you're a Claude session picking up this review:

1. Read `docs/code-reviews/REVIEW_2026-05-04.md` (master report)
2. Read **this** file to find the next pending wave / area
3. Look at `docs/code-reviews/findings/` for already-written findings (skip duplicates)
4. Check `gh issue list --milestone "Code Review 2026-05"` for already-created issues
5. Continue from the next ⏳ item

---

## Phase 0 — Setup ✅

- [x] GitHub labels created (severity:*, area:*, code-review)
- [x] Milestone created: [#1 Code Review 2026-05](https://github.com/napoleonmm83/paperless-scanner/milestone/1)
- [x] Issue template: `.github/ISSUE_TEMPLATE/code-review-finding.md`
- [x] Directory structure: `docs/code-reviews/findings/`
- [x] Master report skeleton: `REVIEW_2026-05-04.md`
- [x] This plan file
- [x] Graph baseline collected (229 files, 2405 nodes, 30464 edges, 16+ communities)
- [x] CodeRabbit config: `.coderabbit.yaml` (path-aware, CLAUDE.md/MEMORY.md rules baked in)
- [x] CodeRabbit setup guide: `docs/CODERABBIT_SETUP.md`
- [ ] Install CodeRabbit GitHub App (manual one-time step — see CODERABBIT_SETUP.md §1)
- [ ] Embeddings (blocked: `pip install code-review-graph[embeddings]` required)

---

## Phase 1 — Wave 1: Security + Architecture + God Classes 🟡

Dispatched as parallel subagents. Each returns structured findings list.

| Area | Agent | Status | Findings |
|------|-------|--------|----------|
| 1.1 SECURITY (TokenManager, AppLockManager, BillingManager, leaks, logging) | Explore | ⏳ | — |
| 1.2 ARCHITECTURE (Clean Architecture, layering violations, DI patterns) | Explore | ⏳ | — |
| 1.3 DOCUMENT_REPOSITORY (1349-line God-class — split candidates) | Explore | ⏳ | — |
| 1.4 VIEWMODELS (Home, Scan, Upload, DocumentDetail VMs) | Explore | ⏳ | — |

---

## Phase 2 — Wave 2: UI + Widget + API + Workers ⏳

| Area | Status | Findings |
|------|--------|----------|
| 2.1 LARGE_COMPOSE_SCREENS (Labels, Scan, Home, DocumentTabs, Settings, …) | ⏳ | — |
| 2.2 WIDGET (ScannerWidget Glance + legacy + config) | ⏳ | — |
| 2.3 API_LAYER (PaperlessApi.kt + models + network config) | ⏳ | — |
| 2.4 WORKERS (WorkManager jobs, retry policy) | ⏳ | — |

---

## Phase 3 — Wave 3: Cross-cutting ⏳

| Area | Status | Findings |
|------|--------|----------|
| 3.1 TESTING (coverage gaps, test quality) | ⏳ | — |
| 3.2 PERFORMANCE/THREADING (Dispatchers, runBlocking, recomp) | ⏳ | — |
| 3.3 TOOLING/CI (GitHub Actions, scripts, gradle, fastlane) | ⏳ | — |
| 3.4 STYLE_GUIDE (Dark Tech Precision Pro compliance) | ⏳ | — |
| 3.5 STRINGS/I18N (hardcoded strings, translation gotchas) | ⏳ | — |

---

## Phase 4 — Aggregation ⏳

- [ ] Merge findings from all subagents
- [ ] Write atomic finding markdowns (`F-001`–`F-NNN`)
- [ ] Update master report severity matrix + index
- [ ] Cross-link related findings

---

## Phase 5 — GitHub Issues ⏳

- [ ] Generate one issue per finding via `gh issue create`
- [ ] Apply severity + area labels
- [ ] Assign to milestone `Code Review 2026-05`
- [ ] Update finding markdown with issue URL

---

## Phase 6 — Refactoring Roadmap ⏳

- [ ] Order findings by ROI (impact / effort)
- [ ] Group into logical sprints
- [ ] Write `docs/code-reviews/ROADMAP_2026-05.md`
- [ ] Identify quick wins (XS / S effort, high severity)
- [ ] Identify long-term refactors (XL effort)

---

## State Snapshot

```
last_updated: 2026-05-04
findings_written: 120
issues_created: 120
master_report:    docs/code-reviews/REVIEW_2026-05-04.md
roadmap:          docs/code-reviews/ROADMAP_2026-05.md
github_milestone: https://github.com/napoleonmm83/paperless-scanner/milestone/1
status:           ✅ Complete — all phases done
next_action:      Begin executing the Roadmap (start with Sprint 1: Critical findings)
```

## Wave Summary

| Wave | Focus | Findings | Issues |
|------|-------|----------|--------|
| 1 | Security · Architecture · DocumentRepository · ViewModels | 59 | #28–#87 |
| 2 | UI Compose · Widget · API · Workers | 49 | #88–#135 |
| 3 | Testing · Performance · Tooling · I18N | 12 | #136–#147 |
| **Total** | — | **120** | **120** |

## Severity Distribution

- 🔴 Critical: 8
- 🟠 High: 37
- 🟡 Medium: 60
- 🔵 Low: 15

Estimated total effort (single dev): **~616 hours** (~77 dev-days). Parallelizable across topics — see `ROADMAP_2026-05.md` for sprint plan.

## Resuming Work

To pick up the review work in a future Claude session:

1. **Pick a finding from the Roadmap or Issue tracker:**
   ```
   gh issue list --milestone "Code Review 2026-05" --label severity:critical --state open
   ```
2. **Read the atomic finding markdown** (self-contained):
   ```
   Read docs/code-reviews/findings/F-NNN-<slug>.md
   ```
3. **Implement the fix**, link the PR to the issue, close on merge.
4. CodeRabbit will auto-review the PR; the path-instructions enforce CLAUDE.md conventions.
