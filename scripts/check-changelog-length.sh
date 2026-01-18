#!/bin/bash

# check-changelog-length.sh
# Validates that all fastlane changelogs are ≤500 characters (Google Play limit)

set -e

CHANGELOG_DIR="fastlane/metadata/android"
MAX_CHARS=500
EXIT_CODE=0

echo "🔍 Checking changelog lengths (Google Play limit: ${MAX_CHARS} characters)..."
echo ""

# Find all changelog files in both de-DE and en-US
for CHANGELOG in $(find "$CHANGELOG_DIR" -name "*.txt" -type f | grep -E "(de-DE|en-US)/changelogs/"); do
    # Get character count (including newlines)
    CHAR_COUNT=$(wc -m < "$CHANGELOG" | tr -d ' ')

    # Get relative path for better readability
    REL_PATH=$(echo "$CHANGELOG" | sed "s|^$CHANGELOG_DIR/||")

    if [ "$CHAR_COUNT" -gt "$MAX_CHARS" ]; then
        echo "❌ FAILED: $REL_PATH"
        echo "   Length: $CHAR_COUNT characters (exceeds limit by $((CHAR_COUNT - MAX_CHARS)))"
        echo ""
        EXIT_CODE=1
    else
        echo "✅ PASSED: $REL_PATH ($CHAR_COUNT characters)"
    fi
done

echo ""

if [ $EXIT_CODE -eq 0 ]; then
    echo "✅ All changelogs are within the 500-character limit!"
else
    echo "❌ Some changelogs exceed the 500-character limit. Please shorten them."
    echo ""
    echo "Tips for shortening changelogs:"
    echo "  - Use abbreviations (e.g., '30-Min' not '30-Minuten')"
    echo "  - Remove filler words"
    echo "  - Keep bullet points under 60 characters"
    echo "  - Remove 'Co-Authored-By' lines (use only in git commits)"
    echo ""
fi

exit $EXIT_CODE
