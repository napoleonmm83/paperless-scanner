package com.paperless.scanner.ui.components.documentlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier

/**
 * DocumentListAnimations - Animation utilities for smooth document list transitions.
 *
 * ## Gmail-Style Slide-Off Animation
 *
 * When a document is deleted from the list, it smoothly slides up and fades out
 * while other items fill the gap. This creates a polished, native-app feel.
 *
 * **How it works:**
 * 1. Room Flow removes the document from the database
 * 2. Paging/LazyColumn detects the data change
 * 3. `Modifier.animateItem()` animates the removal
 * 4. Other items slide up to fill the gap
 *
 * **Requirements:**
 * - MUST use `key` parameter in `items()` for stable identity
 * - MUST use `Modifier.animateItem()` on each list item
 * - MUST use Room Flow or reactive data source (LiveData/StateFlow)
 *
 * **Example (LazyColumn):**
 * ```kotlin
 * LazyColumn {
 *     items(
 *         items = documents,
 *         key = { document -> document.id }  // CRITICAL: Stable key
 *     ) { document ->
 *         SwipeableDocumentCardContainer(
 *             documentId = document.id,
 *             onDelete = { viewModel.deleteDocument(document.id) },
 *             modifier = Modifier.animateItemRemoval()  // Gmail-style animation
 *         ) {
 *             DocumentCardBase(...)
 *         }
 *     }
 * }
 * ```
 *
 * **Example (LazyVerticalGrid):**
 * ```kotlin
 * LazyVerticalGrid(columns = GridCells.Fixed(2)) {
 *     items(
 *         count = documents.itemCount,
 *         key = { index -> documents[index]?.id ?: index }
 *     ) { index ->
 *         val document = documents[index] ?: return@items
 *         SwipeableDocumentCardContainer(
 *             documentId = document.id,
 *             onDelete = { viewModel.deleteDocument(document.id) },
 *             modifier = Modifier.animateItemRemoval()
 *         ) {
 *             DocumentCardBase(...)
 *         }
 *     }
 * }
 * ```
 *
 * **Common Mistakes:**
 * ❌ Forgetting `key` parameter → Items re-compose instead of animating
 * ❌ Using index as key → Animation breaks when order changes
 * ❌ Forgetting `animateItem()` → Instant removal without animation
 * ❌ Using manual list removal → Race conditions, no smooth transition
 *
 * **Best Practice:**
 * Always use Room Flow or reactive data source + animateItem() for smooth UX.
 * Never manually remove items from the list - let the reactive data source handle it.
 */

/**
 * Extension function for LazyColumn/LazyRow items.
 * Applies Gmail-style slide-off animation when item is removed from list.
 *
 * Note: This function must be called within LazyItemScope context.
 *
 * @return [Modifier] with animateItem() applied for smooth removal animation
 * @see SwipeableDocumentCardContainer Typical usage with swipe-to-delete
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyItemScope.animateItemRemoval(): Modifier = Modifier.animateItem()

/**
 * Extension function for LazyVerticalGrid/LazyHorizontalGrid items.
 * Applies Gmail-style slide-off animation when item is removed from grid.
 *
 * Note: This function must be called within LazyGridItemScope context.
 *
 * @return [Modifier] with animateItem() applied for smooth removal animation
 * @see SwipeableDocumentCardContainer Typical usage with swipe-to-delete
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyGridItemScope.animateItemRemoval(): Modifier = Modifier.animateItem()
