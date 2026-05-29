package com.paperless.scanner.ui.screens.scan.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.scan.CropRect
import com.paperless.scanner.ui.screens.scan.MAX_PAGES
import com.paperless.scanner.ui.screens.scan.ScanUiState
import com.paperless.scanner.ui.screens.scan.ScannedPage
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun MultiPageContent(
    uiState: ScanUiState,
    wifiRequired: Boolean,
    isWifiConnected: Boolean,
    usesCloudflare: Boolean,
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
    val isNearLimit = uiState.pageCount >= 90
    val isAtLimit = uiState.pageCount >= MAX_PAGES
    val progressColor = when {
        uiState.pageCount >= 90 -> MaterialTheme.colorScheme.error
        uiState.pageCount >= 50 -> MaterialTheme.colorScheme.tertiary
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

        // Cloudflare Timeout Warning - shown when:
        // 1. Server uses Cloudflare (cf-ray header detected)
        // 2. Single PDF mode (not individual documents)
        // 3. Large batch (>15 pages) OR slow connection (no WiFi)
        if (usesCloudflare && uploadAsSingleDocument && (uiState.pageCount > 15 || !isWifiConnected)) {
            Spacer(modifier = Modifier.height(16.dp))
            CloudflareTimeoutWarning(
                pageCount = uiState.pageCount,
                isWifiConnected = isWifiConnected,
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

@Preview
@Composable
private fun MultiPageContentPreview() {
    MaterialTheme {
        MultiPageContent(
            uiState = ScanUiState(
                pages = listOf(
                    ScannedPage(uri = Uri.EMPTY, pageNumber = 1),
                    ScannedPage(uri = Uri.EMPTY, pageNumber = 2)
                )
            ),
            wifiRequired = false,
            isWifiConnected = true,
            usesCloudflare = false,
            uploadAsSingleDocument = false,
            onUploadModeChange = {},
            onUseAnywayClick = {},
            onAddMore = {},
            onRemovePage = {},
            onRotatePage = {},
            onCropPage = { _, _ -> },
            onMovePage = { _, _ -> },
            onClear = {},
            onContinue = {}
        )
    }
}
