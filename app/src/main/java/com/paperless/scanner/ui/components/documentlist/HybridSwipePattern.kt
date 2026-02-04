package com.paperless.scanner.ui.components.documentlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * HybridSwipePattern - State management for coordinated swipe-to-reveal behavior.
 *
 * **HYBRID PATTERN:**
 * Combines the best of iOS Mail and Gmail patterns:
 * - Only one card revealed at a time (iOS Mail style)
 * - Auto-close on significant scroll (Gmail style)
 * - Scroll threshold prevents accidental closes
 *
 * **FEATURES:**
 * - Single card revealed tracking
 * - Scroll-based auto-close with configurable threshold
 * - Works with LazyColumn and LazyVerticalGrid
 * - Optional haptic feedback (handled by SwipeableDocumentCardContainer)
 *
 * **STATE LIFECYCLE:**
 * - State is NOT preserved across configuration changes (rotation, locale change)
 * - Intentional design: Revealed cards are transient UI states (like dialogs)
 * - Configuration change → State resets → All cards closed
 * - Navigation dispose → DisposableEffect calls closeAll() for cleanup
 *
 * **USAGE WITH LazyColumn:**
 * ```kotlin
 * val listState = rememberLazyListState()
 * val swipePattern = rememberHybridSwipePattern(listState = listState)
 *
 * LazyColumn(state = listState) {
 *     items(documents, key = { it.id }) { doc ->
 *         SwipeableDocumentCardContainer(
 *             documentId = doc.id,
 *             externallyRevealed = swipePattern.isRevealed(doc.id),
 *             onRevealStateChanged = { isRevealed ->
 *                 swipePattern.setRevealed(doc.id, isRevealed)
 *             },
 *             onDelete = { ... }
 *         ) { ... }
 *     }
 * }
 * ```
 *
 * **USAGE WITH LazyVerticalGrid:**
 * ```kotlin
 * val gridState = rememberLazyGridState()
 * val swipePattern = rememberHybridSwipePattern(gridState = gridState)
 *
 * LazyVerticalGrid(state = gridState, ...) {
 *     items(count = ..., key = { ... }) { index ->
 *         SwipeableDocumentCardContainer(
 *             documentId = document.id,
 *             externallyRevealed = swipePattern.isRevealed(document.id),
 *             onRevealStateChanged = { isRevealed ->
 *                 swipePattern.setRevealed(document.id, isRevealed)
 *             },
 *             onDelete = { ... }
 *         ) { ... }
 *     }
 * }
 * ```
 *
 * @param scrollThresholdDp Scroll distance in dp before auto-closing (default: 50dp)
 */
