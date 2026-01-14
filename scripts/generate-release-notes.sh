#!/usr/bin/env bash
set -euo pipefail

# Generate GitHub Release Notes following RELEASE_NOTES_TEMPLATE.md structure
#
# Usage: ./scripts/generate-release-notes.sh <NEW_VERSION> <VERSION_CODE> <PREVIOUS_VERSION>
# Example: ./scripts/generate-release-notes.sh 1.5.0 10500 1.4.28

if [ "$#" -lt 3 ]; then
    echo "Usage: $0 <NEW_VERSION> <VERSION_CODE> <PREVIOUS_VERSION>"
    echo "Example: $0 1.5.0 10500 1.4.28"
    exit 1
fi

NEW_VERSION="$1"
VERSION_CODE="$2"
PREVIOUS_VERSION="$3"
RELEASE_DATE=$(date +%Y-%m-%d)

# Determine track based on workflow or default to Internal Testing
TRACK="${TRACK:-Internal Testing}"

# Check if manual release notes exist
MANUAL_NOTES="docs/releases/v${NEW_VERSION}.md"
if [ -f "$MANUAL_NOTES" ]; then
    echo "✅ Using manual release notes from: $MANUAL_NOTES"
    cat "$MANUAL_NOTES"
    exit 0
fi

echo "📝 Generating structured release notes for v${NEW_VERSION}..."

# Get commits since last tag
PREVIOUS_TAG="v${PREVIOUS_VERSION}"
if ! git rev-parse "$PREVIOUS_TAG" >/dev/null 2>&1; then
    echo "⚠️  Previous tag $PREVIOUS_TAG not found, using all commits"
    COMMITS=$(git log --oneline --no-merges)
else
    COMMITS=$(git log "${PREVIOUS_TAG}..HEAD" --oneline --no-merges)
fi

# Read changelogs
CHANGELOG_DE_FILE="fastlane/metadata/android/de-DE/changelogs/${VERSION_CODE}.txt"
CHANGELOG_EN_FILE="fastlane/metadata/android/en-US/changelogs/${VERSION_CODE}.txt"
DEFAULT_DE="fastlane/metadata/android/de-DE/changelogs/default.txt"
DEFAULT_EN="fastlane/metadata/android/en-US/changelogs/default.txt"

if [ -f "$CHANGELOG_DE_FILE" ]; then
    CHANGELOG_DE=$(cat "$CHANGELOG_DE_FILE")
else
    CHANGELOG_DE=$(cat "$DEFAULT_DE" 2>/dev/null || echo "Version ${NEW_VERSION}")
fi

if [ -f "$CHANGELOG_EN_FILE" ]; then
    CHANGELOG_EN=$(cat "$CHANGELOG_EN_FILE")
else
    CHANGELOG_EN=$(cat "$DEFAULT_EN" 2>/dev/null || echo "Version ${NEW_VERSION}")
fi

# Parse commits into categories
FEATURES=""
FIXES=""
IMPROVEMENTS=""
TECHNICAL=""
BREAKING=""

while IFS= read -r commit; do
    # Extract commit message (remove hash)
    msg=$(echo "$commit" | sed 's/^[a-f0-9]* //')

    # Categorize based on conventional commit prefixes
    if [[ "$msg" =~ ^feat(\(.*\))?:\ (.+) ]]; then
        FEATURES="${FEATURES}- **${BASH_REMATCH[2]}**\n"
    elif [[ "$msg" =~ ^fix(\(.*\))?:\ (.+) ]]; then
        FIXES="${FIXES}- **Fix: ${BASH_REMATCH[2]}**\n"
    elif [[ "$msg" =~ ^perf(\(.*\))?:\ (.+) ]] || [[ "$msg" =~ ^refactor(\(.*\))?:\ (.+) ]]; then
        IMPROVEMENTS="${IMPROVEMENTS}- **${BASH_REMATCH[2]}**\n"
    elif [[ "$msg" =~ ^test(\(.*\))?:\ (.+) ]] || [[ "$msg" =~ ^chore(\(.*\))?:\ (.+) ]] || [[ "$msg" =~ ^docs(\(.*\))?:\ (.+) ]]; then
        TECHNICAL="${TECHNICAL}- ${msg}\n"
    elif [[ "$msg" =~ BREAKING\ CHANGE ]]; then
        BREAKING="${BREAKING}- **${msg}**\n"
    fi
