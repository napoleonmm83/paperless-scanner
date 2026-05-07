package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.ServerStatusResponse
import com.paperless.scanner.util.withRetry
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the raw Retrofit `PaperlessApi.getServerStatus()` so UI ViewModels do
 * not depend on the data.api layer. Centralises the x-version header merge that
 * was previously duplicated between DiagnosticsViewModel and SettingsViewModel.
 */
@Singleton
class ServerStatusRepository @Inject constructor(
    private val api: PaperlessApi
) {
    suspend fun getServerStatus(): Result<ServerStatusResponse> = try {
        // withRetry handles 5xx by promoting Response to HttpException; 4xx falls
        // through and is reported as a non-retried failure below.
        val response = withRetry {
            val r = api.getServerStatus()
            if (r.code() in 500..599) throw HttpException(r)
            r
        }
        if (!response.isSuccessful) {
            throw HttpException(response)
        }
        val body = response.body() ?: throw IOException("Empty response body")
        val headerVersion = response.headers()["x-version"]?.takeIf { it.isNotBlank() }
        val mergedVersion = body.paperlessVersion?.takeIf { it.isNotBlank() } ?: headerVersion
        Result.success(body.copy(paperlessVersion = mergedVersion))
    } catch (e: CancellationException) {
        // Must propagate so coroutine cancellation is not swallowed.
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
