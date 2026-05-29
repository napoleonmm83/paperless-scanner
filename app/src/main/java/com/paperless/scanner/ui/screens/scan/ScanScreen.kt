package com.paperless.scanner.ui.screens.scan

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import com.paperless.scanner.ui.components.SnackbarIcon
import com.paperless.scanner.ui.components.showTypedSnackbar
import com.paperless.scanner.ui.screens.scan.components.ModeSelectionContent
import com.paperless.scanner.ui.screens.scan.components.MultiPageContent
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.paperless.scanner.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Page limit increased from 20 to 100 based on tested capacity (90-100 pages)
// App supports large batches: storage validation, 50MB per file limit, tested for crashes
// See: ByteRover context - "Large Batch Validation (Task 98/100)"
internal const val MAX_PAGES = 100

@Composable
fun ScanScreen(
    initialPageUris: List<Uri> = emptyList(),
    initialScanAction: String? = null,
    onDocumentScanned: (Uri) -> Unit,
    onMultipleDocumentsScanned: (List<Uri>, Boolean) -> Unit,
    onStepByStepMetadata: (List<Uri>) -> Unit,
    viewModel: ScanViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val wifiRequired by viewModel.wifiRequired.collectAsState()
    val isWifiConnected by viewModel.isWifiConnected.collectAsState()
    val usesCloudflare by viewModel.usesCloudflare.collectAsState()
    val uploadAsSingleDocument by viewModel.uploadAsSingleDocument.collectAsState()
    var showAddMoreDialog by remember { mutableStateOf(false) }
    var showMetadataChoiceDialog by remember { mutableStateOf(false) }

    // Initialize ViewModel with route arguments (survives AppLock unlock)
    // CRITICAL: Only initialize from route args if ViewModel doesn't already have pages
    // This prevents stale route arguments from overwriting correct SavedStateHandle data after AppLock
    LaunchedEffect(initialPageUris) {
        if (initialPageUris.isNotEmpty() && !uiState.hasPages) {
            // ViewModel is empty → initialize from route arguments
            viewModel.addPages(initialPageUris)
        }
        // If ViewModel already has pages (e.g., after AppLock unlock), trust SavedStateHandle as source of truth
    }

    // Page URIs are mirrored to AppLockRouteArgsHolder by ScanViewModel itself
    // (single source of truth, #30) — no Navigation SavedStateHandle sync needed here.

    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(MAX_PAGES)
            .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()
    }

    val scanner = remember {
        GmsDocumentScanning.getClient(scannerOptions)
    }

    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // SECURITY: CRITICAL - Resume timeout IMMEDIATELY when scanner returns
        // This MUST happen BEFORE any other logic (even error handling)
        // Ensures timeout resumes correctly on: Success, Cancel, Error, Crash
        viewModel.appLockManager.resumeFromScanner()

        if (result.resultCode == Activity.RESULT_OK) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pageUris = scanningResult?.pages?.mapNotNull { it.imageUri } ?: emptyList()
            if (pageUris.isNotEmpty()) {
                viewModel.addPages(pageUris, PageSource.SCANNER)
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        // CRITICAL: Always resume, even if user canceled (uris is empty)
        viewModel.appLockManager.resumeFromFilePicker()

        if (uris.isNotEmpty()) {
            // CRITICAL: Copy files to local storage IMMEDIATELY while we still have permission
            // Content URIs lose permissions when passed through navigation
            scope.launch(Dispatchers.IO) {
                // Show processing indicator while copying files (before addPages)
                withContext(Dispatchers.Main) {
                    if (uris.size > 5) {
                        viewModel.setProcessing(true)
                    }
                }

                val localUris = uris.mapNotNull { uri ->
                    FileUtils.copyToLocalStorage(context, uri)
                }

                withContext(Dispatchers.Main) {
                    if (localUris.isNotEmpty()) {
                        // addPages will continue showing processing state if needed
                        viewModel.addPages(localUris, PageSource.GALLERY)
                    } else {
                        // No files copied - reset processing state
                        viewModel.setProcessing(false)
                    }
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        // CRITICAL: Always resume, even if user canceled (uris is empty)
        viewModel.appLockManager.resumeFromFilePicker()

        if (uris.isNotEmpty()) {
            // CRITICAL: Copy files to local storage IMMEDIATELY while we still have permission
            // Content URIs lose permissions when passed through navigation
            scope.launch(Dispatchers.IO) {
                // Show processing indicator while copying files (before addPages)
                withContext(Dispatchers.Main) {
                    if (uris.size > 5) {
                        viewModel.setProcessing(true)
                    }
                }

                val localUris = uris.mapNotNull { uri ->
                    FileUtils.copyToLocalStorage(context, uri)
                }

                withContext(Dispatchers.Main) {
                    if (localUris.isNotEmpty()) {
                        // addPages will continue showing processing state if needed
                        viewModel.addPages(localUris, PageSource.FILES)
                    } else {
                        // No files copied - reset processing state
                        viewModel.setProcessing(false)
                    }
                }
            }
        }
    }

    // Handle undo snackbar for removed pages
    LaunchedEffect(uiState.lastRemovedPage) {
        uiState.lastRemovedPage?.let { removedInfo ->
            val result = snackbarHostState.showTypedSnackbar(
                message = context.getString(R.string.scan_page_removed, removedInfo.page.pageNumber),
                icon = SnackbarIcon.INFO,
                actionLabel = context.getString(R.string.scan_undo),
                withDismissAction = true
            )
            when (result) {
                SnackbarResult.ActionPerformed -> {
                    viewModel.undoRemovePage()
                }
                SnackbarResult.Dismissed -> {
                    viewModel.clearLastRemovedPage()
                }
            }
        }
    }

    fun startScanner() {
        scanner.getStartScanIntent(context as Activity)
            .addOnSuccessListener { intentSender ->
                // SECURITY: Suspend timeout IMMEDIATELY BEFORE launching scanner
                // This must be as close as possible to the launch() call to prevent race condition
                // between suspend() and onStop() lifecycle callback
                viewModel.appLockManager.suspendForScanner()

                scannerLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener { e ->
                // No need to resume here - we never suspended in this failure path
                scope.launch {
                    snackbarHostState.showTypedSnackbar(
                        message = context.getString(R.string.scan_scanner_error, e.message ?: ""),
                        icon = SnackbarIcon.ERROR
                    )
                }
            }
    }

    // Auto-trigger scan action from deep link (widget tap)
    // Uses rememberSaveable to ensure action fires only once, even across recompositions
    var deepLinkActionConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialScanAction, deepLinkActionConsumed) {
        if (initialScanAction != null && !deepLinkActionConsumed && !uiState.hasPages) {
            deepLinkActionConsumed = true
            android.util.Log.d("ScanScreen", "Auto-triggering scan action from deep link: $initialScanAction")
            when (initialScanAction) {
                "camera" -> startScanner()
                "gallery" -> {
                    viewModel.appLockManager.suspendForFilePicker()
                    photoPickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                }
                "file" -> {
                    viewModel.appLockManager.suspendForFilePicker()
                    filePickerLauncher.launch(
                        arrayOf("application/pdf", "image/*")
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            if (uiState.hasPages) {
                // Multi-Page View with scanned pages
                MultiPageContent(
                    uiState = uiState,
                    wifiRequired = wifiRequired,
                    isWifiConnected = isWifiConnected,
                    usesCloudflare = usesCloudflare,
                    uploadAsSingleDocument = uploadAsSingleDocument,
                    onUploadModeChange = { viewModel.setUploadAsSingleDocument(it) },
                    onUseAnywayClick = { viewModel.overrideWifiOnlyForSession() },
                    onAddMore = { showAddMoreDialog = true },
                    onRemovePage = { viewModel.removePage(it) },
                    onRotatePage = { viewModel.rotatePage(it) },
                    onCropPage = { pageId, cropRect -> viewModel.cropPage(pageId, cropRect) },
                    onMovePage = { from, to -> viewModel.movePage(from, to) },
                    onClear = { viewModel.clearPages() },
                    onContinue = {
                        // Get rotated URIs in coroutine scope
                        scope.launch {
                            val uris = viewModel.getRotatedPageUris()
                            // DO NOT clear pages here - pages should persist until upload succeeds
                            // This allows user to navigate back and add more pages or make changes
                            if (uris.size == 1) {
                                onDocumentScanned(uris.first())
                            } else if (uploadAsSingleDocument) {
                                // Single PDF: Direct to MultiPageUploadScreen
                                onMultipleDocumentsScanned(uris, true)
                            } else {
                                // Individual Documents: Show metadata choice dialog
                                showMetadataChoiceDialog = true
                            }
                        }
                    }
                )
            } else {
                // Mode Selection Screen (new design)
                ModeSelectionContent(
                    onScanClick = { startScanner() },
                    onGalleryClick = {
                        // CRITICAL: Suspend timeout BEFORE opening picker
                        viewModel.appLockManager.suspendForFilePicker()
                        photoPickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onFilesClick = {
                        // CRITICAL: Suspend timeout BEFORE opening picker
                        viewModel.appLockManager.suspendForFilePicker()
                        filePickerLauncher.launch(
                            arrayOf("application/pdf", "image/*")
                        )
                    }
                )
            }
        }

        // Snackbar Host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Processing/Loading Overlay
        if (uiState.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    .clickable(enabled = false) { }, // Block interactions
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 6.dp
                    )
                    Text(
                        text = context.getString(R.string.scan_processing_files),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = context.getString(R.string.scan_please_wait),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Add More Source Dialog
        if (showAddMoreDialog) {
            AddMoreSourceDialog(
                onDismiss = { showAddMoreDialog = false },
                onScannerSelected = {
                    showAddMoreDialog = false
                    startScanner()
                },
                onGallerySelected = {
                    showAddMoreDialog = false
                    // CRITICAL: Suspend timeout BEFORE opening picker
                    viewModel.appLockManager.suspendForFilePicker()
                    photoPickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                onFilesSelected = {
                    showAddMoreDialog = false
                    // CRITICAL: Suspend timeout BEFORE opening picker
                    viewModel.appLockManager.suspendForFilePicker()
                    filePickerLauncher.launch(
                        arrayOf("application/pdf", "image/*")
                    )
                }
            )
        }

        // Metadata Choice Dialog (Individual Documents only)
        if (showMetadataChoiceDialog) {
            MetadataChoiceDialog(
                onDismiss = { showMetadataChoiceDialog = false },
                onSameForAll = {
                    showMetadataChoiceDialog = false
                    // Navigate to MultiPageUploadScreen with CURRENT upload mode
                    // CRITICAL: Use uploadAsSingleDocument state, not hardcoded false
                    // If user selected "Als separate Dokumente", this will be false
                    // If user selected "Als einzelnes Dokument", this will be true
                    scope.launch {
                        val uris = viewModel.getRotatedPageUris()
                        onMultipleDocumentsScanned(uris, uploadAsSingleDocument)
                    }
                },
                onIndividual = {
                    showMetadataChoiceDialog = false
                    // Navigate to StepByStepMetadataScreen for per-page metadata editing
                    scope.launch {
                        val uris = viewModel.getRotatedPageUris()
                        onStepByStepMetadata(uris)
                    }
                }
            )
        }
    }

}
