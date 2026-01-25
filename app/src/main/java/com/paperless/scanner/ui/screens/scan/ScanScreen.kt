package com.paperless.scanner.ui.screens.scan

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.scan.AddMoreSourceDialog
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import coil.compose.AsyncImage
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

private const val MAX_PAGES = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    initialPageUris: List<Uri> = emptyList(),
    navBackStackEntry: androidx.navigation.NavBackStackEntry? = null,
    onDocumentScanned: (Uri) -> Unit,
    onMultipleDocumentsScanned: (List<Uri>, Boolean) -> Unit,
    viewModel: ScanViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val wifiRequired by viewModel.wifiRequired.collectAsState()
    val isWifiConnected by viewModel.isWifiConnected.collectAsState()
    val uploadAsSingleDocument by viewModel.uploadAsSingleDocument.collectAsState()
    var showAddMoreDialog by remember { mutableStateOf(false) }

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

    // CRITICAL: Sync pages to Navigation SavedStateHandle for AppLock route reconstruction
    // This is SEPARATE from ViewModel SavedStateHandle (which is used for process death)
    // AppLockNavigationInterceptor reads from backStackEntry.savedStateHandle, not ViewModel.savedStateHandle
    LaunchedEffect(uiState.pages) {
        navBackStackEntry?.savedStateHandle?.let { savedState ->
            if (uiState.pages.isEmpty()) {
                savedState["pageUris"] = null
            } else {
                val urisString = uiState.pages.joinToString("|") { it.uri.toString() }
                savedState["pageUris"] = urisString
                android.util.Log.d("ScanScreen", "Synced ${uiState.pages.size} pages to Navigation SavedStateHandle: $urisString")
            }
        }
    }

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
                val localUris = uris.mapNotNull { uri ->
                    FileUtils.copyToLocalStorage(context, uri)
                }
                if (localUris.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        viewModel.addPages(localUris, PageSource.GALLERY)
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
                val localUris = uris.mapNotNull { uri ->
                    FileUtils.copyToLocalStorage(context, uri)
                }
                if (localUris.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        viewModel.addPages(localUris, PageSource.FILES)
                    }
                }
            }
        }
    }

    // Handle undo snackbar for removed pages
    LaunchedEffect(uiState.lastRemovedPage) {
        uiState.lastRemovedPage?.let { removedInfo ->
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.scan_page_removed, removedInfo.page.pageNumber),
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
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.scan_scanner_error, e.message ?: "")
                    )
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
                            } else {
                                onMultipleDocumentsScanned(uris, uploadAsSingleDocument)
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
    }

}

@Composable
private fun ModeSelectionContent(
    onScanClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onFilesClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.scan_new_document_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = stringResource(R.string.scan_choose_option),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Options Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Scan option - uses onPrimary for proper contrast in both themes
            ScanOptionCard(
                icon = Icons.Filled.CameraAlt,
                label = stringResource(R.string.scan_option_scan),
                backgroundColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = onScanClick,
                modifier = Modifier.weight(1f)
            )

            // Gallery option - always light blue, needs dark text
            ScanOptionCard(
                icon = Icons.Filled.PhotoLibrary,
                label = stringResource(R.string.scan_option_gallery),
                backgroundColor = Color(0xFF8DD7FF),
                contentColor = Color.Black,
                onClick = onGalleryClick,
                modifier = Modifier.weight(1f)
            )

            // Files option - always light purple, needs dark text
            ScanOptionCard(
                icon = Icons.Filled.FolderOpen,
                label = stringResource(R.string.scan_option_files),
                backgroundColor = Color(0xFFB88DFF),
                contentColor = Color.Black,
                onClick = onFilesClick,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(100.dp)) // Space for bottom nav
    }
}

@Composable
private fun ScanOptionCard(
    icon: ImageVector,
    label: String,
    backgroundColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .aspectRatio(0.85f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(contentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(28.dp),
                    tint = contentColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
        }
    }
}

