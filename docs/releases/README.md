# Manual Release Notes

This directory contains manually crafted GitHub release notes for specific versions.

## Purpose

When you want to create a comprehensive, manually written release note instead of the automatically generated one, place a markdown file here named `v{VERSION}.md`.

## How It Works

**GitHub Actions Workflow:**
1. The `auto-deploy-internal.yml` workflow calls `scripts/generate-release-notes.sh`
2. The script first checks if `docs/releases/v{VERSION}.md` exists
3. **If found:** Uses the manual release notes (complete control)
4. **If not found:** Generates structured release notes automatically from:
   - Git commits since last tag (parsed into features/fixes/improvements)
   - Fastlane changelogs (DE + EN)
   - Template structure from `docs/RELEASE_NOTES_TEMPLATE.md`

## When to Use Manual Release Notes

Use manual release notes for:
- **Major releases** (v2.0.0, v3.0.0) - Requires comprehensive documentation
- **Breaking changes** - Need detailed migration guides
- **Complex features** - Require screenshots, GIFs, detailed explanations
- **Marketing releases** - Beta or Production releases with public audience

Let automatic generation handle:
- **Internal testing releases** - Quick iteration, no public audience
- **Patch releases** - Simple bug fixes
- **CI/CD releases** - Automated continuous delivery

## Creating Manual Release Notes

### Step 1: Copy Template

```bash
# Create new release notes from template
cp docs/RELEASE_NOTES_TEMPLATE.md docs/releases/v1.5.0.md
```

### Step 2: Fill All Sections

Open `docs/releases/v{VERSION}.md` and fill out:

- ✅ Header (version, date, track)
- ✅ Highlights (1-3 sentences)
- ✅ Features (with "why important")
- ✅ Fixes (with problem descriptions)
- ✅ Improvements
- ✅ Technical Changes (optional)
- ✅ Breaking Changes (CRITICAL if present)
- ✅ Security (optional)
- ✅ Installation instructions
- ✅ Changelog DE + EN (from fastlane metadata)
- ✅ Links
- ✅ Contributors
- ✅ Comparison link
- ✅ Screenshots (if UI changes)

### Step 3: Review Checklist

Use the checklist in `docs/RELEASE_NOTES_TEMPLATE.md` to ensure completeness.

### Step 4: Commit Before Release

```bash
git add docs/releases/v1.5.0.md
git commit -m "docs: add manual release notes for v1.5.0"
git push
```

**Important:** Commit the manual release notes BEFORE the workflow runs. The script checks for the file during the GitHub Actions deployment.

## Example Workflow

### Scenario: Planning a major v2.0.0 release

```bash
# 1. Create manual release notes ahead of time
cp docs/RELEASE_NOTES_TEMPLATE.md docs/releases/v2.0.0.md

# 2. Fill out all sections with detailed information
vim docs/releases/v2.0.0.md

# 3. Add screenshots to release assets
# (Upload to GitHub or use placeholder URLs)

# 4. Commit the manual notes
git add docs/releases/v2.0.0.md
git commit -m "docs: add comprehensive release notes for v2.0.0"

# 5. Make the release (manual version bump or let workflow do it)
git push origin main

# 6. GitHub Actions will use your manual notes instead of generating
```

## Directory Structure

```
docs/releases/
├── README.md           # This file
├── v1.5.0.md          # Manual release notes for v1.5.0 (example)
├── v2.0.0.md          # Manual release notes for v2.0.0 (example)
└── ...                # Future manual releases
```

## Tips

- Use `docs/RELEASE_NOTES_EXAMPLE.md` as inspiration
- Follow `docs/RELEASE_NOTES_QUICK_REFERENCE.md` for best practices
- For screenshots, upload to GitHub Issues or use GitHub release assets
- Always link to relevant issues/PRs (#123)
- Include breaking changes prominently
- Keep user-focused language (not technical jargon)

## Related Documentation

- **Template:** `docs/RELEASE_NOTES_TEMPLATE.md`
- **Example:** `docs/RELEASE_NOTES_EXAMPLE.md`
- **Quick Reference:** `docs/RELEASE_NOTES_QUICK_REFERENCE.md`
- **Generation Script:** `scripts/generate-release-notes.sh`
- **Workflow:** `.github/workflows/auto-deploy-internal.yml`
