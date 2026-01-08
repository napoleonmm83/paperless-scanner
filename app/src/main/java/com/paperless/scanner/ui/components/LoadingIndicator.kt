package com.paperless.scanner.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Paperless loading indicator with Dark Tech Precision styling.
 *
 * A circular loading spinner using the primary neon-yellow color
 * with a smooth rotation animation.
 *
 * @param modifier Modifier for the indicator
 * @param size Size of the indicator
 * @param color Primary color (defaults to theme primary)
 * @param trackColor Background track color
 * @param strokeWidth Width of the arc stroke
 */
@Composable
fun PaperlessLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    strokeWidth: Dp = 4.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")

    // Rotation animation
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Sweep angle animation for dynamic arc length
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 30f,
        targetValue = 270f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 800,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )

    Canvas(
        modifier = modifier.size(size)
    ) {
        val strokeWidthPx = strokeWidth.toPx()
        val arcSize = this.size.minDimension - strokeWidthPx

        // Background track
        drawCircle(
            color = trackColor,
            radius = (arcSize / 2),
            style = Stroke(width = strokeWidthPx)
        )

        // Animated arc
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(strokeWidthPx / 2, strokeWidthPx / 2),
            size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
            style = Stroke(
                width = strokeWidthPx,
                cap = StrokeCap.Round
            )
        )
    }
}

/**
 * Compact loading indicator for inline use.
 *
 * @param modifier Modifier for the indicator
 * @param size Size of the indicator (default 24dp for inline use)
 * @param color Color of the spinner
 */
@Composable
fun PaperlessLoadingIndicatorSmall(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    PaperlessLoadingIndicator(
        modifier = modifier,
        size = size,
        color = color,
        strokeWidth = 3.dp
    )
}

/**
 * Shimmer placeholder for loading content.
 *
 * Creates a diagonal shimmer animation effect that gives
 * visual feedback while content is loading.
 *
 * @param modifier Modifier for the placeholder
 * @param height Height of the placeholder
 */
@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    height: Dp = 60.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")

    val translateAnim by infiniteTransition.animateFloat(
        initialValue = -500f,
        targetValue = 1500f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1500,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim, translateAnim),
        end = Offset(translateAnim + 300f, translateAnim + 300f)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(brush)
    )
}

/**
 * Shimmer placeholder for card-shaped content.
 *
 * @param modifier Modifier for the placeholder
 */
@Composable
fun ShimmerCard(
    modifier: Modifier = Modifier
) {
    ShimmerPlaceholder(
        modifier = modifier,
        height = 80.dp
    )
}

/**
 * Shimmer placeholder for text lines.
 *
 * @param modifier Modifier for the placeholder
 * @param width Fraction of parent width (0.0 to 1.0)
 */
@Composable
fun ShimmerTextLine(
    modifier: Modifier = Modifier,
    width: Float = 1f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmerText")

    val translateAnim by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTextTranslate"
    )

    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.surfaceVariant
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim, 0f),
        end = Offset(translateAnim + 200f, 0f)
    )

    Box(
        modifier = modifier
            .fillMaxWidth(width)
            .height(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(brush)
    )
}

/**
 * Document list item shimmer placeholder.
 *
 * Mimics the layout of a document list item while loading.
 */
@Composable
fun DocumentItemShimmer(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "docShimmer")

    val translateAnim by infiniteTransition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1400,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "docShimmerTranslate"
    )

    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim, translateAnim / 2),
        end = Offset(translateAnim + 400f, (translateAnim + 400f) / 2)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(brush)
    )
}