@Composable
private fun MultiPageContent(
    uiState: ScanUiState,
    wifiRequired: Boolean,
    isWifiConnected: Boolean,
    uploadAsSingleDocument: Boolean,
    onUploadModeChange: (Boolean) -> Unit,
    onUseAnywayClick: () -> Unit,
    onAddMore: () -> Unit,
    onRemovePage: (String) -> Unit,
    onRotatePage: (String) -> Unit,
    onCropPage: (String, CropRect) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onContinue: () -> Unit
) {
    val isNearLimit = uiState.pageCount >= 18
    val isAtLimit = uiState.pageCount >= MAX_PAGES
    val progressColor = when {
        uiState.pageCount >= 19 -> MaterialTheme.colorScheme.error
        uiState.pageCount >= 15 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    // Preview dialog state
    var previewPageIndex by remember { mutableStateOf<Int?>(null) }

    // Haptic feedback
    val haptic = LocalHapticFeedback.current

    // LazyRow state
    val lazyRowState = rememberLazyListState()

    // Reorderable state
    val reorderableState = rememberReorderableLazyListState(lazyRowState) { from, to ->
        // Adjust for the "Add" button at the end
        val fromIndex = from.index
        val toIndex = to.index
        if (fromIndex < uiState.pageCount && toIndex < uiState.pageCount) {
            onMovePage(fromIndex, toIndex)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Show preview dialog
    previewPageIndex?.let { index ->
        PagePreviewDialog(
            pages = uiState.pages,
            initialPageIndex = index,
            onDismiss = { previewPageIndex = null },
            onRotate = onRotatePage,
            onRemove = { pageId ->
                onRemovePage(pageId)
                // Close dialog if no pages left
                if (uiState.pageCount <= 1) {
                    previewPageIndex = null
                }
            },
            onCrop = onCropPage
        )
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Page count header with progress
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.scan_page_count, uiState.pageCount, MAX_PAGES),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.scan_hold_to_sort),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isNearLimit) {
                    Text(
                        text = if (isAtLimit) stringResource(R.string.scan_limit_reached) else stringResource(R.string.scan_almost_full),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isAtLimit) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { uiState.pageCount.toFloat() / MAX_PAGES },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round
            )
        }

        // WiFi Banner - shown when AI requires WiFi but device is not connected
        if (wifiRequired && !isWifiConnected) {
            Spacer(modifier = Modifier.height(16.dp))
            WifiRequiredBanner(
                onUseAnywayClick = onUseAnywayClick,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Upload Mode Selection
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            SegmentedButton(
                selected = !uploadAsSingleDocument,
                onClick = { onUploadModeChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(stringResource(R.string.batch_import_button_individual))
            }
            SegmentedButton(
                selected = uploadAsSingleDocument,
                onClick = { onUploadModeChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.batch_import_button_single))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyRow(
            state = lazyRowState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            itemsIndexed(
                items = uiState.pages,
                key = { _, page -> page.id }
            ) { index, page ->
                ReorderableItem(
                    state = reorderableState,
                    key = page.id
                ) { isDragging ->
                    PageThumbnail(
                        page = page,
                        index = index,
                        isDragging = isDragging,
                        onClick = { previewPageIndex = index },
                        onRemove = { onRemovePage(page.id) },
                        onRotate = { onRotatePage(page.id) },
                        modifier = Modifier.longPressDraggableHandle()
                    )
                }
            }

            // Add more button (only show if not at limit)
            if (!isAtLimit) {
                item(key = "add_button") {
                    AddPageCard(onClick = onAddMore)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = stringResource(R.string.scan_add_metadata),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.scan_add_metadata)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAddMore,
                    enabled = !isAtLimit,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.cd_add),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isAtLimit) stringResource(R.string.scan_maximum) else stringResource(R.string.scan_more_pages))
                }

                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_discard),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_discard))
                }
            }
        }
    }
}

