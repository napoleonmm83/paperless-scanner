# CodeRabbit Setup — Paperless Scanner

CodeRabbit provides **continuous AI code review on every PR**, complementing the
**one-shot deep reviews** stored in `docs/code-reviews/`.

## What CodeRabbit Does

| Capability | When |
|------------|------|
| AI walkthrough + summary | On PR open / push |
| Inline comments with fix suggestions | On PR open / push |
| Line-level chat (`@coderabbitai`) | On demand |
| Static analyzers: ktlint, detekt, gitleaks, semgrep, actionlint, shellcheck, yamllint, markdownlint, languagetool | On PR |
| Path-aware reviews (CLAUDE.md / MEMORY.md rules per directory) | On PR |
| Auto-labeling (severity / area) | On PR |
| Sequence diagrams | On PR |

## One-Time Setup

### 1. Install the GitHub App

Open https://github.com/apps/coderabbitai → **Install** → choose
`napoleonmm83/paperless-scanner`. Grant repo access (read code + write comments).

> Free tier: open-source repos. Paid otherwise. Check current pricing at
> https://coderabbit.ai/pricing.

### 2. Verify Configuration

The repo already ships a config: [`.coderabbit.yaml`](../.coderabbit.yaml).

Key choices made there:
- **Profile:** `assertive` — surfaces more issues (not just chill nits)
- **Auto-review:** on PRs targeting `main`, drafts excluded
- **Path instructions:** enforce CLAUDE.md conventions per directory:
  - UI screens → Dark Tech Precision Pro style guide
  - Repositories → Flow/Result patterns, no God-classes
  - DataStore → no token logging, AEADBadTagException recovery
  - Widget → Glance gotchas from MEMORY.md
  - API → Paperless-ngx quirks (plain-string upload response)
  - `strings.xml` → English-only policy (Gemini handles other locales)
  - Changelogs → ≤ 500 char enforcement
  - Workflows → RELEASE variants only, JDK 21
- **Tools enabled:** ktlint, detekt, gitleaks, semgrep, actionlint, shellcheck, yamllint, markdownlint, languagetool
- **Knowledge base:** auto-learns from past PRs/issues, web search ON

### 3. Trigger First Review

Open any PR, or run on existing PRs:
```bash
# In the PR comments:
@coderabbitai review
```

## Day-to-Day Workflow

### On every PR
1. CodeRabbit posts a walkthrough comment within ~1–2 min
2. Inline comments appear on flagged lines with fix suggestions
3. Apply or dismiss; reply `@coderabbitai resolve` to mark threads done

### Useful Chat Commands (in PR comments)
| Command | Purpose |
|---------|---------|
| `@coderabbitai review` | Re-run review on latest commit |
| `@coderabbitai full review` | Re-review entire PR (not just diff) |
| `@coderabbitai summary` | Regenerate high-level summary |
| `@coderabbitai resolve` | Mark all CodeRabbit threads as resolved |
| `@coderabbitai pause` | Pause auto-reviews on this PR |
| `@coderabbitai resume` | Resume auto-reviews |
| `@coderabbitai ignore` | Ignore this PR entirely |
| `@coderabbitai help` | Help menu |

### Tuning Path Instructions
When you discover a pattern that's worth enforcing on every future PR, add it
to `.coderabbit.yaml` under `reviews.path_instructions`. CodeRabbit applies
these rules immediately on the next review.

Example:
```yaml
- path: "app/src/main/java/com/paperless/scanner/data/sync/**/*.kt"
  instructions: |
    Sync layer must use exponential backoff on transient errors.
    Idempotency keys required for all upload retries.
```

## How CodeRabbit Fits With Other Reviews

| Review type | Cadence | Where stored | Use when |
|-------------|---------|--------------|----------|
| **CodeRabbit** | Per-PR (auto) | PR comments | Every change |
| **Deep review** (this repo) | Quarterly / on-demand | `docs/code-reviews/` | Architecture audits |
| **`/code-review:code-review`** | On request | Inline | Single PR deep dive |
| **`/security-review`** | On request | Inline | Security-sensitive PRs |
| **`/ultrareview`** | On request | Cloud | Pre-merge multi-agent verification |

## Disabling CodeRabbit Per PR

Add `[skip review]` or `WIP` to PR title (configured under
`reviews.auto_review.ignore_title_keywords`).

## Privacy / Data

- CodeRabbit accesses repo code per the GitHub App permissions you grant
- The `knowledge_base` settings in `.coderabbit.yaml` control opt-in to
  cross-PR learning. Set `opt_out: true` if you want strict isolation
  (currently `false` — opt-in for better suggestions)
- `web_search.enabled: true` lets reviews ground claims in current docs.
  Set `false` for fully offline reviews

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| No review on PR | Check app installed; check PR not draft; PR title not `WIP`; base branch is `main` |
| Review missing rules | Verify path matches `path_instructions` glob; commit `.coderabbit.yaml` to default branch |
| Too many nits | Switch `profile: chill` |
| Review too lenient | Switch `profile: assertive` (current) |
| Want gating | Set `request_changes_workflow: true` (currently `false` to allow merges) |

## References

- [CodeRabbit docs](https://docs.coderabbit.ai/)
- [Configuration reference](https://docs.coderabbit.ai/reference/configuration)
- [Path instructions guide](https://docs.coderabbit.ai/guides/review-instructions#path-based-instructions)
- Project config: [`.coderabbit.yaml`](../.coderabbit.yaml)
