package com.paperless.scanner.ui.components.documentlist

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
import kotlinx.coroutines.flow.collectLatest
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

    /**
     * Check if a specific card is revealed.
     */
    fun isRevealed(cardId: Int): Boolean = _revealedCardId == cardId

    /**
     * Set reveal state for a card.
     * - If revealing: closes any other revealed card
     * - If closing: only closes if this card is currently revealed
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
     * Force close all revealed cards.
     * Called by scroll detection logic.
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

    // Monitor scroll state for auto-close
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collectLatest { (index, offset) ->
            if (state.updateScrollTracking(offset.toFloat(), index)) {
                state.closeAll()
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

    // Monitor scroll state for auto-close
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }.collectLatest { (index, offset) ->
            if (state.updateScrollTracking(offset.toFloat(), index)) {
                state.closeAll()
            }
        }
    }

    return state
}
