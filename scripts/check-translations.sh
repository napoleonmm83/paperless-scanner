#!/bin/bash
# Check that all translation files have the same string keys as the base German strings.xml
# Cross-platform compatible (macOS, Linux, Windows Git Bash)

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

BASE_DIR="app/src/main/res"
BASE_FILE="$BASE_DIR/values/strings.xml"

# All supported language codes
LANGUAGES=(en fr es it pt nl pl sv da no fi cs hu el ro tr)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}==========================================${NC}"
echo -e "${BLUE}  Translation Completeness Check${NC}"
echo -e "${BLUE}==========================================${NC}"
echo ""

# Extract string names from XML file
# Uses basic sed syntax compatible with both BSD (macOS) and GNU (Linux/Windows)
extract_keys() {
    local file="$1"
    grep '<string name="' "$file" | sed 's/.*<string name="\([^"]*\)".*/\1/' | sort
}

# Count lines in a variable (cross-platform)
count_lines() {
    local content="$1"
    if [ -z "$content" ]; then
        echo "0"
    else
        echo "$content" | wc -l | tr -d ' \t\n'
    fi
}

# Check if base file exists
if [ ! -f "$BASE_FILE" ]; then
    echo -e "${RED}ERROR: Base file not found: $BASE_FILE${NC}"
    exit 1
fi

# Get base keys
BASE_KEYS=$(extract_keys "$BASE_FILE")
BASE_COUNT=$(count_lines "$BASE_KEYS")

echo -e "Base file (German): ${GREEN}$BASE_COUNT strings${NC}"
echo ""

ERRORS=0
WARNINGS=0

for lang in "${LANGUAGES[@]}"; do
    LANG_FILE="$BASE_DIR/values-$lang/strings.xml"

    if [ ! -f "$LANG_FILE" ]; then
        echo -e "${RED}✗ $lang: File missing!${NC}"
        ERRORS=$((ERRORS + 1))
        continue
    fi

    # Extract string names from language file
    LANG_KEYS=$(extract_keys "$LANG_FILE")
    LANG_COUNT=$(count_lines "$LANG_KEYS")

    # Find missing keys (in base but not in lang) using temp files for comm
    BASE_TMP=$(mktemp)
    LANG_TMP=$(mktemp)
    echo "$BASE_KEYS" > "$BASE_TMP"
    echo "$LANG_KEYS" > "$LANG_TMP"

    MISSING=$(comm -23 "$BASE_TMP" "$LANG_TMP" 2>/dev/null || true)
    EXTRA=$(comm -13 "$BASE_TMP" "$LANG_TMP" 2>/dev/null || true)

    rm -f "$BASE_TMP" "$LANG_TMP"

    MISSING_COUNT=$(count_lines "$MISSING")
    EXTRA_COUNT=$(count_lines "$EXTRA")

    # Handle empty results
    if [ -z "$MISSING" ]; then MISSING_COUNT=0; fi
    if [ -z "$EXTRA" ]; then EXTRA_COUNT=0; fi

    if [ "$MISSING_COUNT" -eq 0 ] && [ "$EXTRA_COUNT" -eq 0 ]; then
        echo -e "${GREEN}✓ $lang: Complete ($LANG_COUNT strings)${NC}"
    elif [ "$MISSING_COUNT" -gt 0 ]; then
        echo -e "${RED}✗ $lang: Missing $MISSING_COUNT strings${NC}"
        echo "  Missing keys:"
        echo "$MISSING" | head -5 | sed 's/^/    - /'
        if [ "$MISSING_COUNT" -gt 5 ]; then
            echo "    ... and $((MISSING_COUNT - 5)) more"
        fi
        ERRORS=$((ERRORS + 1))
    else
        echo -e "${YELLOW}⚠ $lang: Has $EXTRA_COUNT extra strings${NC}"
        WARNINGS=$((WARNINGS + 1))
    fi
done

echo ""
echo -e "${BLUE}==========================================${NC}"

if [ "$ERRORS" -gt 0 ]; then
    echo -e "${RED}FAILED: $ERRORS language(s) have missing translations${NC}"
    exit 1
elif [ "$WARNINGS" -gt 0 ]; then
    echo -e "${YELLOW}WARNING: $WARNINGS language(s) have extra strings${NC}"
    exit 0
else
    echo -e "${GREEN}SUCCESS: All translations complete!${NC}"
    exit 0
fi
