package com.paperless.scanner.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R

/**
 * Animated shimmer placeholder for thumbnail loading state.
 *
 * Dark Tech Precision Pro Style:
 * - Flat design with 1dp border (no shadow)
 * - 12dp corner radius
 * - Animated shimmer effect with neon-yellow accent
 *
 * @param modifier Modifier for the placeholder
 */
@Composable
fun ShimmerThumbnailPlaceholder(
    modifier: Modifier = Modifier
) {
    // Infinite shimmer animation
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    // Dark Tech Precision Pro colors
    val backgroundColor = MaterialTheme.colorScheme.surface
    val shimmerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    val borderColor = MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        backgroundColor,
                        shimmerColor,
                        backgroundColor
                    ),
                    start = Offset(shimmerTranslate - 1000f, shimmerTranslate - 1000f),
                    end = Offset(shimmerTranslate, shimmerTranslate)
                )
            )
    )
}

/**
 * Error placeholder for failed thumbnail loads.
 *
 * Dark Tech Precision Pro Style:
 * - High contrast with error color
 * - 1dp border, 12dp corners, 0dp elevation
 * - Optional retry button with neon-yellow accent
 *
 * @param onRetry Optional retry callback (shows retry button if provided)
 * @param modifier Modifier for the placeholder
 */
@Composable
fun ErrorThumbnailPlaceholder(
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error,
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.BrokenImage,
                contentDescription = stringResource(R.string.thumbnail_error),
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.error
            )

            if (onRetry != null) {
                Spacer(modifier = Modifier.height(4.dp))
                IconButton(
                    onClick = onRetry,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.thumbnail_retry),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary // Neon yellow
                    )
                }
            }
        }
    }
}

/**
 * Document type placeholder based on file type/extension.
 *
 * Dark Tech Precision Pro Style:
 * - Primary color background with high contrast
 * - 1dp border, 12dp corners
 * - Icon based on document type
 *
 * @param mimeType MIME type of document (e.g., "application/pdf")
 * @param fileName Optional file name to infer type from extension
 * @param modifier Modifier for the placeholder
 */
@Composable
fun DocTypeThumbnailPlaceholder(
    mimeType: String? = null,
    fileName: String? = null,
    modifier: Modifier = Modifier
) {
    // Determine icon based on MIME type or file extension
    val icon = when {
        mimeType?.startsWith("application/pdf") == true -> Icons.Filled.PictureAsPdf
        mimeType?.startsWith("image/") == true -> Icons.Filled.Image
        fileName?.endsWith(".pdf", ignoreCase = true) == true -> Icons.Filled.PictureAsPdf
        fileName?.endsWith(".png", ignoreCase = true) == true -> Icons.Filled.Image
        fileName?.endsWith(".jpg", ignoreCase = true) == true -> Icons.Filled.Image
        fileName?.endsWith(".jpeg", ignoreCase = true) == true -> Icons.Filled.Image
        else -> Icons.Filled.Description
    }

    val typeDescription = mimeType ?: fileName ?: stringResource(R.string.thumbnail_unknown)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.thumbnail_document_type, typeDescription),
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/**
 * Loading placeholder with text label.
 *
 * Used for initial loading state before thumbnail fetch starts.
 *
 * @param text Loading text (default: localized "Loading...")
 * @param modifier Modifier for the placeholder
 */
@Composable
fun LoadingThumbnailPlaceholder(
    text: String? = null,
    modifier: Modifier = Modifier
) {
    val displayText = text ?: stringResource(R.string.loading)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium
        )
    }
}
