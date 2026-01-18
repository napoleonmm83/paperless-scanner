# Contributing to Paperless Scanner

Thank you for your interest in contributing to Paperless Scanner! This document provides guidelines and instructions for contributing.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
- [Reporting Bugs](#reporting-bugs)
- [Suggesting Features](#suggesting-features)
- [Contributing Code](#contributing-code)
- [Development Setup](#development-setup)
- [Coding Standards](#coding-standards)
- [Commit Message Guidelines](#commit-message-guidelines)
- [Pull Request Process](#pull-request-process)

---

## Code of Conduct

This project follows a simple code of conduct:

- **Be respectful** - Treat everyone with respect and kindness
- **Be constructive** - Provide helpful feedback and suggestions
- **Be patient** - Remember that maintainers and contributors volunteer their time
- **Be collaborative** - Work together to improve the project

## How Can I Contribute?

There are many ways to contribute to Paperless Scanner:

### 🐛 Reporting Bugs

Found a bug? [Open a bug report](https://github.com/napoleonmm83/paperless-scanner/issues/new?template=bug_report.md)

**Before reporting:**
- Check if the issue already exists
- Make sure you're using the latest version
- Verify it's not a Paperless-ngx server issue

**What to include:**
- Clear description of the problem
- Steps to reproduce
- Expected vs actual behavior
- Device info (model, Android version, app version)
- Screenshots or screen recordings if applicable

### 💡 Suggesting Features

Have an idea? [Open a feature request](https://github.com/napoleonmm83/paperless-scanner/issues/new?template=feature_request.md)

**Before suggesting:**
- Check if it's already been requested
- Consider if it fits the project's scope
- Think about how it would benefit most users

**What to include:**
- Clear description of the feature
- Problem it solves or value it adds
- Possible implementation approach
- Use case examples

### 📖 Improving Documentation

Documentation improvements are always welcome:

- Fix typos or unclear explanations
- Add examples or clarifications
- Translate documentation to other languages
- Update outdated information

### 💻 Contributing Code

Want to write code? Great! See the sections below for guidelines.

---

## Reporting Bugs

### Security Vulnerabilities

**DO NOT** open a public issue for security vulnerabilities.

Instead, email the maintainers directly at: [Contact via GitHub](https://github.com/napoleonmm83)

### Regular Bugs

Use the [Bug Report template](https://github.com/napoleonmm83/paperless-scanner/issues/new?template=bug_report.md) and include:

1. **Environment**
   - Device model
   - Android version
   - App version
   - Paperless-ngx version

2. **Reproduction Steps**
   - Detailed step-by-step instructions
   - Any special configuration or setup

3. **Expected Behavior**
   - What should happen

4. **Actual Behavior**
   - What actually happens
   - Error messages or stack traces

5. **Screenshots/Videos**
   - Visual proof of the issue
   - Screen recordings for complex issues

---

## Suggesting Features

Use the [Feature Request template](https://github.com/napoleonmm83/paperless-scanner/issues/new?template=feature_request.md).

### Feature Types

Consider which category your feature falls into:

- **Core Features** (Free) - Basic scanning, upload, tagging
- **Premium Features** - AI-powered suggestions and enhancements
- **Power-User Features** - Advanced configuration and self-hosted options
- **UI/UX Improvements** - Better user experience
- **Performance** - Speed and efficiency improvements

### Feature Roadmap

We prioritize features based on:

1. **User demand** - Number of 👍 reactions on the issue
2. **Impact** - How many users benefit
3. **Effort** - Development and maintenance cost
4. **Alignment** - Fits project vision and scope

---

## Contributing Code

### Before You Start

1. **Check existing issues** - Someone might already be working on it
2. **Open an issue first** - Discuss the change before writing code
3. **Get approval** - Wait for maintainer feedback on large changes
4. **Start small** - First contributions should be simple

### Development Setup

#### Prerequisites

- **JDK 21** (not JDK 24+)
- **Android Studio** (latest stable)
- **Git**
- **A test device or emulator with Google Play Services**

#### Setup Steps

```bash
# 1. Fork the repository on GitHub
# 2. Clone your fork
git clone https://github.com/YOUR_USERNAME/paperless-scanner.git
cd "paperless client"

# 3. Add upstream remote
git remote add upstream https://github.com/napoleonmm83/paperless-scanner.git

# 4. Create a feature branch
git checkout -b feature/your-feature-name

# 5. Open in Android Studio
# File → Open → Select "paperless client" folder

# 6. Let Gradle sync complete
# Wait for all dependencies to download

# 7. Run the app
# Select app configuration → Run on device/emulator
```

#### Local Testing

Before committing, run local CI checks:

```bash
# Full CI validation (REQUIRED before push)
./scripts/validate-ci.sh

# Quick check (syntax only, faster)
./scripts/validate-ci.sh --quick

# With verbose output
./scripts/validate-ci.sh --verbose
```

**IMPORTANT:** All checks must pass before pushing!

---

## Coding Standards

### Kotlin Style

Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html):

```kotlin
// Use data object for sealed class singletons
sealed class UiState {
    data object Loading : UiState()
    data class Success(val data: List<Document>) : UiState()
    data class Error(val message: String) : UiState()
}

// Prefer StateFlow over LiveData
class MyViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
}

// Use suspend functions for async operations
suspend fun fetchDocuments(): Result<List<Document>> {
    return withContext(Dispatchers.IO) {
        try {
            val response = api.getDocuments()
            Result.success(response.results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// Repository methods return Result<T>
interface DocumentRepository {
    suspend fun uploadDocument(document: Document): Result<String>
}
```

### Jetpack Compose Guidelines

```kotlin
// Stateless Composables with state hoisting
@Composable
fun DocumentCard(
    document: Document,
    onDocumentClick: (Document) -> Unit,
    modifier: Modifier = Modifier
) {
    // UI implementation
}

// Use remember for internal state
@Composable
fun ExpandableCard(content: String) {
    var expanded by remember { mutableStateOf(false) }
    // Implementation
}

// Use rememberSaveable for state that survives config changes
@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    // Implementation
}
```

### Architecture Patterns

- **MVVM** - ViewModel + UI State + Events
- **Repository Pattern** - Separate data layer from UI
- **Clean Architecture** - Data → Domain → Presentation
- **Single Responsibility** - Each class has one clear purpose
- **Dependency Injection** - Use Hilt for all dependencies

### Code Organization

```
feature/
├── FeatureScreen.kt        # Composable UI
├── FeatureViewModel.kt     # Business logic + state
├── FeatureUiState.kt       # UI state sealed class
└── components/             # Feature-specific components
```

### Testing

Write tests for:

- **ViewModels** - State changes and business logic
- **Repositories** - Data operations and error handling
- **Utils** - Utility functions and helpers

```kotlin
class DocumentViewModelTest {
    @Test
    fun `uploadDocument updates state to Success`() = runTest {
        // Arrange
        val viewModel = DocumentViewModel(mockRepository)

        // Act
        viewModel.uploadDocument(testDocument)

        // Assert
        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
    }
}
```

---

## Commit Message Guidelines

We follow [Conventional Commits](https://www.conventionalcommits.org/):

### Format

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Types

- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, no logic change)
- `refactor`: Code refactoring
- `perf`: Performance improvements
- `test`: Adding or updating tests
- `chore`: Build process, tooling, dependencies

### Examples

```bash
# Feature
feat(upload): add multi-page document support

# Bug fix
fix(login): handle expired tokens correctly

# Documentation
docs(readme): update installation instructions

# Refactoring
refactor(scan): extract camera logic to separate class

# Performance
perf(upload): optimize PDF compression

# Breaking change
feat(api)!: migrate to Paperless-ngx v2.0 API

BREAKING CHANGE: Requires Paperless-ngx v2.0 or later
```

### Best Practices

- Use present tense ("add feature" not "added feature")
- Use imperative mood ("move cursor to..." not "moves cursor to...")
- Keep first line under 72 characters
- Reference issues with `#123` or `Fixes #123`
- Add `Co-Authored-By:` for pair programming

---

## Pull Request Process

### 1. Prepare Your Changes

```bash
# Make sure you're up to date
git fetch upstream
git rebase upstream/main

# Run local CI checks
./scripts/validate-ci.sh

# Commit your changes
git add .
git commit -m "feat: your feature description"

# Push to your fork
git push origin feature/your-feature-name
```

### 2. Create Pull Request

1. Go to the [repository on GitHub](https://github.com/napoleonmm83/paperless-scanner)
2. Click "Pull Requests" → "New Pull Request"
3. Click "compare across forks"
4. Select your fork and branch
5. Fill out the PR template

### 3. PR Template

```markdown
## Description

<!-- Clear description of what this PR does -->

## Related Issue

<!-- Link to the issue this PR addresses -->
Fixes #123

## Type of Change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update

## Testing

<!-- Describe the tests you ran -->

- [ ] Unit tests pass
- [ ] UI tests pass (if applicable)
- [ ] Manual testing on device

**Test Configuration:**
- Device: Pixel 7
- Android Version: 14
- Paperless-ngx Version: v2.0.0

## Screenshots

<!-- If UI changes, add before/after screenshots -->

## Checklist

- [ ] My code follows the project's coding standards
- [ ] I have run `./scripts/validate-ci.sh` and all checks pass
- [ ] I have commented my code, particularly in hard-to-understand areas
- [ ] I have updated the documentation accordingly
- [ ] My changes generate no new warnings
- [ ] I have added tests that prove my fix is effective or that my feature works
- [ ] New and existing unit tests pass locally
```

### 4. Code Review

- **Be patient** - Reviews take time
- **Respond to feedback** - Address comments promptly
- **Ask questions** - If feedback is unclear, ask!
- **Be open to changes** - Maintainers know the codebase best

### 5. After Approval

Once approved:

1. Maintainer will merge your PR
2. Your changes will be in the next release
3. You'll be credited in the release notes!

---

## Questions?

- **General questions:** [Open a question issue](https://github.com/napoleonmm83/paperless-scanner/issues/new?template=question.md)
- **Development help:** Check [docs/TECHNICAL.md](docs/TECHNICAL.md)
- **Community:** Join [r/paperlessngx](https://reddit.com/r/paperlessngx)

---

## Thank You!

Every contribution, no matter how small, is valuable. Thank you for helping make Paperless Scanner better!

---

**Happy Coding! 🚀**
