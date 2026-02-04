package com.paperless.scanner.ui.components.documentlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R

/**
 * DocumentCardBase - Reusable base card component for document lists.
 *
 * **DARK TECH PRECISION PRO STYLE GUIDE:**
 * - Surface background (#141414)
 * - 1dp border with outline color (#27272A)
 * - No elevation (0.dp) - flat design
 * - 20dp corner radius for premium look
 * - 16dp content padding
 *
 * **LAYOUT:**
 * - Left: DocumentThumbnail (48.dp)
 * - Center: Custom metadata content (flexible)
 * - Right: Arrow forward icon (20.dp)
 *
 * **FLEXIBILITY:**
 * The metadata parameter allows each screen to customize the content:
 * - HomeScreen: timeAgo + single tag badge
 * - DocumentsScreen: date + correspondent + multiple tags in FlowRow
 * - Future screens: Custom layouts as needed
 *
 * **USAGE:**
 * ```kotlin
 * DocumentCardBase(
 *     documentId = document.id,
 *     serverUrl = serverUrl,
 *     showThumbnails = true,
 *     onClick = { onDocumentClick(document.id) }
 * ) {
 *     // Custom metadata content
 *     Text(text = document.title, style = MaterialTheme.typography.bodyLarge)
 *     Row {
 *         Text(text = document.date, style = MaterialTheme.typography.labelSmall)
 *     }
 * }
 * ```
 *
 * @param documentId Document ID for thumbnail loading
 * @param serverUrl Paperless-ngx server URL
 * @param showThumbnails User preference for thumbnail visibility
 * @param onClick Callback when card is clicked
 * @param modifier Optional modifier for the card
 * @param metadata Custom metadata content (receives ColumnScope for vertical layout)
 *
 * @see DocumentThumbnail For thumbnail rendering logic
 * @see SwipeableDocumentCardContainer For swipe-to-delete wrapper
 */
@Composable
fun DocumentCardBase(
    documentId: Int,
    serverUrl: String,
    showThumbnails: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    metadata: @Composable ColumnScope.() -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left: Thumbnail
            DocumentThumbnail(
                documentId = documentId,
                serverUrl = serverUrl,
                showThumbnails = showThumbnails
            )

            // Center: Custom metadata content
            Column(
                modifier = Modifier.weight(1f),
                content = metadata
            )

            // Right: Arrow icon
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.cd_open_document),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
