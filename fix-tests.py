#!/usr/bin/env python3
import re

with open('app/src/test/java/com/paperless/scanner/ui/screens/home/HomeViewModelTest.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Remove turbine import
content = content.replace('import app.cash.turbine.test\n', '')

# Pattern 1: Replace turbine test blocks with direct value access
# From: viewModel.uiState.test {\n            val state = awaitItem()
# To: val state = viewModel.uiState.value
pattern1 = r'viewModel\.uiState\.test \{\s+val state = awaitItem\(\)\s+'
content = re.sub(pattern1, 'val state = viewModel.uiState.value\n        ', content)

# Pattern 2: Remove closing braces after assertions (from turbine blocks)
# This is tricky - need to find standalone } after assertions
# For now, let's do it manually for each test method

# Save
with open('app/src/test/java/com/paperless/scanner/ui/screens/home/HomeViewModelTest.kt', 'w', encoding='utf-8') as f:
    f.write(content)

print("Fixed!")
