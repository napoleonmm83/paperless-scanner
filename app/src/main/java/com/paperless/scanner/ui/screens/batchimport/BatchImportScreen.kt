package com.paperless.scanner.ui.screens.batchimport

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.paperless.scanner.R
import com.paperless.scanner.ui.navigation.BatchSourceType
import com.paperless.scanner.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.UUID

/**
 * File type enumeration for rendering appropriate preview
 */
private enum class FileType {
    PDF, IMAGE, OTHER
}

/**
 * Data class representing a file item with its state
 */
private data class FileItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val fileType: FileType,
    val rotation: Int = 0  // Only used for images (0, 90, 180, 270)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchImportScreen(
    imageUris: List<Uri>,
    sourceType: BatchSourceType,
    onContinueToMetadata: (List<Uri>, Boolean) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Convert initial URIs to FileItems with type detection
    var fileItems by remember {
        mutableStateOf(
            imageUris.map { uri ->
                FileItem(
                    uri = uri,
                    fileType = getFileType(context, uri)
                )
            }
        )
    }
    var uploadAsSingleDocument by rememberSaveable { mutableStateOf(false) }

    // LazyRow state for drag & drop
    val lazyRowState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyRowState) { from, to ->
        // Move items in the list
        val fromIndex = from.index
        val toIndex = to.index
        if (fromIndex < fileItems.size && toIndex < fileItems.size) {
            fileItems = fileItems.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Photo Picker launcher (for Gallery source)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val localUris = uris.mapNotNull { uri ->
                    FileUtils.copyToLocalStorage(context, uri)
                }
                withContext(Dispatchers.Main) {
                    val newItems = localUris.map { uri ->
                        FileItem(
                            uri = uri,
                            fileType = getFileType(context, uri)
                        )
                    }
                    fileItems = fileItems + newItems
                }
            }
        }
    }

    // File Picker launcher (for Files source)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val localUris = uris.mapNotNull { uri ->
                    FileUtils.copyToLocalStorage(context, uri)
                }
                withContext(Dispatchers.Main) {
                    val newItems = localUris.map { uri ->
                        FileItem(
                            uri = uri,
                            fileType = getFileType(context, uri)
                        )
                    }
                    fileItems = fileItems + newItems
                }
            }
        }
    }

    // Function to add more files using the appropriate picker
    val onAddMoreFiles: () -> Unit = {
        when (sourceType) {
            BatchSourceType.GALLERY -> {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            }
            BatchSourceType.FILES -> {
                filePickerLauncher.launch(arrayOf("image/*", "application/pdf"))
            }
        }
    }

    // Function to rotate an image
    val onRotateFile: (String) -> Unit = { fileId ->
        fileItems = fileItems.map { item ->
            if (item.id == fileId && item.fileType == FileType.IMAGE) {
                item.copy(rotation = (item.rotation + 90) % 360)
            } else {
                item
            }
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // Function to remove a file
    val onRemoveFile: (String) -> Unit = { fileId ->
        fileItems = fileItems.filter { it.id != fileId }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_import_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.batch_import_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Photo,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.batch_import_images_selected, fileItems.size),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (uploadAsSingleDocument)
                                stringResource(R.string.batch_import_mode_single)
                            else
                                stringResource(R.string.batch_import_mode_individual),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Upload Mode Selection
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                SegmentedButton(
                    selected = !uploadAsSingleDocument,
                    onClick = { uploadAsSingleDocument = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(stringResource(R.string.batch_import_button_individual))
                }
                SegmentedButton(
                    selected = uploadAsSingleDocument,
                    onClick = { uploadAsSingleDocument = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(stringResource(R.string.batch_import_button_single))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Preview Label with hint
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.batch_import_preview),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.batch_import_hold_to_sort),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Horizontal scrollable file list with drag & drop
            LazyRow(
                state = lazyRowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                itemsIndexed(
                    items = fileItems,
                    key = { _, item -> item.id }
                ) { index, item ->
                    ReorderableItem(
                        state = reorderableState,
                        key = item.id
                    ) { isDragging ->
                        FilePreviewCard(
                            item = item,
                            index = index,
                            isDragging = isDragging,
                            onRotate = { onRotateFile(item.id) },
                            onRemove = { onRemoveFile(item.id) },
                            modifier = Modifier.longPressDraggableHandle()
                        )
                    }
                }
            }

            // Add more button below images
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onAddMoreFiles() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.batch_import_add_more),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Continue button
            Button(
                onClick = {
                    // Pass URIs in current order to metadata screen
                    onContinueToMetadata(fileItems.map { it.uri }, uploadAsSingleDocument)
                },
                enabled = fileItems.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp)
            ) {
                Text(
                    text = stringResource(R.string.scan_add_metadata),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null
                )
            }
        }
    }
}