@Composable
private fun PageThumbnail(
    page: ScannedPage,
    index: Int,
    isDragging: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val elevation = if (isDragging) 8.dp else 2.dp
    val scale = if (isDragging) 1.05f else 1f

    Card(
        modifier = modifier
            .width(160.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .zIndex(if (isDragging) 1f else 0f),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box {
            AsyncImage(
                model = page.uri,
                contentDescription = stringResource(R.string.scan_page_description, page.pageNumber),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(12.dp))
                    .rotate(page.rotation.toFloat())
                    .clickable { onClick() },
                contentScale = ContentScale.Crop
            )

            // Page number badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(28.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${page.pageNumber}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Top right buttons (Rotate + Remove)
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Rotate button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape
                        )
                        .clickable { onRotate() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = stringResource(R.string.scan_rotate),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                // Remove button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = CircleShape
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onRemove()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.scan_remove),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Drag indicator at bottom
            if (!isDragging) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = stringResource(R.string.scan_hold_to_sort_hint),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Source badge (bottom left)
            val sourceIcon = when (page.source) {
                PageSource.SCANNER -> Icons.Filled.CameraAlt
                PageSource.GALLERY -> Icons.Filled.PhotoLibrary
                PageSource.FILES -> Icons.Filled.FolderOpen
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .size(24.dp)
                    .background(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = sourceIcon,
                    contentDescription = when (page.source) {
                        PageSource.SCANNER -> stringResource(R.string.scan_option_scan)
                        PageSource.GALLERY -> stringResource(R.string.scan_option_gallery)
                        PageSource.FILES -> stringResource(R.string.scan_option_files)
                    },
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
private fun AddPageCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_add_page),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.scan_add_page),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PagePreviewDialog(
    pages: List<ScannedPage>,
    initialPageIndex: Int,
    onDismiss: () -> Unit,
    onRotate: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCrop: (String, CropRect) -> Unit = { _, _ -> }
) {
    val pagerState = rememberPagerState(
        initialPage = initialPageIndex,
        pageCount = { pages.size }
    )
    val scope = rememberCoroutineScope()
    var showCropScreen by remember { mutableStateOf<ScannedPage?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
        ) {
            // Pager for swiping between pages
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val page = pages[pageIndex]
                ZoomableImage(
                    uri = page.uri,
                    rotation = page.rotation,
                    contentDescription = stringResource(R.string.scan_page_description, page.pageNumber)
                )
            }

            // Top bar with close button and page indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Close button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.scan_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Page indicator
                Text(
                    text = "${pagerState.currentPage + 1} / ${pages.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                // Spacer for symmetry
                Spacer(modifier = Modifier.size(48.dp))
            }

            // Bottom action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                // Rotate button
                IconButton(
                    onClick = {
                        val currentPage = pages[pagerState.currentPage]
                        onRotate(currentPage.id)
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = stringResource(R.string.scan_rotate),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Crop button
                IconButton(
                    onClick = {
                        val currentPage = pages[pagerState.currentPage]
                        showCropScreen = currentPage
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Crop,
                        contentDescription = stringResource(R.string.scan_crop),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Delete button
                IconButton(
                    onClick = {
                        val currentPage = pages[pagerState.currentPage]
                        onRemove(currentPage.id)
                        // If only one page left, close dialog
                        if (pages.size <= 1) {
                            onDismiss()
                        } else {
                            // Navigate to previous page if at end
                            if (pagerState.currentPage >= pages.size - 1) {
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        (pagerState.currentPage - 1).coerceAtLeast(0)
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.scan_delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Show CropScreen when crop button is clicked
        showCropScreen?.let { page ->
            CropScreen(
                uri = page.uri,
                rotation = page.rotation,
                onDismiss = { showCropScreen = null },
                onCropApply = { cropRect ->
                    onCrop(page.id, cropRect)
                    showCropScreen = null
                }
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    uri: Uri,
    rotation: Int,
    contentDescription: String
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = uri,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                    rotationZ = rotation.toFloat()
                ),
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * WiFi Required Banner - shown when AI analysis requires WiFi but device is not connected.
 * Provides "Use anyway" button to override the restriction for current session.
 */
@Composable
private fun WifiRequiredBanner(
    onUseAnywayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.ai_wifi_only_banner),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.ai_wifi_required_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onUseAnywayClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = stringResource(R.string.ai_wifi_only_override_button),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
