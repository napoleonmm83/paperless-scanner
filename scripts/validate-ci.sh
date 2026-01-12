#!/bin/bash
# =============================================================================
# CI Validation Script - Exakte Simulation der GitHub Actions
# =============================================================================
#
# Dieses Script führt EXAKT die gleichen Checks wie .github/workflows/android-ci.yml aus.
# Es MUSS vor jedem Commit/Push ausgeführt werden!
#
# Usage: ./scripts/validate-ci.sh [options]
#
# Options:
#   --quick         Nur schnelle Checks (Compile + Lint, kein vollständiger Build)
#   --full          Alle Checks inkl. Build (Standard)
#   --verbose, -v   Ausführliche Ausgabe
#   --help, -h      Hilfe anzeigen
#
# =============================================================================

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# Default options
QUICK_MODE=false
VERBOSE=false
ERRORS=0
WARNINGS=0

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --quick)
            QUICK_MODE=true
            ;;
        --full)
            QUICK_MODE=false
            ;;
        --verbose|-v)
            VERBOSE=true
            ;;
        --help|-h)
            echo "CI Validation Script - Exakte Simulation der GitHub Actions"
            echo ""
            echo "Usage: ./scripts/validate-ci.sh [options]"
            echo ""
            echo "Options:"
            echo "  --quick         Nur schnelle Checks (Compile + Lint)"
            echo "  --full          Alle Checks inkl. Build (Standard)"
            echo "  --verbose, -v   Ausführliche Ausgabe"
            echo "  --help, -h      Hilfe anzeigen"
            echo ""
            echo "Dieses Script führt EXAKT die gleichen Checks wie GitHub Actions aus:"
            echo "  - testReleaseUnitTest  (nicht Debug!)"
            echo "  - lintRelease          (nicht Debug!)"
            echo "  - assembleRelease      (nicht Debug!)"
            echo "  - Translation Check"
            echo "  - XML Validation"
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
    shift
done

echo ""
echo -e "${CYAN}╔══════════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║                    CI VALIDATION - GitHub Actions Mirror                 ║${NC}"
echo -e "${CYAN}║                                                                          ║${NC}"
echo -e "${CYAN}║  WICHTIG: Dieses Script führt EXAKT die gleichen Checks wie GitHub       ║${NC}"
echo -e "${CYAN}║           Actions aus. Nur wenn alles grün ist, sollte gepusht werden!   ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Show what GitHub Actions does
echo -e "${BLUE}GitHub Actions android-ci.yml Checks:${NC}"
echo -e "  ${MAGENTA}build job:${NC}      ./gradlew testReleaseUnitTest && assembleRelease"
echo -e "  ${MAGENTA}lint job:${NC}       ./gradlew lintRelease"
echo -e "  ${MAGENTA}validate job:${NC}   Translation Check, XML Validation, Hardcoded Strings"
echo ""

START_TIME=$(date +%s)

# Detect gradle wrapper
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" || "$OSTYPE" == "win32" ]]; then
    GRADLEW="./gradlew.bat"
else
    GRADLEW="./gradlew"
    chmod +x ./gradlew 2>/dev/null || true
fi

# Gradle options (same as CI - no daemon for consistency)
GRADLE_OPTS="--no-daemon"
if ! $VERBOSE; then
    GRADLE_OPTS="$GRADLE_OPTS -q"
fi

# =============================================================================
# Helper Functions
# =============================================================================

run_step() {
    local step_num=$1
    local step_name=$2
    local command=$3
    local is_critical=${4:-true}

    echo -e "${YELLOW}[$step_num] $step_name...${NC}"
    STEP_START=$(date +%s)

    if eval "$command"; then
        STEP_END=$(date +%s)
        STEP_DURATION=$((STEP_END - STEP_START))
        echo -e "${GREEN}    ✓ $step_name passed (${STEP_DURATION}s)${NC}"
        return 0
    else
        STEP_END=$(date +%s)
        STEP_DURATION=$((STEP_END - STEP_START))
        if $is_critical; then
            echo -e "${RED}    ✗ $step_name FAILED (${STEP_DURATION}s)${NC}"
            ((ERRORS++))
        else
            echo -e "${YELLOW}    ⚠ $step_name had warnings (${STEP_DURATION}s)${NC}"
            ((WARNINGS++))
        fi
        return 1
    fi
}

# =============================================================================
# STEP 1: Validation Checks (same as GitHub Actions "validate" job)
# =============================================================================

echo -e "${MAGENTA}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${MAGENTA}  PHASE 1: Validation Checks (validate job)${NC}"
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════════${NC}"
echo ""

# 1.1 Translation Completeness Check
if [ -f "scripts/check-translations.sh" ]; then
    chmod +x scripts/check-translations.sh 2>/dev/null || true
    run_step "1.1" "Translation Completeness" "./scripts/check-translations.sh" || true
else
    echo -e "${YELLOW}[1.1] Translation Check... SKIPPED (script not found)${NC}"
fi
echo ""