/**
 * Helper function to determine file type from URI.
 */
private fun getFileType(context: android.content.Context, uri: Uri): FileType {
    // Method 1: Try ContentResolver.getType()
    val mimeType = try {
        context.contentResolver.getType(uri)
    } catch (e: Exception) {
        null
    }

    if (mimeType != null) {
        return when {
            mimeType == "application/pdf" -> FileType.PDF
            mimeType.startsWith("image/") -> FileType.IMAGE
            else -> FileType.OTHER
        }
    }

    // Method 2: Check URI path for extension
    val uriString = uri.toString().lowercase()
    val path = uri.path?.lowercase() ?: ""

    return when {
        uriString.endsWith(".pdf") || path.endsWith(".pdf") -> FileType.PDF
        uriString.endsWith(".jpg") || uriString.endsWith(".jpeg") ||
        uriString.endsWith(".png") || uriString.endsWith(".gif") ||
        uriString.endsWith(".webp") || uriString.endsWith(".bmp") ||
        path.endsWith(".jpg") || path.endsWith(".jpeg") ||
        path.endsWith(".png") || path.endsWith(".gif") ||
        path.endsWith(".webp") || path.endsWith(".bmp") -> FileType.IMAGE
        else -> {
            // Method 3: Try to query display name from content provider
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (displayNameIndex >= 0) {
                            val displayName = cursor.getString(displayNameIndex)?.lowercase() ?: ""
                            return@use when {
                                displayName.endsWith(".pdf") -> FileType.PDF
                                displayName.endsWith(".jpg") || displayName.endsWith(".jpeg") ||
                                displayName.endsWith(".png") || displayName.endsWith(".gif") ||
                                displayName.endsWith(".webp") -> FileType.IMAGE
                                else -> FileType.OTHER
                            }
                        }
                    }
                    FileType.OTHER
                } ?: FileType.OTHER
            } catch (e: Exception) {
                // If all methods fail, assume it might be an image and let Coil try
                FileType.IMAGE
            }
        }
    }
}

@Composable
private fun FilePreviewCard(
    item: FileItem,
    index: Int,
    isDragging: Boolean,
    onRotate: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val elevation = if (isDragging) 8.dp else 2.dp
    val scale = if (isDragging) 1.05f else 1f

    // For PDFs, render the first page as a thumbnail asynchronously
    val pdfThumbnail by produceState<Bitmap?>(initialValue = null, item.uri, item.fileType) {
        if (item.fileType == FileType.PDF) {
            value = withContext(Dispatchers.IO) {
                FileUtils.renderPdfFirstPage(context, item.uri, maxWidth = 400)
            }
        }
    }

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
            when (item.fileType) {
                FileType.PDF -> {
                    if (pdfThumbnail != null) {
                        // Show rendered PDF page thumbnail
                        Image(
                            bitmap = pdfThumbnail!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.batch_import_pdf_file),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.7f)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // Show loading/placeholder while rendering
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.7f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PictureAsPdf,
                                contentDescription = stringResource(R.string.batch_import_pdf_file),
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                FileType.IMAGE -> {
                    // Image preview with rotation
                    AsyncImage(
                        model = item.uri,
                        contentDescription = stringResource(R.string.batch_import_image_description, index + 1),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.7f)
                            .clip(RoundedCornerShape(12.dp))
                            .rotate(item.rotation.toFloat()),
                        contentScale = ContentScale.Crop
                    )
                }
                FileType.OTHER -> {
                    // Generic file placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.7f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.InsertDriveFile,
                            contentDescription = stringResource(R.string.batch_import_file),
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Index badge
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
                    text = "${index + 1}",
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
                // Rotate button - only for images
                if (item.fileType == FileType.IMAGE) {
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
                            contentDescription = stringResource(R.string.batch_import_rotate),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
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
                        contentDescription = stringResource(R.string.batch_import_remove),
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
                        contentDescription = stringResource(R.string.batch_import_drag_hint),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