class HybridSwipePatternState(
    private val scrollThresholdDp: Float = 50f
) {
    // Currently revealed card ID (null = all closed)
    // NOTE: Uses mutableStateOf (NOT rememberSaveable) - state resets on rotation intentionally
    // Revealed cards are transient UI states that should not persist across config changes
    private var _revealedCardId by mutableStateOf<Int?>(null)

    // Last scroll offset for threshold detection
    private var lastScrollOffset by mutableFloatStateOf(0f)

    // First visible item index for scroll tracking
    private var lastFirstVisibleItemIndex by mutableIntStateOf(0)

    // MULTI-TOUCH RACE CONDITION FIX:
    // Mutex ensures sequential close-then-open animations
    // When card B opens while card A is revealed:
    // 1. Mutex lock (prevents parallel access)
    // 2. Card A animates to closed (wait for completion)
    // 3. Card B animates to open
    // 4. Mutex unlock
    // This guarantees never two cards are visually open simultaneously
    private val animationMutex = Mutex()

    // Registry of card animatables for coordinated animations
    // Key: cardId, Value: Animatable controlling the card's offset
    private val cardAnimatables = mutableMapOf<Int, Animatable<Float, AnimationVector1D>>()

    // Revealed offset value (negative = swiped left)
    private var revealedOffsetPx: Float = -300f // Will be set by registerCard

    // SIMULTANEOUS DRAG PREVENTION:
    // Tracks which card is currently being dragged (null = no active drag)
    // Uses @Volatile for immediate visibility across all coroutines
    // Uses synchronized for atomic check-and-set to prevent race conditions
    private val dragLock = Any()
    @Volatile
    private var _currentlyDraggingCardId: Int? = null


    /**
     * Check if another card (not this one) is currently being dragged.
     * Thread-safe read via @Volatile.
     */
    fun isOtherCardDragging(cardId: Int): Boolean {
        val dragging = _currentlyDraggingCardId
        return dragging != null && dragging != cardId
    }

    /**
     * Check if an animation is currently running (mutex is locked).
     * Used by gesture blocking to prevent new swipes during ongoing animations.
     *
     * @return true if a swipe animation is in progress, false otherwise
     */
    fun isAnimationRunning(): Boolean = animationMutex.isLocked


    /**
     * Try to acquire the drag lock for this card.
     * Returns true if successful (this card can drag), false if another card is already dragging.
     * Uses synchronized for atomic check-and-set.
     */
    fun tryStartDragging(cardId: Int): Boolean {
        synchronized(dragLock) {
            val current = _currentlyDraggingCardId
            if (animationMutex.isLocked) {
                return false
            }
            if (current == null) {
                _currentlyDraggingCardId = cardId
                return true
            }
            return current == cardId
        }
    }

    /**
     * Mark a card as starting to drag (non-atomic version for backward compatibility).
     */
    fun startDragging(cardId: Int) {
        synchronized(dragLock) {
            _currentlyDraggingCardId = cardId
        }
    }

    /**
     * Mark a card as finished dragging.
     */
    fun stopDragging(cardId: Int) {
        synchronized(dragLock) {
            if (_currentlyDraggingCardId == cardId) {
                _currentlyDraggingCardId = null
            }
        }
    }

    /**
     * Register a card's animatable for coordinated animation control.
     * Called by SwipeableDocumentCardContainer during composition.
     *
     * @param cardId Unique card identifier
     * @param animatable The Animatable controlling the card's horizontal offset
     * @param revealedOffset The target offset when revealed (negative value)
     */
    fun registerCard(
        cardId: Int,
        animatable: Animatable<Float, AnimationVector1D>,
        revealedOffset: Float
    ) {
        cardAnimatables[cardId] = animatable
        revealedOffsetPx = revealedOffset
    }

    /**
     * Unregister a card when it's disposed.
     * Also releases drag lock if this card was dragging.
     */
    fun unregisterCard(cardId: Int) {
        cardAnimatables.remove(cardId)
        if (_revealedCardId == cardId) {
            _revealedCardId = null
        }
        // Release drag lock if this card was dragging (e.g., scrolled off-screen)
        synchronized(dragLock) {
            if (_currentlyDraggingCardId == cardId) {
                _currentlyDraggingCardId = null
            }
        }
    }

    /**
     * Check if a specific card is revealed.
     */
    fun isRevealed(cardId: Int): Boolean = _revealedCardId == cardId

    /**
     * Set reveal state for a card with sequential animation.
     * Uses Mutex to ensure close-then-open animation sequence.
     *
     * - If revealing: first closes any other revealed card (animated), then opens this one
     * - If closing: only closes if this card is currently revealed
     *
     * IMPORTANT: This is a suspend function that waits for animations to complete.
     * Call from a coroutine scope (e.g., LaunchedEffect or rememberCoroutineScope).
     */
    suspend fun setRevealedAnimated(cardId: Int, isRevealed: Boolean) {
        animationMutex.withLock {
            if (isRevealed) {
                // PARALLEL ANIMATIONS: Close previous card AND open new card simultaneously
                val previousCardId = _revealedCardId
                _revealedCardId = cardId

                // Run both animations in parallel, wait for both to complete
                try {
                    coroutineScope {
                        // Close previous card (if any)
                        if (previousCardId != null && previousCardId != cardId) {
                            launch {
                                cardAnimatables[previousCardId]?.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow  // 400f - smooth ~150ms
                                    )
                                )
                            }
                        }

                        // Open new card
                        launch {
                            cardAnimatables[cardId]?.animateTo(
                                targetValue = revealedOffsetPx,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium  // 1500f - normal speed
                                )
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    // Animation was cancelled (e.g., card disposed, navigation)
                    // Reset state to match visual reality (card not fully revealed)
                    if (_revealedCardId == cardId && cardAnimatables[cardId] == null) {
                        _revealedCardId = null
                    }
                    throw e  // Re-throw to maintain structured concurrency
                }
            } else if (_revealedCardId == cardId) {
                // Close only if this card is currently revealed
                _revealedCardId = null
            }
        }
    }

    /**
     * Legacy non-animated setRevealed for backward compatibility.
     * Prefer setRevealedAnimated for proper race condition handling.
     */
    fun setRevealed(cardId: Int, isRevealed: Boolean) {
        if (isRevealed) {
            // Open this card, close others
            _revealedCardId = cardId
        } else if (_revealedCardId == cardId) {
            // Close only if this card is currently revealed
            _revealedCardId = null
        }
    }

    /**
     * Force close all revealed cards with animation.
     * Called by scroll detection logic.
     */
    suspend fun closeAllAnimated() {
        animationMutex.withLock {
            val currentCardId = _revealedCardId
            if (currentCardId != null) {
                cardAnimatables[currentCardId]?.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium  // Faster close on scroll
                    )
                )
                _revealedCardId = null
            }
        }
    }

    /**
     * Force close all revealed cards (instant, no animation).
     * Called by DisposableEffect for cleanup.
     */
    internal fun closeAll() {
        _revealedCardId = null
    }

    /**
     * Update scroll tracking state.
     * Returns true if threshold exceeded (should close cards).
     */
    internal fun updateScrollTracking(
        currentOffset: Float,
        currentFirstVisibleItemIndex: Int
    ): Boolean {
        // Check if scrolled to a different item (always close)
        if (currentFirstVisibleItemIndex != lastFirstVisibleItemIndex) {
            lastFirstVisibleItemIndex = currentFirstVisibleItemIndex
            lastScrollOffset = currentOffset
            return true
        }

        // Check if scroll offset exceeded threshold
        val scrollDelta = abs(currentOffset - lastScrollOffset)
        if (scrollDelta > scrollThresholdDp) {
            lastScrollOffset = currentOffset
            return true
        }

        return false
    }
}

