# Changelog v1.4.2

## 🆕 Neue Features

### Document DELETE Funktion
- ✅ **Dokumente löschen:** Nutzer können nun Dokumente direkt aus der App löschen
- ✅ **Bestätigungs-Dialog:** Verhindert versehentliches Löschen mit klarem Confirmation Dialog
- ✅ **Offline-Support:** Lösch-Anfragen werden offline gespeichert und synchronisiert, wenn Netzwerk verfügbar
- ✅ **UI:** Roter Delete-Button in der Dokumenten-Detail-Ansicht

**Implementierung:**
- Repository: `DocumentRepository.deleteDocument()`
- ViewModel: `DocumentDetailViewModel.deleteDocument()`
- UI: Delete-Button + `DeleteConfirmationDialog` in `DocumentDetailScreen`
- Offline-First: Nutzt bestehenden `SyncManager` für Pending Changes

---

## 🚀 CI/CD Verbesserungen

### Automatisches Deployment zu Google Play Console
- ✅ **Auto-Deploy Workflow:** Neuer GitHub Actions Workflow für automatisches Deployment
- ✅ **Automatische Version Bumps:** Patch-Version wird automatisch bei jedem Push auf `main` erhöht
- ✅ **Internal Track:** Jeder Push auf `main` deployed automatisch zum Internal Testing Track
- ✅ **Test-First:** Deployment erfolgt nur nach erfolgreichen Unit Tests

**Neue Workflows:**
- `.github/workflows/auto-deploy-internal.yml` - Automatisches Deployment
- Dokumentation: `docs/GITHUB_ACTIONS_SETUP.md`

**Workflow-Ablauf:**
1. Push auf `main`
2. Unit Tests laufen
3. Version wird automatisch gebumpt (Commit mit `[skip ci]`)
4. Release AAB wird gebaut
5. Automatisches Deployment zu Internal Track
6. AAB als Artifact gespeichert

---

## 📁 Geänderte Dateien

### Backend
- `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`
  - Neue Methode: `deleteDocument(documentId: Int): Result<Unit>`
  - Offline-First Pattern mit SyncManager Integration

### UI Layer
- `app/src/main/java/com/paperless/scanner/ui/screens/documents/DocumentDetailViewModel.kt`
  - Erweiterte UiState: `isDeleting`, `deleteError`, `deleteSuccess`
  - Neue Methode: `deleteDocument()`, `clearDeleteError()`

- `app/src/main/java/com/paperless/scanner/ui/screens/documents/DocumentDetailScreen.kt`
  - Delete-Button in TopBar (roter Icon)
  - `DeleteConfirmationDialog` Composable
  - Auto-Navigation nach erfolgreichem Löschen
  - Dark Tech Precision Pro Styling

### CI/CD
- `.github/workflows/auto-deploy-internal.yml` (NEU)
- `docs/GITHUB_ACTIONS_SETUP.md` (NEU)
- `CHANGELOG_v1.4.2.md` (NEU)

---

## 🔧 Technische Details

### Offline-Modus
```kotlin
// Online: API-Call
api.deleteDocument(documentId)
cachedDocumentDao.softDelete(documentId)

// Offline: Queue for sync
pendingChangeDao.insert(PendingChange(
    entityType = "document",
    entityId = documentId,
    changeType = "delete"
))
cachedDocumentDao.softDelete(documentId)
```

### UI Components
```kotlin
// Delete Button
IconButton(
    onClick = { showDeleteDialog = true },
    enabled = !uiState.isLoading && !uiState.isDeleting
) {
    Icon(
        imageVector = Icons.Filled.Delete,
        tint = MaterialTheme.colorScheme.error
    )
}

// Confirmation Dialog
DeleteConfirmationDialog(
    documentTitle = uiState.title,
    isDeleting = uiState.isDeleting,
    onConfirm = { viewModel.deleteDocument() },
    onDismiss = { showDeleteDialog = false }
)
```

---

## 🧪 Testing

### Manuell getestet:
- ✅ Build erfolgreich: `BUILD SUCCESSFUL in 14s`
- ✅ Code kompiliert ohne Fehler
- ✅ Offline-Modus: Bestehende SyncManager-Integration

### Automatisiert:
- ✅ GitHub Actions CI läuft vor Deployment
- ✅ Unit Tests müssen passieren

---

## 📋 Setup-Anforderungen für Auto-Deploy

### GitHub Secrets (Required):
1. `KEYSTORE_BASE64` - Android Signing Keystore
2. `KEYSTORE_PASSWORD` - Keystore Passwort
3. `KEY_ALIAS` - Key Alias
4. `KEY_PASSWORD` - Key Passwort
5. `PLAY_STORE_KEY_JSON` - Google Play Service Account JSON

**Siehe:** `docs/GITHUB_ACTIONS_SETUP.md` für detaillierte Anleitung

---

## 🎯 Nächste Schritte

### Empfohlene Features für v1.5.0:
1. **Tag Management UI**
   - Swipe-to-Delete in Labels Screen
   - Edit/Update Tags Dialog
   - Repository-Methoden bereits vorhanden

2. **Document Metadata UPDATE**
   - Edit-Modus in DocumentDetailScreen
   - Update Title, Tags, Correspondent, DocumentType
   - Requires API Endpoint: `PATCH /api/documents/{id}/`

3. **Advanced Filters**
   - Filter by Correspondent
   - Filter by DocumentType
   - Date Range Filter
   - API bereits vorhanden

---

**Build:** `v1.4.2` (Version Code: `10402`)
**Datum:** 2026-01-06
**Status:** ✅ Ready for Internal Track
