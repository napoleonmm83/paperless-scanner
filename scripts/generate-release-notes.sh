#!/bin/bash
set -e  # Exit on error, but NO pipefail to avoid SIGPIPE

# Script to generate structured GitHub Release notes
# Usage: ./generate-release-notes.sh <new_version> <version_code> <previous_version>

NEW_VERSION="$1"
VERSION_CODE="$2"
PREVIOUS_VERSION="$3"
RELEASE_DATE=$(date +%Y-%m-%d)
TRACK="Internal Testing"

echo "Generating release notes for v${NEW_VERSION} (code: ${VERSION_CODE})"

# Get commits since last tag
PREVIOUS_TAG="v${PREVIOUS_VERSION}"
if git rev-parse "$PREVIOUS_TAG" >/dev/null 2>&1; then
  COMMITS=$(git log "${PREVIOUS_TAG}..HEAD" --oneline --no-merges)
  echo "Found $(echo "$COMMITS" | wc -l) commits since ${PREVIOUS_TAG}"
else
  echo "Tag ${PREVIOUS_TAG} not found, using last 20 commits"
  COMMITS=$(git log --oneline --no-merges | head -20)
fi

# Count commits BEFORE using in heredoc (avoid SIGPIPE in heredoc)
COMMIT_COUNT=$(echo "$COMMITS" | wc -l)

# Parse commits into categories
FEATURES=""
FIXES=""
IMPROVEMENTS=""
TECHNICAL=""

while IFS= read -r commit; do
  if [ -z "$commit" ]; then
    continue
  fi

  msg=$(echo "$commit" | sed 's/^[a-f0-9]* //')

  if [[ "$msg" =~ ^feat(\(.*\))?:\ (.+) ]]; then
    FEATURES="${FEATURES}- **${BASH_REMATCH[2]}**"$'\n'
  elif [[ "$msg" =~ ^fix(\(.*\))?:\ (.+) ]]; then
    FIXES="${FIXES}- **Fix: ${BASH_REMATCH[2]}**"$'\n'
  elif [[ "$msg" =~ ^perf(\(.*\))?:\ (.+) ]] || [[ "$msg" =~ ^refactor(\(.*\))?:\ (.+) ]]; then
    IMPROVEMENTS="${IMPROVEMENTS}- **${BASH_REMATCH[2]}**"$'\n'
  elif [[ "$msg" =~ ^test(\(.*\))?:\ (.+) ]] || [[ "$msg" =~ ^chore(\(.*\))?:\ (.+) ]] || [[ "$msg" =~ ^docs(\(.*\))?:\ (.+) ]]; then
    TECHNICAL="${TECHNICAL}- ${msg}"$'\n'
  fi
done <<< "$COMMITS"

# Generate highlights
HIGHLIGHTS="Diese Version enthält Verbesserungen und Fehlerbehebungen."
if [ -n "$FEATURES" ]; then
  HIGHLIGHTS="Diese Version bringt neue Features und Verbesserungen."
fi

# Generate comparison URL
REPO_URL="https://github.com/napoleonmm83/paperless-scanner"
COMPARISON_URL="${REPO_URL}/compare/v${PREVIOUS_VERSION}...v${NEW_VERSION}"

# Write release notes to file in smaller chunks to avoid SIGPIPE
OUTPUT_FILE="release-notes.md"

# Header
printf "## 📱 Paperless Scanner v%s\n\n" "${NEW_VERSION}" > "$OUTPUT_FILE"
printf "**Release Date:** %s\n" "${RELEASE_DATE}" >> "$OUTPUT_FILE"
printf "**Version Code:** %s\n" "${VERSION_CODE}" >> "$OUTPUT_FILE"
printf "**Track:** %s\n\n" "${TRACK}" >> "$OUTPUT_FILE"
echo "---" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

# Highlights
printf "## 🎯 Highlights\n\n" >> "$OUTPUT_FILE"
printf "%s\n\n" "${HIGHLIGHTS}" >> "$OUTPUT_FILE"
echo "---" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

# Features
if [ -n "$FEATURES" ]; then
  printf "## ✨ Neue Features\n\n" >> "$OUTPUT_FILE"
  printf "%s\n" "$FEATURES" >> "$OUTPUT_FILE"
fi

# Fixes
if [ -n "$FIXES" ]; then
  printf "## 🐛 Fehlerbehebungen\n\n" >> "$OUTPUT_FILE"
  printf "%s\n" "$FIXES" >> "$OUTPUT_FILE"
fi

# Improvements
if [ -n "$IMPROVEMENTS" ]; then
  printf "## 🔧 Verbesserungen\n\n" >> "$OUTPUT_FILE"
  printf "%s\n" "$IMPROVEMENTS" >> "$OUTPUT_FILE"
fi

# Technical
if [ -n "$TECHNICAL" ]; then
  printf "## 📚 Technische Änderungen\n\n" >> "$OUTPUT_FILE"
  printf "%s\n" "$TECHNICAL" >> "$OUTPUT_FILE"
fi

# Breaking Changes
printf "## ⚠️ Breaking Changes\n\n" >> "$OUTPUT_FILE"
printf "*Keine Breaking Changes in dieser Version*\n\n" >> "$OUTPUT_FILE"
echo "---" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

# Links
echo "## 🔗 Links" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"
echo "- [GitHub Repository](${REPO_URL})" >> "$OUTPUT_FILE"
echo "- [Issue Tracker](${REPO_URL}/issues)" >> "$OUTPUT_FILE"
echo "- [Paperless-ngx](https://github.com/paperless-ngx/paperless-ngx)" >> "$OUTPUT_FILE"
echo "- [Dokumentation](${REPO_URL}/tree/main/docs)" >> "$OUTPUT_FILE"
echo "- [Google Play Store](https://play.google.com/store/apps/details?id=com.paperless.scanner)" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"
echo "---" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

# Contributors
echo "## 🙏 Contributors" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"
echo "- @napoleonmm83 - Main development" >> "$OUTPUT_FILE"
echo "- Claude Sonnet 4.5 - AI-assisted development and code review" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"
echo "---" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

# Comparison
printf "**Vollständige Änderungen:** [\`v%s...v%s\`](%s)\n\n" "${PREVIOUS_VERSION}" "${NEW_VERSION}" "${COMPARISON_URL}" >> "$OUTPUT_FILE"
echo "---" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

# Statistics
echo "## 📊 Statistiken" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"
echo "- **Commits:** ${COMMIT_COUNT} commits seit v${PREVIOUS_VERSION}" >> "$OUTPUT_FILE"
echo "- **Version Code:** ${VERSION_CODE}" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"
echo "---" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

# Footer
printf "**Release erstellt am:** %s\n" "${RELEASE_DATE}" >> "$OUTPUT_FILE"
printf "**Deployed to:** Google Play Console %s\n" "${TRACK}" >> "$OUTPUT_FILE"

echo "✅ Release notes generated successfully at ${OUTPUT_FILE}"
