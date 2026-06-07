package com.paperless.scanner.ui.screens.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.components.documentlist.DocumentCardBase
import com.paperless.scanner.ui.components.documentlist.HybridSwipePatternState
import com.paperless.scanner.ui.components.documentlist.SwipeableDocumentCardContainer
import com.paperless.scanner.ui.components.documentlist.rememberHybridSwipePattern
import com.paperless.scanner.ui.screens.home.RecentDocument

/**
 * "Recently added" section header with a "See all" affordance.
 */
@Composable
fun RecentDocumentsHeader(
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_recently_added),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.minimumInteractiveComponentSize().clickable { onSeeAll() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.home_see_all),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    // decorative-only: the adjacent "See all" text already labels this clickable row
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * Empty-state card shown when there are no recent documents.
 */
@Composable
fun EmptyDocumentsCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                // decorative-only: the no-documents message Text below carries the meaning
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.home_no_documents),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.home_scan_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * A single recent-document row: swipeable container wrapping the shared
 * DocumentCardBase with HomeScreen-specific metadata (title, time, tag badge).
 *
 * The `animateItemRemoval()` LazyItemScope modifier stays at the call site;
 * this composable is the swipeable content itself.
 */
@Composable
fun RecentDocumentItem(
    document: RecentDocument,
    serverUrl: String,
    showThumbnails: Boolean,
    swipePattern: HybridSwipePatternState,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    // Using shared swipeable container with Hybrid Pattern
    // MULTI-TOUCH RACE CONDITION FIX: Pass swipePatternState for
    // sequential close-then-open animations via Mutex
    SwipeableDocumentCardContainer(
        documentId = document.id,
        externallyRevealed = swipePattern.isRevealed(document.id),
        swipePatternState = swipePattern,
        onDelete = onDelete
    ) {
        DocumentCardBase(
            documentId = document.id,
            serverUrl = serverUrl,
            showThumbnails = showThumbnails,
            onClick = onClick
        ) {
            // HomeScreen-specific metadata
            Text(
                text = document.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        // decorative-only: the timeAgo Text immediately after carries the meaning
                        imageVector = Icons.Outlined.AccessTime,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = document.timeAgo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Optional tag badge
                document.tagName?.let { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                document.tagColor?.let { Color(it).copy(alpha = 0.2f) }
                                    ?: MaterialTheme.colorScheme.primaryContainer
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = document.tagColor?.let { Color(it) }
                                ?: MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun RecentDocumentsHeaderPreview() {
    MaterialTheme {
        RecentDocumentsHeader(onSeeAll = {})
    }
}

@Preview
@Composable
private fun EmptyDocumentsCardPreview() {
    MaterialTheme {
        EmptyDocumentsCard(modifier = Modifier.padding(24.dp))
    }
}

@Preview
@Composable
private fun RecentDocumentItemPreview() {
    MaterialTheme {
        RecentDocumentItem(
            document = RecentDocument(
                id = 1,
                title = "Invoice 2026-04.pdf",
                timeAgo = "2h ago",
                tagName = "Finance",
                tagColor = 0xFF4CAF50
            ),
            serverUrl = "",
            showThumbnails = false,
            swipePattern = rememberHybridSwipePattern(listState = rememberLazyListState()),
            onClick = {},
            onDelete = {}
        )
    }
}
