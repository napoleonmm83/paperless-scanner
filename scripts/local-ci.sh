#!/bin/bash
# Local Android CI Check Script for Paperless Scanner
# Simulates GitHub Actions CI locally before push
# Usage: ./scripts/local-ci.sh [options]

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# Default options
RUN_TESTS=true
RUN_LINT=true
RUN_BUILD=false
QUICK_MODE=false
VERBOSE=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --quick|-q)
            QUICK_MODE=true
            RUN_TESTS=false
            ;;
        --full|-f)
            RUN_BUILD=true
            ;;
        --no-tests)
            RUN_TESTS=false
            ;;
        --no-lint)
            RUN_LINT=false
            ;;
        --verbose|-v)
            VERBOSE=true
            ;;
        --help|-h)
            echo "Paperless Scanner - Local CI Check"
            echo ""
            echo "Usage: ./scripts/local-ci.sh [options]"
            echo ""
            echo "Options:"
            echo "  --quick, -q     Quick mode: lint only (fastest)"
            echo "  --full, -f      Full mode: tests + lint + build"
            echo "  --no-tests      Skip unit tests"
            echo "  --no-lint       Skip lint check"
            echo "  --verbose, -v   Verbose output"
            echo "  --help, -h      Show this help"
            echo ""
            echo "Examples:"
            echo "  ./scripts/local-ci.sh           # Default: tests + lint"
            echo "  ./scripts/local-ci.sh --quick   # Quick: lint only (~45s)"
            echo "  ./scripts/local-ci.sh --full    # Full: tests + lint + build"
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
    shift
done

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Paperless Scanner - Local CI${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

START_TIME=$(date +%s)
ERRORS=0

# Compile check (syntax errors)
run_compile_check() {
    echo -e "${YELLOW}[1/4] Compile check...${NC}"
    STEP_START=$(date +%s)

    if $VERBOSE; then
        ./gradlew compileDebugKotlin --no-daemon
    else
        ./gradlew compileDebugKotlin --no-daemon -q
    fi

    if [[ $? -ne 0 ]]; then
        echo -e "${RED}Compile check FAILED${NC}"
        ((ERRORS++))
        return 1
    fi

    STEP_END=$(date +%s)
    STEP_DURATION=$((STEP_END - STEP_START))
    echo -e "${GREEN}Compile check passed (${STEP_DURATION}s)${NC}"
    echo ""
}

# Unit tests
run_unit_tests() {
    if ! $RUN_TESTS; then
        echo -e "${YELLOW}[2/4] Unit tests... SKIPPED${NC}"
        echo ""
        return 0
    fi

    echo -e "${YELLOW}[2/4] Running unit tests...${NC}"
    STEP_START=$(date +%s)

    if $VERBOSE; then
        ./gradlew testDebugUnitTest --no-daemon
    else
        ./gradlew testDebugUnitTest --no-daemon -q
    fi

    if [[ $? -ne 0 ]]; then
        echo -e "${RED}Unit tests FAILED${NC}"
        echo "Results: app/build/reports/tests/testDebugUnitTest/index.html"
        ((ERRORS++))
        return 1
    fi

    STEP_END=$(date +%s)
    STEP_DURATION=$((STEP_END - STEP_START))
    echo -e "${GREEN}Unit tests passed (${STEP_DURATION}s)${NC}"
    echo ""
}

# Lint check
run_lint_check() {
    if ! $RUN_LINT; then
        echo -e "${YELLOW}[3/4] Lint check... SKIPPED${NC}"
        echo ""
        return 0
    fi

    echo -e "${YELLOW}[3/4] Running lint check...${NC}"
    STEP_START=$(date +%s)

    if $VERBOSE; then
        ./gradlew lintDebug --no-daemon
    else
        ./gradlew lintDebug --no-daemon -q
    fi

    if [[ $? -ne 0 ]]; then
        echo -e "${RED}Lint check FAILED${NC}"
        echo "Results: app/build/reports/lint-results-debug.html"
        ((ERRORS++))
        return 1
    fi

    STEP_END=$(date +%s)
    STEP_DURATION=$((STEP_END - STEP_START))
    echo -e "${GREEN}Lint check passed (${STEP_DURATION}s)${NC}"
    echo ""
}

# Build check
run_build_check() {
    if ! $RUN_BUILD; then
        echo -e "${YELLOW}[4/4] Build check... SKIPPED${NC}"
        echo ""
        return 0
    fi

    echo -e "${YELLOW}[4/4] Building debug APK...${NC}"
    STEP_START=$(date +%s)

    if $VERBOSE; then
        ./gradlew assembleDebug --no-daemon
    else
        ./gradlew assembleDebug --no-daemon -q
    fi

    if [[ $? -ne 0 ]]; then
        echo -e "${RED}Build FAILED${NC}"
        ((ERRORS++))
        return 1
    fi

    STEP_END=$(date +%s)
    STEP_DURATION=$((STEP_END - STEP_START))
    echo -e "${GREEN}Build passed (${STEP_DURATION}s)${NC}"
    echo ""
}

# Print summary
print_summary() {
    END_TIME=$(date +%s)
    TOTAL_DURATION=$((END_TIME - START_TIME))

    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  CI Summary${NC}"
    echo -e "${BLUE}========================================${NC}"

    if [[ $ERRORS -eq 0 ]]; then
        echo -e "${GREEN}All checks passed!${NC}"
        echo ""
        echo "Total time: ${TOTAL_DURATION}s"
        echo ""
        echo -e "${GREEN}Safe to commit and push.${NC}"
    else
        echo -e "${RED}${ERRORS} check(s) failed!${NC}"
        echo ""
        echo "Total time: ${TOTAL_DURATION}s"
        echo ""
        echo -e "${RED}Fix errors before committing.${NC}"
        exit 1
    fi
}

# Quick mode (lint only)
if $QUICK_MODE; then
    echo "Mode: Quick (lint only)"
    echo ""
    run_lint_check
    print_summary
    exit 0
fi

# Normal/Full mode
if $RUN_BUILD; then
    echo "Mode: Full (compile + tests + lint + build)"
else
    echo "Mode: Default (compile + tests + lint)"
fi
echo ""

run_compile_check
run_unit_tests
run_lint_check
run_build_check
print_summary
