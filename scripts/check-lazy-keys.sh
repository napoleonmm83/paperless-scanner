#!/bin/bash
# check-lazy-keys.sh — guard against keyless Lazy* item/items/stickyHeader slots (issue #224).
#
# Lazy* item{}/items(...)/stickyHeader without a stable key= regress animation and
# recomposition state (see #99). Extracted from validate-ci.sh (#302) so the SAME guard runs
# in BOTH the local pre-push gate (validate-ci.sh) AND the GitHub Actions `validate` job — a
# contributor who skips validate-ci.sh can no longer land a keyless slot.
#
# Usage: scripts/check-lazy-keys.sh [SCAN_DIR]   (default: app/src/main/java)
# Exit:  0 = all Lazy slots keyed · 1 = at least one keyless slot found
#
# Scope-aware awk block scanner: a left word-boundary excludes non-Lazy overloads
# (DropdownMenuItem, NavigationBarItem, addItem, .item(...) ...); comment/KDoc lines are
# stripped so prose never trips it; multi-line calls are paren-balanced. NUL-delimited grep
# + mapfile array pass filenames safely (GNU grep + bash 4.4+ — both present in CI ubuntu
# and Win11 Git-Bash).
set -uo pipefail

SCAN_DIR="${1:-app/src/main/java}"

LAZY_KT_FILES=()
mapfile -d '' -t LAZY_KT_FILES < <(grep -rlEZ '(^|[^[:alnum:]_.])(item|items|stickyHeader)[[:space:]]*[({]' "$SCAN_DIR" --include="*.kt" 2>/dev/null || true)

KEYLESS=""
if [ ${#LAZY_KT_FILES[@]} -gt 0 ]; then
    KEYLESS=$(awk '
    BEGIN { collecting = 0 }
    {
        raw = $0
        sub(/\/\/.*/, "", raw)
        if (raw ~ /^[[:space:]]*\*/)  { if (!collecting) next }
        if (raw ~ /^[[:space:]]*\/\*/) { if (!collecting) next }
        if (!collecting && \
            raw ~ /(^|[^[:alnum:]_.])(item|items|stickyHeader)[[:space:]]*[({]/) {
            collecting = 1; buf = ""; depth = 0; sawParen = 0
            startline = FNR; startfile = FILENAME
        }
        if (collecting) {
            buf = buf " " raw
            o = gsub(/\(/, "(", raw)
            c = gsub(/\)/, ")", raw)
            if (o > 0) sawParen = 1
            depth += o - c
            closed = 0
            if (sawParen == 0 && buf ~ /(item|items|stickyHeader)[[:space:]]*\{/) closed = 1
            if (sawParen == 1 && depth <= 0) closed = 1
            if (closed) {
                collecting = 0
                # Check key= only in the call HEADER (before the lambda body): strip from the
                # first "{" so a key= nested inside the item body cannot mask a keyless slot
                # (e.g. single-line `item { items(x, key={}) }`). (CodeRabbit)
                header = buf; sub(/\{.*/, "", header)
                if (header !~ /key[[:space:]]*=/) {
                    g = buf; gsub(/[[:space:]]+/, " ", g)
                    printf("        %s:%d %s\n", startfile, startline, g)
                }
            }
        }
    }
' "${LAZY_KT_FILES[@]}" 2>/dev/null || true)
fi

if [ -n "$KEYLESS" ]; then
    echo "    ✗ Lazy item/items slot without key= found (issue #224):"
    echo "$KEYLESS"
    echo "        Add a stable key= argument (e.g. items(data, key = { it.id }))."
    exit 1
fi

echo "    ✓ All Lazy item slots have keys"
exit 0
