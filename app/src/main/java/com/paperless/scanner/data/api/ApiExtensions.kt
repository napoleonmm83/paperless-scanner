package com.paperless.scanner.data.api

import android.util.Log
import com.paperless.scanner.data.api.models.PaginatedResponse
import com.paperless.scanner.util.NetworkConfig
import com.paperless.scanner.util.withRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import retrofit2.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Extension functions for safe API calls with proper error handling.
 */

/**
 * Executes an API call safely, wrapping any exception in PaperlessException.
 *
 * Retries transient failures (IOException and 5xx HttpException) at the suspend
 * boundary via [withRetry] — replaces the previous blocking RetryInterceptor.
 *
 * [CancellationException] propagates unchanged so coroutine cancellation is
 * never swallowed (catch order matters; see PR #195).
 *
 * Usage:
 * ```kotlin
 * suspend fun getTags(): Result<List<Tag>> = safeApiCall {
 *     api.getTags()
 * }
 * ```
 */
suspend fun <T> safeApiCall(
    apiCall: suspend () -> T
): Result<T> {
    return try {
        Result.success(withRetry { apiCall() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(PaperlessException.from(e))
    }
}

/**
 * Fetches every page of a Paperless-ngx paginated list endpoint and returns the
 * concatenated [PaginatedResponse.results].
 *
 * Walks pages `1..N`, stopping as soon as the server reports no further page
 * ([PaginatedResponse.next] is null/blank). Each page is fetched through
 * [withRetry] because the list endpoints are idempotent GETs.
 *
 * Before this helper, callers fetched only page 1 and silently dropped every item
 * beyond the page size (Issue #126).
 *
 * [maxPages] is a safety cap: a server that never clears `next` cannot spin an
 * unbounded loop. If the cap is reached, the results gathered so far are returned
 * and a warning is logged — the truncation is explicit, never silent.
 *
 * [CancellationException] propagates unchanged (thrown from within [withRetry]).
 */
suspend fun <T> fetchAllPages(
    maxPages: Int = NetworkConfig.MAX_PAGINATED_PAGES,
    fetchPage: suspend (page: Int) -> PaginatedResponse<T>
): List<T> {
    val all = mutableListOf<T>()
    var page = 1
    while (page <= maxPages) {
        val response = withRetry { fetchPage(page) }
        all += response.results
        if (response.next.isNullOrBlank()) {
            return all
        }
        page++
    }
    Log.w(
        "Pagination",
        "Reached maxPages=$maxPages cap while the server still reported more pages; " +
            "returning ${all.size} items."
    )
    return all
}

/**
 * Bounds a single network read with a coroutine-level total timeout (#82).
 *
 * The OkHttp client deliberately configures only connect + read (per-socket) timeouts, NOT
 * a global `callTimeout` — a `callTimeout` would break the adaptive long upload timeouts. A
 * response that keeps trickling bytes (each individual socket read staying under the read
 * timeout) could therefore stall a list GET indefinitely. Wrapping the read in [withTimeout]
 * gives it the `callTimeout`-equivalent it lacks, scoped to these reads only.
 *
 * A timeout is surfaced as an [IOException] (a genuine network failure) so callers report it
 * as a failure instead of swallowing it. Only **this** function's own timeout is converted:
 * if the surrounding coroutine was itself cancelled (e.g. a caller wrapping the read in its
 * own [withTimeout]), that cancellation arrives here as a [TimeoutCancellationException] too,
 * so [ensureActive] re-checks the outer scope and lets the cancellation propagate unchanged —
 * structured cancellation is preserved and `withRetry` never retries a cancel.
 */
suspend fun <T> withReadTimeout(
    timeoutMs: Long = NetworkConfig.READ_TIMEOUT_SECONDS * 1000,
    block: suspend () -> T
): T {
    return try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        // If the OUTER coroutine was cancelled, honour that cancellation rather than masking
        // it as a retryable IOException (ensureActive rethrows the outer CancellationException).
        coroutineContext.ensureActive()
        throw IOException("Network read timed out after ${timeoutMs}ms")
    }
}

/**
 * Executes an API call that returns a Response, handling both network and HTTP errors.
 *
 * Usage:
 * ```kotlin
 * suspend fun login(): Result<TokenResponse> = safeApiResponse {
 *     api.login(credentials)
 * }
 * ```
 */
suspend fun <T> safeApiResponse(
    apiCall: suspend () -> Response<T>
): Result<T> {
    return try {
        val response = withRetry {
            val r = apiCall()
            // Promote 5xx to HttpException so withRetry can retry it; 4xx falls
            // through unchanged and is reported below as a non-retried failure.
            if (r.code() in 500..599) {
                throw retrofit2.HttpException(r)
            }
            r
        }
        if (response.isSuccessful) {
            response.body()?.let { body ->
                Result.success(body)
            } ?: Result.failure(
                PaperlessException.ParseError("Empty server response")
            )
        } else {
            val errorBody = response.errorBody()?.string()
            Result.failure(
                PaperlessException.fromHttpCode(response.code(), errorBody)
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: retrofit2.HttpException) {
        val errorBody = e.response()?.errorBody()?.string()
        Result.failure(PaperlessException.fromHttpCode(e.code(), errorBody))
    } catch (e: IOException) {
        Result.failure(PaperlessException.NetworkError(e))
    } catch (e: Exception) {
        Result.failure(PaperlessException.from(e))
    }
}

/**
 * Executes an API call with a transformation function.
 *
 * Usage:
 * ```kotlin
 * suspend fun getTagNames(): Result<List<String>> = safeApiCallWithTransform(
 *     apiCall = { api.getTags() },
 *     transform = { response -> response.results.map { it.name } }
 * )
 * ```
 */
suspend fun <T, R> safeApiCallWithTransform(
    apiCall: suspend () -> T,
    transform: (T) -> R
): Result<R> {
    return try {
        val response = withRetry { apiCall() }
        Result.success(transform(response))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(PaperlessException.from(e))
    }
}

/**
 * Maps a Result's failure to a PaperlessException if it isn't already.
 */
fun <T> Result<T>.mapToPaperlessException(): Result<T> {
    return this.fold(
        onSuccess = { Result.success(it) },
        onFailure = { throwable ->
            Result.failure(PaperlessException.from(throwable))
        }
    )
}

/**
 * Extracts user-friendly error message from a Result's failure.
 */
fun <T> Result<T>.errorMessage(): String? {
    return exceptionOrNull()?.let { throwable ->
        when (throwable) {
            is PaperlessException -> throwable.userMessage
            else -> throwable.message ?: "Unknown error"
        }
    }
}

/**
 * Checks if the Result's failure is retryable.
 */
fun <T> Result<T>.isRetryable(): Boolean {
    return exceptionOrNull()?.let { throwable ->
        (throwable as? PaperlessException)?.isRetryable ?: false
    } ?: false
}

/**
 * Checks if the Result's failure requires re-authentication.
 */
fun <T> Result<T>.requiresReauth(): Boolean {
    return exceptionOrNull()?.let { throwable ->
        (throwable as? PaperlessException)?.requiresReauth ?: false
    } ?: false
}
