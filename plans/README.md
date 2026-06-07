# Plans — root-cause consolidation of the open backlog

> ⚠️ **Reihenfolge & aktueller Reststand: siehe [`00-execution-plan.md`](00-execution-plan.md)** (Stand 2026-06-05,
> code-verifiziert). Die Wellen-Sequenz und einige Status-Angaben *unten* sind teils veraltet (Wave 0 ist bereits
> komplett gemerged). Das Master-Execution-Doc ersetzt die Sequenzierung; die `0X-*.md` bleiben die Detail-Specs.

These `plans/0X-*.md` docs are the design half of the `oh-my-issues` consolidation: the 20 open
issues from the 2026-05-04 deep review tail are bundled into root-cause **plan-masters**. Each
multi-child master has a GitHub issue (label `plan` + `plan-0X`) that tracks it; the children are
closed `not planned` with a redirect to their master. The doc is the design; the issue is the tracker.

## Master index

| Plan | Title | Children | Status |
|------|-------|----------|--------|
| [01](01-layer-boundary.md) | Data↔Domain↔UI Layer Boundary | #48, #153, #132 | ready (3 sequenced PRs: #48→#153→#132) |
| [02](02-token-taxonomy.md) | SecureTokenStorage Failure Taxonomy | #303, #37 | #303 first; #37 needs Keystore shadow |
| [03](03-test-interfaces.md) | Test-Double Foundation (interface extraction) | #239, #202 | DI infra → #239 → #202 |
| [04](04-a11y-styleguide.md) | a11y & Style-Guide Conformance | #264, #266 | runtime **landed** (#327); only detekt-enforcement rule remains → see `00-execution-plan.md` B1/B2 |
| [05](05-docrepo-integrity.md) | DocumentRepository Data-Integrity & Cache Semantics | #65, #66 | online path done; finish offline + kdoc sweep |
| [06](06-coroutine-hygiene.md) | Coroutine & Flow Hygiene (3 independent slices) | #50, #82, #142 | 3 small independent PRs |
| [07](07-tooling-hardening.md) | Build/CI & Release Tooling Hardening | #302, #145 | #302 ready; #145 deferred (device-verify) |
| [08](08-typed-navigation.md) | Typed Navigation Routes | #45 | standalone track |
| [09](09-scan-file-lifecycle.md) | Scan Draft File Lifecycle | #307 | **landed** (#326) — complete |
| [10](10-upload-idempotency.md) | Upload At-Most-Once / Idempotency | #287 | **blocked** on Paperless-ngx API investigation |

> #296 remains a standalone umbrella tracker for deferred feature TODOs — not consolidated here.

## Execution order (ROI / unblock graph)

- **Wave 0 — DONE (2026-06-05):** plan-09 (#307, PR #326) and plan-04 runtime (#264/#266, PR #327) are
  merged to `origin/main`; the two stranded local branches have been pruned. Residual: plan-04 detekt rule (→ `00-execution-plan.md` B1/B2).
- **Wave 1 — quick standalone wins:** plan-07a (#302 tooling), plan-08 (#45 typed nav, scoped).
- **Wave 2 — big architectural cluster:** plan-01 (#48→#153→#132).
- **Wave 3 — test foundation:** plan-03 (interface extraction → #239 → #202).
- **Wave 4 — data integrity + coroutine hygiene:** plan-05 (#65 offline path + #66 kdoc), plan-06
  (#142, #82, #50 as three independent PRs).
- **Wave 5 — security hardening:** plan-02 (#303 → #37, needs Robolectric Keystore shadow).
- **Deferred/blocked:** plan-07b (#145 ProGuard, device-verify), plan-10 (#287, upstream API).

Every PR: rebase on current `origin/main` and pass `scripts/validate-ci.sh` before push.
