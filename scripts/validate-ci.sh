#!/bin/bash
# CI Validation Script - Simuliert GitHub Actions EXAKT
# Verwendet die GLEICHEN Befehle wie .github/workflows/android-ci.yml
#
# Usage: ./scripts/validate-ci.sh [options]
#
# Dieses Skript MUSS vor jedem Push ausgefuehrt werden!

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# Default options
SKIP_TESTS=false
SKIP_LINT=false
SKIP_BUILD=false
VERBOSE=false
USE_DOCKER=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-tests)
            SKIP_TESTS=true
            ;;
        --skip-lint)
            SKIP_LINT=true
            ;;
        --skip-build)
            SKIP_BUILD=true
            ;;
        --docker)
            USE_DOCKER=true
            ;;
        --verbose|-v)
            VERBOSE=true
            ;;
        --help|-h)
            echo "CI Validation Script - Simuliert GitHub Actions EXAKT"
            echo ""
            echo "Usage: ./scripts/validate-ci.sh [options]"
            echo ""
            echo "Options:"
            echo "  --skip-tests    Skip unit tests"
            echo "  --skip-lint     Skip lint check"
            echo "  --skip-build    Skip debug build"
            echo "  --docker        Use Docker instead of native Gradle"
            echo "  --verbose, -v   Verbose output"
            echo "  --help, -h      Show this help"
            echo ""
            echo "This script runs the EXACT same commands as GitHub Actions."
            echo "Run this BEFORE every push to prevent CI failures!"
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
echo -e "${CYAN}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║           CI VALIDATION - GitHub Actions Simulation          ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Show what GitHub Actions does
echo -e "${BLUE}GitHub Actions android-ci.yml runs:${NC}"
echo "  1. ./gradlew testDebugUnitTest"
echo "  2. ./gradlew lintDebug"
echo "  3. ./gradlew assembleDebug"
echo ""

START_TIME=$(date +%s)
ERRORS=0

# Detect gradle wrapper
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" || "$OSTYPE" == "win32" ]]; then
    GRADLEW="./gradlew.bat"
else
    GRADLEW="./gradlew"
    chmod +x ./gradlew 2>/dev/null || true
fi

# Gradle options (same as CI)
GRADLE_OPTS="--no-daemon"
if ! $VERBOSE; then
    GRADLE_OPTS="$GRADLE_OPTS -q"
fi

run_step() {
    local step_num=$1
    local step_name=$2
    local command=$3

    echo -e "${YELLOW}[$step_num] $step_name...${NC}"
    STEP_START=$(date +%s)

    if eval "$command"; then
        STEP_END=$(date +%s)
        STEP_DURATION=$((STEP_END - STEP_START))
        echo -e "${GREEN}    ✓ $step_name passed (${STEP_DURATION}s)${NC}"
        return 0
    else
        echo -e "${RED}    ✗ $step_name FAILED${NC}"
        ((ERRORS++))
        return 1
    fi
}

# Run Docker or Native
if $USE_DOCKER; then
    echo -e "${BLUE}Using Docker CI environment...${NC}"
    echo ""

    if ! command -v docker &> /dev/null; then
        echo -e "${RED}Docker not found!${NC}"
        exit 1
    fi

    docker-compose -f docker-compose.ci.yml run --rm ci-full

else
    echo -e "${BLUE}Using native Gradle...${NC}"
    echo ""

    # Step 1: Unit Tests (same as GitHub Actions "Build & Test" job)
    if ! $SKIP_TESTS; then
        run_step "1/3" "Unit Tests (testDebugUnitTest)" "$GRADLEW testDebugUnitTest $GRADLE_OPTS" || true
    else
        echo -e "${YELLOW}[1/3] Unit Tests... SKIPPED${NC}"
    fi
    echo ""

    # Step 2: Lint Check (same as GitHub Actions "Lint Check" job)
    if ! $SKIP_LINT; then
        run_step "2/3" "Lint Check (lintDebug)" "$GRADLEW lintDebug $GRADLE_OPTS" || true
    else
        echo -e "${YELLOW}[2/3] Lint Check... SKIPPED${NC}"
    fi
    echo ""

    # Step 3: Build Debug APK (same as GitHub Actions "Build & Test" job)
    if ! $SKIP_BUILD; then
        run_step "3/3" "Debug Build (assembleDebug)" "$GRADLEW assembleDebug $GRADLE_OPTS" || true
    else
        echo -e "${YELLOW}[3/3] Debug Build... SKIPPED${NC}"
    fi
fi

END_TIME=$(date +%s)
TOTAL_DURATION=$((END_TIME - START_TIME))

echo ""
echo -e "${CYAN}══════════════════════════════════════════════════════════════${NC}"

if [[ $ERRORS -eq 0 ]]; then
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║              ✓ ALL CI CHECKS PASSED                          ║${NC}"
    echo -e "${GREEN}║                                                              ║${NC}"
    echo -e "${GREEN}║  Total time: ${TOTAL_DURATION}s                                            ║${NC}"
    echo -e "${GREEN}║  Safe to push to GitHub!                                     ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════════╝${NC}"
    exit 0
else
    echo -e "${RED}╔══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║              ✗ CI CHECKS FAILED                              ║${NC}"
    echo -e "${RED}║                                                              ║${NC}"
    echo -e "${RED}║  ${ERRORS} check(s) failed!                                         ║${NC}"
    echo -e "${RED}║  DO NOT PUSH - Fix errors first!                             ║${NC}"
    echo -e "${RED}╚══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${YELLOW}Tip: Check the following reports:${NC}"
    echo "  - Tests:  app/build/reports/tests/testDebugUnitTest/index.html"
    echo "  - Lint:   app/build/reports/lint-results-debug.html"
    exit 1
fi
