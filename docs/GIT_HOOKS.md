# Git Hooks Setup

This project uses Git hooks to improve the developer experience and prevent common issues.

---

## 📋 Overview

### Pre-Push Hook

**Purpose:** Automatically rebase with remote changes before push to prevent "failed to push some refs" errors.

**Why needed:** GitHub Actions automatically bumps version and pushes to `main` after deployment. This can cause push failures for local commits made during deployment.

**What it does:**
1. Fetches remote changes before push
2. Detects if remote is ahead (from auto-bump)
3. Automatically rebases local commits on top of remote
4. Continues with push if rebase succeeds
5. Aborts push and shows error if rebase fails

---

## 🔧 Installation

### Option 1: Automatic (Recommended)

The hook should already be installed at `.git/hooks/pre-push` if you cloned this repository recently.

**Verify installation:**
```bash
ls -la .git/hooks/pre-push
```

If the file exists, you're done! ✅

### Option 2: Manual Installation

If the hook is missing, create it manually:

```bash
# Copy hook to Git hooks directory
cat > .git/hooks/pre-push << 'EOF'
#!/bin/bash
# Pre-Push Hook: Auto-Rebase with Remote Changes
# Prevents "failed to push some refs" errors from GitHub Actions version bumps

echo ""
echo "🔍 Checking for remote changes before push..."

# Fetch remote silently
git fetch origin main --quiet 2>&1 | grep -v "warning: Pulling without" || true

# Get commit hashes
LOCAL=$(git rev-parse @)
REMOTE=$(git rev-parse origin/main)
BASE=$(git merge-base @ origin/main)

if [ "$LOCAL" = "$REMOTE" ]; then
    # Up to date
    echo "✅ Local branch is up to date with remote."
elif [ "$LOCAL" = "$BASE" ]; then
    # Need to pull
    echo "⚠️  Remote has new commits (likely auto-bump from GitHub Actions)."
    echo "🔄 Auto-rebasing with remote changes..."

    if git pull --rebase origin main --autostash --quiet; then
        echo "✅ Rebase successful! Continuing with push..."
    else
        echo ""
        echo "❌ Rebase failed! Please resolve conflicts manually:"
        echo "   git rebase --abort"
        echo "   git pull --rebase origin main"
        echo "   git push"
        exit 1
    fi
elif [ "$REMOTE" = "$BASE" ]; then
    # Ahead of remote (normal case)
    echo "✅ Local branch is ahead of remote. Proceeding with push..."
else
    # Diverged
    echo "⚠️  Local and remote branches have diverged."
    echo "🔄 Auto-rebasing with remote changes..."

    if git pull --rebase origin main --autostash --quiet; then
        echo "✅ Rebase successful! Continuing with push..."
    else
        echo ""
        echo "❌ Rebase failed! Please resolve conflicts manually:"
        echo "   git rebase --abort"
        echo "   git pull --rebase origin main"
        echo "   git push"
        exit 1
    fi
fi

echo ""
EOF

# Make executable (Linux/macOS)
chmod +x .git/hooks/pre-push

# Windows: Git will handle execution automatically
```

---

## 🧪 Testing

Test the hook by making a commit and pushing:

```bash
# Make a test commit
echo "test" >> test.txt
git add test.txt
git commit -m "test: verify pre-push hook"

# Push (hook should run automatically)
git push
```

**Expected output:**
```
🔍 Checking for remote changes before push...
✅ Local branch is ahead of remote. Proceeding with push...
```

If you see this message, the hook is working correctly! ✅

---

## 🔄 Common Scenarios

### Scenario 1: Normal Push (No Remote Changes)

```
🔍 Checking for remote changes before push...
✅ Local branch is ahead of remote. Proceeding with push...
```

**Action:** Push continues normally.

### Scenario 2: Remote Changed (Auto-Bump)

```
🔍 Checking for remote changes before push...
⚠️  Remote has new commits (likely auto-bump from GitHub Actions).
🔄 Auto-rebasing with remote changes...
✅ Rebase successful! Continuing with push...
```