done <<< "$COMMITS"

# Generate highlights (first feature or fix as placeholder)
HIGHLIGHTS="Diese Version enthält Verbesserungen und Fehlerbehebungen."
if [ -n "$FEATURES" ]; then
    HIGHLIGHTS="Diese Version bringt neue Features und Verbesserungen."
fi

# Generate comparison URL
REPO_URL="https://github.com/napoleonmm83/paperless-scanner"
COMPARISON_URL="${REPO_URL}/compare/v${PREVIOUS_VERSION}...v${NEW_VERSION}"

# Generate structured release notes
cat <<EOF
## 📱 Paperless Scanner v${NEW_VERSION}

**Release Date:** ${RELEASE_DATE}
**Version Code:** ${VERSION_CODE}
**Track:** ${TRACK}

---

## 🎯 Highlights

${HIGHLIGHTS}

---

EOF

# Only include sections that have content
if [ -n "$FEATURES" ]; then
    cat <<EOF
## ✨ Neue Features

$(echo -e "$FEATURES")

EOF
fi

if [ -n "$FIXES" ]; then
    cat <<EOF
## 🐛 Fehlerbehebungen

$(echo -e "$FIXES")

EOF
fi

if [ -n "$IMPROVEMENTS" ]; then
    cat <<EOF
## 🔧 Verbesserungen

$(echo -e "$IMPROVEMENTS")

EOF
fi

if [ -n "$TECHNICAL" ]; then
    cat <<EOF
## 📚 Technische Änderungen

$(echo -e "$TECHNICAL")

EOF
fi

if [ -n "$BREAKING" ]; then
    cat <<EOF
## ⚠️ Breaking Changes

$(echo -e "$BREAKING")

EOF
else
    cat <<EOF
## ⚠️ Breaking Changes

*Keine Breaking Changes in dieser Version*

EOF
fi

# Installation section
cat <<EOF
---

## 📲 Installation

### Google Play (Empfohlen)

- **Internal Track:** Nur für eingeladene Tester verfügbar
- **Beta Track:** Öffentliche Beta - Join via Google Play Console
- **Production:** Vollständiger Release für alle Nutzer

### Direkter Download (APK/AAB)

1. Lade \`app-release.aab\` aus den Release Assets herunter
2. Installiere mit \`bundletool\`:
   \`\`\`bash
   bundletool build-apks --bundle=app-release.aab --output=app.apks
   bundletool install-apks --apks=app.apks
   \`\`\`

⚠️ **Hinweis:** Direkt-Downloads sind nur für Entwicklung/Testing. Production Apps sollten über Google Play bezogen werden.

---

## 📝 Changelog (Vollständig)

### Deutsch (DE)

\`\`\`
${CHANGELOG_DE}
\`\`\`

### English (EN)

\`\`\`
${CHANGELOG_EN}
\`\`\`

---

## 🔗 Links

- [GitHub Repository](${REPO_URL})
- [Issue Tracker](${REPO_URL}/issues)
- [Paperless-ngx](https://github.com/paperless-ngx/paperless-ngx)
- [Dokumentation](${REPO_URL}/tree/main/docs)
- [Google Play Store](https://play.google.com/store/apps/details?id=com.paperless.scanner)

---

## 🙏 Contributors

- @napoleonmm83 - Main development
- Claude Sonnet 4.5 - AI-assisted development and code review

---

**Vollständige Änderungen:** [\`v${PREVIOUS_VERSION}...v${NEW_VERSION}\`](${COMPARISON_URL})

---

## 📊 Statistiken

- **Commits:** $(echo "$COMMITS" | wc -l) commits seit v${PREVIOUS_VERSION}
- **Version Code:** ${VERSION_CODE}

---

**Release erstellt am:** ${RELEASE_DATE}
**Deployed to:** Google Play Console ${TRACK}
EOF

echo ""
echo "✅ Release notes generated successfully"
