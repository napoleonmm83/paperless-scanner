package com.paperless.scanner.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injectable bundle of [CoroutineDispatcher]s.
 *
 * Replaces scattered single-dispatcher injection so use cases / ViewModels can be tested
 * with a single [kotlinx.coroutines.test.TestDispatcher] pinned to all three slots, and so a
 * dependency carries one parameter instead of several.
 */
data class CoroutineDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
)
