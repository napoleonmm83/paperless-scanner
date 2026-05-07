package com.paperless.scanner.data.api

import com.paperless.scanner.util.withRetry
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

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
