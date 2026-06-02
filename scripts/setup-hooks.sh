#!/bin/bash
# =============================================================================
# setup-hooks.sh — One-time Git hooks bootstrap
# =============================================================================
#
# Points Git at the versioned hooks in .githooks/ via core.hooksPath.
# Run once after cloning the repository:
#
#   ./scripts/setup-hooks.sh
#
# This makes `git commit` run .githooks/pre-commit (quick syntax checks) and
# `git push` run .githooks/pre-push (auto-rebase with remote). To bypass a
# single run, use the standard `--no-verify` flag.
#
# Exit codes: 0 = hooks installed (core.hooksPath set to .githooks);
#             1 = not run from inside the repository (.githooks/ missing).
# =============================================================================

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

if [ ! -d ".githooks" ]; then
    echo -e "${RED}✗ .githooks/ directory not found. Run this from inside the repository.${NC}"
    exit 1
fi

# Ensure the hook scripts are executable (no-op on Windows filesystems).
chmod +x .githooks/pre-commit .githooks/pre-push 2>/dev/null || true

# Point Git at the versioned hooks.
git config core.hooksPath .githooks

echo -e "${GREEN}✓ Git hooks installed.${NC}"
echo -e "  core.hooksPath -> $(git config --get core.hooksPath)"
echo ""
echo -e "${YELLOW}Hooks now active:${NC}"
echo "  pre-commit  Quick syntax checks (Kotlin + test compile, duplicate string IDs)"
echo "  pre-push    Auto-rebase with remote (prevents GitHub Actions bump conflicts)"
echo ""
echo -e "Bypass a single run with ${YELLOW}--no-verify${NC} (discouraged)."