**Action:** Hook automatically rebased and push continues.

### Scenario 3: Rebase Conflict

```
🔍 Checking for remote changes before push...
⚠️  Remote has new commits (likely auto-bump from GitHub Actions).
🔄 Auto-rebasing with remote changes...

❌ Rebase failed! Please resolve conflicts manually:
   git rebase --abort
   git pull --rebase origin main
   git push
```

**Action:** Push is aborted. Resolve conflicts manually.

**Resolution steps:**
```bash
# 1. Abort current rebase
git rebase --abort

# 2. Pull with rebase
git pull --rebase origin main

# 3. Resolve conflicts (if any)
git status
# Edit conflicted files
git add <resolved-files>
git rebase --continue

# 4. Push again
git push
```

---

## ⚙️ How It Works

### Detection Logic

The hook uses Git references to detect the state:

```bash
LOCAL=$(git rev-parse @)           # Current HEAD
REMOTE=$(git rev-parse origin/main) # Remote main HEAD
BASE=$(git merge-base @ origin/main) # Common ancestor
```

**State Detection:**
- `LOCAL == REMOTE` → Up to date
- `LOCAL == BASE` → Remote is ahead (need to pull)
- `REMOTE == BASE` → Local is ahead (normal push)
- Otherwise → Branches diverged (need to rebase)

### Rebase with Autostash

```bash
git pull --rebase origin main --autostash
```

**Flags:**
- `--rebase`: Rebase local commits on top of remote
- `--autostash`: Temporarily stash uncommitted changes during rebase
- `--quiet`: Suppress unnecessary output

---

## 🚫 Disabling the Hook

If you need to temporarily disable the hook:

```bash
# Option 1: Use --no-verify flag
git push --no-verify

# Option 2: Rename the hook
mv .git/hooks/pre-push .git/hooks/pre-push.disabled

# Option 3: Delete the hook
rm .git/hooks/pre-push
```

**⚠️ Warning:** Disabling the hook means you'll need to manually rebase when remote changes occur.

---

## 📚 Related Documentation

- [Auto Deploy Workflow](.github/workflows/auto-deploy-internal.yml) - GitHub Actions deployment
- [Version Management](../version.properties) - Version bump configuration
- [Best Practices](BEST_PRACTICES.md) - Development guidelines

---

## 🐛 Troubleshooting

### Hook doesn't run

**Symptoms:** Push succeeds immediately without hook output.

**Solution:**
1. Check if hook exists: `ls -la .git/hooks/pre-push`
2. Check if hook is executable: `git ls-files --stage .git/hooks/pre-push`
3. Reinstall hook using [Manual Installation](#option-2-manual-installation)

### Hook fails with "command not found"

**Symptoms:** Hook errors with "bash: command not found" or similar.

**Solution:**
- Windows: Ensure Git Bash is installed (comes with Git for Windows)
- Linux/macOS: Hook requires bash, git, and standard Unix tools

### Hook always fails with rebase conflict

**Symptoms:** Every push fails with rebase conflict, even for unrelated files.

**Solution:**
1. Check if you have uncommitted changes: `git status`
2. Stash changes: `git stash`
3. Pull with rebase: `git pull --rebase origin main`
4. Pop stash: `git stash pop`
5. Resolve conflicts if any
6. Push again

---

## 📖 References

**Best Practices:**
- [Auto Version Bumps with GitHub Actions](https://medium.com/swlh/bump-bump-bump-d0dab616e83)
- [Avoid workflow loops on GitHub Actions](https://blog.shounakmulay.dev/avoid-workflow-loops-on-github-actions-when-committing-to-a-protected-branch)
- [Workflow infinite loop discussions](https://github.com/orgs/community/discussions/26970)

**Implementation:**
- Implemented: 2026-01-26
- Last Updated: 2026-01-26
- Maintainer: @napoleonmm83