/**
 * Remember HybridSwipePatternState with LazyColumn.
 */
@Composable
fun rememberHybridSwipePattern(
    listState: LazyListState,
    scrollThresholdDp: Float = 50f
): HybridSwipePatternState {
    val state = remember(scrollThresholdDp) { HybridSwipePatternState(scrollThresholdDp) }

    // Clean up state when composable is disposed (e.g., navigation away)
    DisposableEffect(Unit) {
        onDispose {
            state.closeAll()
        }
    }

    // Monitor scroll state for auto-close with animation
    // IMPORTANT: Use collect (not collectLatest) to prevent animation cancellation!
    // collectLatest would cancel the animation on each new scroll event
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (state.updateScrollTracking(offset.toFloat(), index)) {
                state.closeAllAnimated()
            }
        }
    }

    return state
}

/**
 * Remember HybridSwipePatternState with LazyVerticalGrid.
 */
@Composable
fun rememberHybridSwipePattern(
    gridState: LazyGridState,
    scrollThresholdDp: Float = 50f
): HybridSwipePatternState {
    val state = remember(scrollThresholdDp) { HybridSwipePatternState(scrollThresholdDp) }

    // Clean up state when composable is disposed (e.g., navigation away)
    DisposableEffect(Unit) {
        onDispose {
            state.closeAll()
        }
    }

    // Monitor scroll state for auto-close with animation
    // IMPORTANT: Use collect (not collectLatest) to prevent animation cancellation!
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (state.updateScrollTracking(offset.toFloat(), index)) {
                state.closeAllAnimated()
            }
        }
    }

    return state
}
