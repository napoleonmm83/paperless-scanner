package com.paperless.scanner.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Wraps each upstream emission in Result.success(). If the upstream Flow
 * throws, emits Result.failure(e) instead of propagating the exception.
 * CancellationException is always re-thrown so coroutine cancellation works correctly.
 */
fun <T> Flow<T>.asUiResult(): Flow<Result<T>> =
    map { Result.success(it) }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(Result.failure(e))
        }
