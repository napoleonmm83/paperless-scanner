---
name: Code Review Finding
about: Finding from structured code review (auto-generated or manual)
title: "[<severity>] <area>: <short description>"
labels: ["code-review"]
assignees: []
---

## Context

<!-- Why this matters. Background. Risk if not fixed. -->

## Location

- **File:** `path/to/file.kt`
- **Lines:** `L42-L78`
- **Function/Class:** `ClassName.functionName`

## Current Code

```kotlin
// Snippet of the problematic code
```

## Problem

<!-- What's wrong, concretely. -->

## Proposed Fix

<!-- High-level approach. Reference Best Practice / CLAUDE.md / Android docs. -->

## Acceptance Criteria

- [ ] Criterion 1
- [ ] Criterion 2
- [ ] All existing tests pass
- [ ] CI green (`./scripts/validate-ci.sh`)
- [ ] No regression in related flows

## Related Findings

<!-- Cross-links to related GitHub issues / docs/code-reviews/findings/F-XXX-*.md -->

## References

- Detail markdown: `docs/code-reviews/findings/F-XXX-<slug>.md`
- Best Practice: <link to CLAUDE.md section / Android docs>

## Estimated Effort

`XS` (<1h) | `S` (1-3h) | `M` (3-8h) | `L` (1-2 days) | `XL` (multi-day refactor)
