# Code Reviews

Structured code reviews of the Paperless Scanner Android client.

## Layout

```
docs/code-reviews/
├── README.md                    # This file
├── REVIEW_<DATE>.md             # Master report per review run
├── REVIEW_PLAN_<DATE>.md        # Plan + progress tracker (session-resumable)
└── findings/
    └── F-NNN-<slug>.md          # Atomic finding (self-contained, session-safe)
```

## Workflow

### 1. Run a review
1. Use `code-review-graph` MCP tools (graph stats, communities, hotspots, flows)
2. Generate findings markdown in `findings/F-NNN-<slug>.md`
3. Update master report `REVIEW_<DATE>.md` with severity matrix + index
4. Create GitHub issue per finding via `gh issue create`

### 2. Resume work in a new session
Each finding markdown is **self-contained**. To fix one:
```
Read docs/code-reviews/findings/F-042-<slug>.md
```
The agent has all context: file paths, line numbers, problem, fix approach, acceptance criteria.

### 3. Track progress
- GitHub Issues with milestone `Code Review <DATE>` and labels `severity:*` + `area:*`
- Issue body links back to the finding markdown
- Close issue when PR merged → finding is "resolved"

## Conventions

### Severity
| Label | Meaning |
|-------|---------|
| `severity:critical` | Security / data loss / crash / production-blocker |
| `severity:high` | Significant maintainability or performance risk |
| `severity:medium` | Code quality / tech debt / SRP violations |
| `severity:low` | Minor / nice-to-have / style |

### Area Labels
`area:security`, `area:architecture`, `area:refactor`, `area:performance`, `area:testing`, `area:ui`, `area:di`, `area:data`, `area:worker`, `area:widget`, `area:tooling`

### Effort Estimate
`XS` (<1h) · `S` (1-3h) · `M` (3-8h) · `L` (1-2 days) · `XL` (multi-day refactor)

## Past Reviews

- [Review 2026-05-04 — Deep Review (full codebase)](./REVIEW_2026-05-04.md)

## Continuous Review (CodeRabbit)

Per-PR AI review runs automatically via CodeRabbit. Configuration lives in
[`.coderabbit.yaml`](../../.coderabbit.yaml). Setup, commands, and tuning:
[`docs/CODERABBIT_SETUP.md`](../CODERABBIT_SETUP.md).

The relationship:

| Layer | Cadence | Purpose |
|-------|---------|---------|
| **CodeRabbit** | Every PR | Catch regressions, enforce CLAUDE.md per-path rules |
| **Deep Review (this folder)** | Quarterly / on-demand | Architecture audits, God-class detection, refactoring roadmap |
| **`/code-review:code-review`** | On request | Single PR deep dive |
| **`/security-review`** | On request | Security-sensitive PRs |
