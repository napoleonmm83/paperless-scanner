package com.paperless.scanner.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Dark Tech Precision Animation System
 *
 * Provides consistent animation constants and composables
 * for the Paperless Scanner app.
 */
object PaperlessAnimations {

    // ============================================
    // Duration Constants
    // ============================================

    /** Ultra-fast animations for micro-interactions (e.g., checkbox toggle) */
    const val DURATION_INSTANT = 100

    /** Fast animations for small UI changes (e.g., button press) */
    const val DURATION_SHORT = 150

    /** Standard animations for most transitions (e.g., card expand) */
    const val DURATION_MEDIUM = 300

    /** Slower animations for emphasis (e.g., screen enter) */
    const val DURATION_LONG = 500

    /** Extended animations for dramatic effects (e.g., onboarding) */
    const val DURATION_EXTRA_LONG = 800

    // ============================================
    // Easing Curves
    // ============================================

    /** Standard ease out - fast start, slow end (most common) */
    val EaseOut = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    /** Ease in out - slow start, fast middle, slow end */
    val EaseInOut = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** Ease in - slow start, fast end (for exits) */
    val EaseIn = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    /** Emphasized ease - more pronounced acceleration */
    val Emphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    // ============================================
    // Screen Transition Specs
    // ============================================

    /** Enter transition for screens sliding in from right */
    val screenEnterTransition = fadeIn(
        animationSpec = tween(DURATION_MEDIUM, easing = EaseOut)
    ) + slideInHorizontally(
        animationSpec = tween(DURATION_MEDIUM, easing = EaseOut),
        initialOffsetX = { fullWidth -> fullWidth / 4 }
    )

    /** Exit transition for screens sliding out to left */
    val screenExitTransition = fadeOut(
        animationSpec = tween(DURATION_SHORT, easing = EaseIn)
    ) + slideOutHorizontally(
        animationSpec = tween(DURATION_SHORT, easing = EaseIn),
        targetOffsetX = { fullWidth -> -fullWidth / 4 }
    )

    /** Pop enter transition (going back) */
    val screenPopEnterTransition = fadeIn(
        animationSpec = tween(DURATION_MEDIUM, easing = EaseOut)
    ) + slideInHorizontally(
        animationSpec = tween(DURATION_MEDIUM, easing = EaseOut),
        initialOffsetX = { fullWidth -> -fullWidth / 4 }
    )

    /** Pop exit transition (going back) */
    val screenPopExitTransition = fadeOut(
        animationSpec = tween(DURATION_SHORT, easing = EaseIn)
    ) + slideOutHorizontally(
        animationSpec = tween(DURATION_SHORT, easing = EaseIn),
        targetOffsetX = { fullWidth -> fullWidth / 4 }
    )

    /** Vertical enter transition (bottom sheets, dialogs) */
    val verticalEnterTransition = fadeIn(
        animationSpec = tween(DURATION_MEDIUM, easing = EaseOut)
    ) + slideInVertically(
        animationSpec = tween(DURATION_MEDIUM, easing = EaseOut),
        initialOffsetY = { fullHeight -> fullHeight / 4 }
    )

    /** Vertical exit transition */
    val verticalExitTransition = fadeOut(
        animationSpec = tween(DURATION_SHORT, easing = EaseIn)
    ) + slideOutVertically(
        animationSpec = tween(DURATION_SHORT, easing = EaseIn),
        targetOffsetY = { fullHeight -> fullHeight / 4 }
    )

    // ============================================
    // Scale Transitions
    // ============================================

    /** Scale + fade enter for dialogs and overlays */
    val scaleEnterTransition = fadeIn(
        animationSpec = tween(DURATION_MEDIUM, easing = EaseOut)
    ) + scaleIn(
        animationSpec = tween(DURATION_MEDIUM, easing = EaseOut),
        initialScale = 0.92f
    )

