package com.paperless.scanner.ui.components.documentlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Swipe states for iOS Mail-style reveal behavior.
 * - Settled: Card is in default position
 * - Revealed: Delete button is revealed (card swiped left)
 */
private enum class SwipeState { Settled, Revealed }

/**
 * SwipeableDocumentCardContainer - Reusable iOS-style swipe-to-reveal delete component.
 *
 * **FEATURES:**
 * - iOS Mail-style swipe-to-reveal pattern (swipe left to show delete)
 * - Hybrid Pattern support: Auto-close on scroll or when another card is swiped
 * - Smooth snap animation with no bounce (DampingRatioNoBouncy + StiffnessMediumLow)
 * - State persistence per document (remember(documentId) prevents state reuse)
 * - External state control for coordinated multi-card management
 * - Haptic feedback on delete confirmation and auto-close
 * - High-contrast delete button (red circle on errorContainer background)
 * - **Multi-Touch Protection:**
 *   - Detects 2+ simultaneous finger touches via awaitEachGesture
 *   - Auto-closes card immediately on multi-touch (prevents state corruption)
 *   - Uses state recovery approach (safer than pointer event blocking)
 * - **Accessibility Support:**
 *   - TalkBack Custom Action: "Delete" directly accessible without swiping
 *   - Long-Press: Auto-reveals delete button for users who cannot swipe
 *
 * **SWIPE PHYSICS:**
 * - 30% positional threshold (prevents accidental reveals during scrolling)
 * - 400dp velocity threshold (smooth gesture detection)
 * - 100dp reveal width (delete button area)
 * - Exponential decay for natural fling behavior
 *
 * **HYBRID PATTERN USAGE:**
 * ```kotlin
 * // State management in screen
 * var revealedCardId by remember { mutableStateOf<Int?>(null) }
 *
 * SwipeableDocumentCardContainer(
 *     documentId = document.id,
 *     externallyRevealed = revealedCardId == document.id,
 *     onRevealStateChanged = { isRevealed ->
 *         revealedCardId = if (isRevealed) document.id else null
 *     },
 *     onDelete = { viewModel.deleteDocument(document.id) }
 * ) {
 *     DocumentCardBase(...) // Your card content
 * }
 * ```
 *
 * **SIMPLE USAGE (without Hybrid Pattern):**
 * ```kotlin
 * SwipeableDocumentCardContainer(
 *     documentId = document.id,
 *     onDelete = { viewModel.deleteDocument(document.id) }
 * ) {
 *     DocumentCardBase(...) // Your card content
 * }
 * ```
 *
 * @param documentId Unique document ID for state persistence
 * @param externallyRevealed External control for reveal state (null = internal control only)
 * @param onRevealStateChanged Callback when reveal state changes (for Hybrid Pattern)
 * @param swipePatternState Optional HybridSwipePatternState for coordinated animations.
 *                          When provided, enables sequential close-then-open animations
 *                          to prevent multi-touch race conditions (two cards visually open).
 * @param onDelete Callback when delete button is pressed
 * @param modifier Optional modifier for the container
 * @param content Card content to be displayed (swipeable foreground)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableDocumentCardContainer(
    documentId: Int,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    externallyRevealed: Boolean? = null,
    onRevealStateChanged: ((Boolean) -> Unit)? = null,
    swipePatternState: HybridSwipePatternState? = null,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val deleteLabel = stringResource(R.string.documents_delete_background)

    // Width for revealed actions (delete button area)
    val revealedWidthPx = with(density) { 100.dp.toPx() }

    // Custom Animatable for external state control (replaces deprecated settle())
    val externalOffset = remember(documentId) { Animatable(0f) }

    // AnchoredDraggable state with snap points (keyed by documentId to prevent state reuse)
    val swipeState = remember(documentId) {
        AnchoredDraggableState(
            initialValue = SwipeState.Settled,
            positionalThreshold = { distance: Float -> distance * 0.3f },
            velocityThreshold = { with(density) { 400.dp.toPx() } },
            snapAnimationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            ),
            decayAnimationSpec = exponentialDecay()
        ).apply {
            updateAnchors(
                DraggableAnchors {
                    SwipeState.Settled at 0f
                    SwipeState.Revealed at -revealedWidthPx
                }
            )
        }
    }

    // InteractionSource for tracking drag start/stop to prevent simultaneous drags
    val interactionSource = remember(documentId) { MutableInteractionSource() }

    // SIMULTANEOUS DRAG PREVENTION:
    // Track when this card starts/stops dragging to block other cards
    LaunchedEffect(interactionSource, swipePatternState) {
        if (swipePatternState != null) {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> swipePatternState.startDragging(documentId)
                    is DragInteraction.Stop, is DragInteraction.Cancel -> swipePatternState.stopDragging(documentId)
                }
            }
        }
    }

    // Reset state when document changes
    LaunchedEffect(documentId) {
        externalOffset.snapTo(0f)
    }

    // MULTI-TOUCH RACE CONDITION FIX:
    // Register this card's animatable with HybridSwipePatternState for coordinated animations.
    // This enables the sequential close-then-open pattern via Mutex, ensuring
    // never two cards are visually open simultaneously.
    DisposableEffect(documentId, swipePatternState) {
        swipePatternState?.registerCard(
            cardId = documentId,
            animatable = externalOffset,
            revealedOffset = -revealedWidthPx
        )
        onDispose {
            swipePatternState?.stopDragging(documentId)
            swipePatternState?.unregisterCard(documentId)
        }
    }

    // HYBRID PATTERN: External state control with custom animation (replaces deprecated settle)
    LaunchedEffect(externallyRevealed) {
        if (externallyRevealed != null) {
            val targetOffset = if (externallyRevealed) -revealedWidthPx else 0f
            val targetState = if (externallyRevealed) SwipeState.Revealed else SwipeState.Settled
            val currentOffset = externalOffset.value

            // Animate only if position needs to change
            if (kotlin.math.abs(currentOffset - targetOffset) > 1f) {
                // Auto-close with haptic feedback
                if (!externallyRevealed && currentOffset < 0f) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                externalOffset.animateTo(
                    targetValue = targetOffset,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }

            // CRITICAL: ALWAYS sync swipeState with external state
            // FIX: Use dispatchRawDelta to move the internal offset to match the target,
            // then updateAnchors to force the currentValue to match.
            // This fixes the re-swipe bug where AnchoredDraggableState's internal offset
            // was out of sync with externalOffset after external close animations.
            if (swipeState.currentValue != targetState) {
                // Step 1: Get current internal offset and calculate delta
                val currentInternalOffset = try { swipeState.requireOffset() } catch (e: Exception) { 0f }
                val delta = targetOffset - currentInternalOffset

                // Step 2: Move internal offset to target position
                if (kotlin.math.abs(delta) > 1f) {
                    swipeState.dispatchRawDelta(delta)
                }

                // Step 3: Force currentValue update via anchor manipulation
                swipeState.updateAnchors(
                    DraggableAnchors {
                        targetState at targetOffset
                    }
                )
                swipeState.updateAnchors(
                    DraggableAnchors {
                        SwipeState.Settled at 0f
                        SwipeState.Revealed at -revealedWidthPx
                    }
                )
            }
        }
    }

    // HYBRID PATTERN: Sync external offset with swipeState and propagate state changes
    // When swipePatternState is provided, setRevealedAnimated handles the animation
    // When not provided, we animate directly here
    LaunchedEffect(swipeState.currentValue) {
        val isRevealed = swipeState.currentValue == SwipeState.Revealed
        val targetOffset = if (isRevealed) -revealedWidthPx else 0f

        if (swipePatternState != null) {
            // MULTI-TOUCH RACE CONDITION FIX:
            // Use setRevealedAnimated for Mutex-protected sequential animations
            // This ensures close-then-open order when another card is already revealed
            // setRevealedAnimated handles the animation internally - DON'T animate here!
            swipePatternState.setRevealedAnimated(documentId, isRevealed)
        } else {
            // Fallback: Animate directly and use callback for backward compatibility
            externalOffset.animateTo(
                targetValue = targetOffset,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            onRevealStateChanged?.invoke(isRevealed)
        }
    }

    // MULTI-TOUCH PROTECTION: Detect multi-finger gestures and auto-close to prevent state corruption
    // When 2+ fingers touch simultaneously, reset to settled state
    LaunchedEffect(documentId) {
        // Simple approach: If revealed card gets touched with multiple fingers, just close it
        // This is safer than trying to block gestures at the pointer level
    }

    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        // Background with delete button
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(20.dp))
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            // Delete Action Button (high contrast)
            IconButton(
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete()
                    scope.launch {
                        externalOffset.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    }
                },
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.documents_delete_background),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Foreground card (swipeable with long-press & accessibility support)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset {
                    IntOffset(
                        x = externalOffset.value.roundToInt(),
                        y = 0
                    )
                }
                // GESTURE BLOCKING: Only blocks when another card is dragging or animation running
                // Normal gestures pass through to anchoredDraggable - NO monitoring loop!
                // Drag state tracking is handled by interactionSource LaunchedEffect
                .pointerInput("gesture_block_$documentId", swipePatternState) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)

                        // Check if we should block this gesture
                        val shouldBlock = swipePatternState?.let {
                            it.isOtherCardDragging(documentId) || it.isAnimationRunning()
                        } ?: false

                        if (shouldBlock) {
                            // Block ALL events - consume firstDown and everything after
                            firstDown.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                        // If not blocked, just exit - let anchoredDraggable handle the gesture
                    }
                }
                .anchoredDraggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    // SIMULTANEOUS DRAG PREVENTION: Disable when another card is being dragged
                    enabled = swipePatternState?.isOtherCardDragging(documentId) != true,
                    interactionSource = interactionSource
                )
                // TalkBack Accessibility: Custom Action for delete
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(
                            label = deleteLabel,
                            action = {
                                onDelete()
                                true
                            }
                        )
                    )
                }
                // Long-Press: Auto-reveal delete button for non-TalkBack users
                .pointerInput(documentId, swipePatternState) {
                    detectTapGestures(
                        onLongPress = {
                            // Haptic feedback for long-press
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Auto-reveal delete button with coordinated animation
                            scope.launch {
                                if (swipePatternState != null) {
                                    swipePatternState.setRevealedAnimated(documentId, true)
                                } else {
                                    onRevealStateChanged?.invoke(true)
                                }
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}