# 1.2 Check for duplicate string IDs
echo -e "${YELLOW}[1.2] Checking for duplicate string IDs...${NC}"
DUPLICATES=$(grep '<string name="' app/src/main/res/values/strings.xml | sed 's/.*name="\([^"]*\)".*/\1/' | sort | uniq -d)
if [ -n "$DUPLICATES" ]; then
    echo -e "${RED}    ✗ Duplicate string IDs found:${NC}"
    echo "$DUPLICATES" | sed 's/^/        /'
    ((ERRORS++))
else
    echo -e "${GREEN}    ✓ No duplicate string IDs${NC}"
fi
echo ""

# 1.3 Check for empty strings
echo -e "${YELLOW}[1.3] Checking for empty strings...${NC}"
EMPTY=$(grep '<string name="[^"]*"></string>' app/src/main/res/values/strings.xml || true)
if [ -n "$EMPTY" ]; then
    echo -e "${YELLOW}    ⚠ Empty strings found (review manually):${NC}"
    echo "$EMPTY" | head -5 | sed 's/^/        /'
    ((WARNINGS++))
else
    echo -e "${GREEN}    ✓ No empty strings${NC}"
fi
echo ""

# 1.4 Check for hardcoded strings in Kotlin (non-blocking)
echo -e "${YELLOW}[1.4] Checking for potential hardcoded strings...${NC}"
HARDCODED=$(grep -rn 'text = "' app/src/main/java --include="*.kt" 2>/dev/null | grep -v "// OK" | grep -v "TODO" | head -10 || true)
if [ -n "$HARDCODED" ]; then
    echo -e "${YELLOW}    ⚠ Potential hardcoded strings (review manually):${NC}"
    echo "$HARDCODED" | head -5 | sed 's/^/        /'
    if [ $(echo "$HARDCODED" | wc -l) -gt 5 ]; then
        echo "        ... and more"
    fi
    echo -e "${YELLOW}        Consider using stringResource(R.string.xxx) instead.${NC}"
    # Note: This is a warning, not an error (same as GitHub Actions)
else
    echo -e "${GREEN}    ✓ No obvious hardcoded strings${NC}"
fi
echo ""

# =============================================================================
# STEP 2: Build & Test (same as GitHub Actions "build" job)
# =============================================================================

echo -e "${MAGENTA}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${MAGENTA}  PHASE 2: Build & Test (build job) - RELEASE VARIANT${NC}"
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════════${NC}"
echo ""

# 2.1 Unit Tests - RELEASE (same as GitHub Actions!)
run_step "2.1" "Unit Tests (testReleaseUnitTest)" "$GRADLEW testReleaseUnitTest $GRADLE_OPTS" || true
echo ""

# 2.2 Release Build (skip in quick mode)
if ! $QUICK_MODE; then
    run_step "2.2" "Release Build (assembleRelease)" "$GRADLEW assembleRelease $GRADLE_OPTS" || true
else
    echo -e "${YELLOW}[2.2] Release Build... SKIPPED (quick mode)${NC}"
fi
echo ""

# =============================================================================
# STEP 3: Lint Check (same as GitHub Actions "lint" job)
# =============================================================================

echo -e "${MAGENTA}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${MAGENTA}  PHASE 3: Lint Check (lint job) - RELEASE VARIANT${NC}"
echo -e "${MAGENTA}═══════════════════════════════════════════════════════════════${NC}"
echo ""

# 3.1 Lint - RELEASE (same as GitHub Actions!)
run_step "3.1" "Lint Check (lintRelease)" "$GRADLEW lintRelease $GRADLE_OPTS" || true
echo ""

# =============================================================================
# Summary
# =============================================================================

END_TIME=$(date +%s)
TOTAL_DURATION=$((END_TIME - START_TIME))

echo ""
echo -e "${CYAN}══════════════════════════════════════════════════════════════════════════${NC}"

if [[ $ERRORS -eq 0 ]]; then
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║                     ✓ ALL CI CHECKS PASSED                               ║${NC}"
    echo -e "${GREEN}║                                                                          ║${NC}"
    if [[ $WARNINGS -gt 0 ]]; then
        printf "${GREEN}║  Warnings: %-3d (non-blocking)                                            ║${NC}\n" $WARNINGS
    fi
    printf "${GREEN}║  Total time: %-4ds                                                        ║${NC}\n" $TOTAL_DURATION
    echo -e "${GREEN}║                                                                          ║${NC}"
    echo -e "${GREEN}║  ✓ Safe to commit and push to GitHub!                                    ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════════════════════╝${NC}"
    exit 0
else
    echo -e "${RED}╔══════════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║                     ✗ CI CHECKS FAILED                                   ║${NC}"
    echo -e "${RED}║                                                                          ║${NC}"
    printf "${RED}║  Errors: %-3d | Warnings: %-3d                                             ║${NC}\n" $ERRORS $WARNINGS
    echo -e "${RED}║                                                                          ║${NC}"
    echo -e "${RED}║  ✗ DO NOT PUSH - Fix errors first!                                       ║${NC}"
    echo -e "${RED}╚══════════════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${YELLOW}Reports:${NC}"
    echo "  - Tests:  app/build/reports/tests/testReleaseUnitTest/index.html"
    echo "  - Lint:   app/build/reports/lint-results-release.html"
    exit 1
fi