    /** Scale + fade exit for dialogs and overlays */
    val scaleExitTransition = fadeOut(
        animationSpec = tween(DURATION_SHORT, easing = EaseIn)
    ) + scaleOut(
        animationSpec = tween(DURATION_SHORT, easing = EaseIn),
        targetScale = 0.92f
    )

    // ============================================
    // Expand/Collapse Transitions
    // ============================================

    /** Expand vertically (for expandable sections) */
    val expandTransition = fadeIn(
        animationSpec = tween(DURATION_MEDIUM, easing = EaseOut)
    ) + expandVertically(
        animationSpec = tween(DURATION_MEDIUM, easing = EaseOut)
    )

    /** Collapse vertically */
    val collapseTransition = fadeOut(
        animationSpec = tween(DURATION_SHORT, easing = EaseIn)
    ) + shrinkVertically(
        animationSpec = tween(DURATION_SHORT, easing = EaseIn)
    )
}

// ============================================
// Animation Composables
// ============================================

/**
 * Animated content wrapper with fade + scale effect.
 * Use for screen content that should animate in/out.
 *
 * @param visible Whether the content is visible
 * @param content The composable content to animate
 */
@Composable
fun AnimatedScreenContent(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = PaperlessAnimations.scaleEnterTransition,
        exit = PaperlessAnimations.scaleExitTransition,
        content = content
    )
}

/**
 * Animated content for expandable sections.
 *
 * @param visible Whether the content is expanded
 * @param content The composable content to animate
 */
@Composable
fun ExpandableContent(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = PaperlessAnimations.expandTransition,
        exit = PaperlessAnimations.collapseTransition,
        content = content
    )
}

/**
 * Pulsing indicator for important UI elements.
 * Useful for drawing attention to notifications, sync status, etc.
 *
 * @param color The color of the indicator
 * @param size The size of the indicator
 */
@Composable
fun PulsingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 12.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = PaperlessAnimations.DURATION_LONG,
                easing = PaperlessAnimations.EaseInOut
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = PaperlessAnimations.DURATION_LONG,
                easing = PaperlessAnimations.EaseInOut
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .alpha(alpha)
            .background(color, CircleShape)
    )
}

/**
 * Stagger animation delay calculator for list items.
 * Use to create cascading entrance effects.
 *
 * @param index The index of the item in the list
 * @param baseDelay Base delay in milliseconds
 * @param staggerDelay Delay added per item
 * @param maxDelay Maximum total delay (to prevent very long waits)
 */
fun calculateStaggerDelay(
    index: Int,
    baseDelay: Int = 0,
    staggerDelay: Int = 50,
    maxDelay: Int = 500
): Int {
    return (baseDelay + (index * staggerDelay)).coerceAtMost(maxDelay)
}

/**
 * Animated list item with staggered entrance.
 * Items appear one after another for a cascading effect.
 *
 * @param index The index of this item in the list
 * @param content The composable content of the item
 */
@Composable
fun StaggeredAnimatedItem(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(calculateStaggerDelay(index).toLong())
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = PaperlessAnimations.DURATION_MEDIUM,
                easing = PaperlessAnimations.EaseOut
            )
        ) + slideInVertically(
            animationSpec = tween(
                durationMillis = PaperlessAnimations.DURATION_MEDIUM,
                easing = PaperlessAnimations.EaseOut
            ),
            initialOffsetY = { it / 4 }
        )
    ) {
        content()
    }
}

/**
 * Shimmer effect modifier for loading placeholders.
 * Creates a diagonal shine animation across the composable.
 */
@Composable
fun shimmerBrush(): androidx.compose.ui.graphics.Brush {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")

    val translateAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.surfaceVariant
    )

    return androidx.compose.ui.graphics.Brush.linearGradient(
        colors = shimmerColors,
        start = androidx.compose.ui.geometry.Offset(translateAnim - 500f, translateAnim - 500f),
        end = androidx.compose.ui.geometry.Offset(translateAnim, translateAnim)
    )
}
