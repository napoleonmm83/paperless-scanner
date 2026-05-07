package com.paperless.scanner.data.api

import com.paperless.scanner.BuildConfig
import com.paperless.scanner.data.datastore.CloudflareDetectionHolder
import com.paperless.scanner.util.NetworkConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Application interceptor that adapts the OkHttp write timeout for document
 * upload requests based on payload size and Cloudflare presence.
 *
 * Resolves issue #122 (F-095): the static 60-second
 * [NetworkConfig.WRITE_TIMEOUT_SECONDS] was insufficient for large
 * multi-page PDFs on slow connections. This interceptor applies a
 * per-request override only on the upload endpoint
 * (`POST /api/documents/post_document/`):
 *
 * - **Base:** [NetworkConfig.WRITE_TIMEOUT_BASE_SECONDS] (120s)
 * - **Per MB:** + [NetworkConfig.WRITE_TIMEOUT_PER_MB_SECONDS] (2s) per
 *   megabyte of request body content length
 * - **Cloudflare cap:** when [CloudflareDetectionHolder] reports `true`,
 *   the result is capped at
 *   [NetworkConfig.WRITE_TIMEOUT_CLOUDFLARE_CAP_SECONDS] (90s) — Cloudflare
 *   enforces a 100-second edge timeout, so any longer value would be killed
 *   by the proxy before reaching the origin
 *
 * Non-upload requests pass through unchanged; the global writeTimeout
 * configured on the [okhttp3.OkHttpClient] applies via OkHttp's defaults.
 *
 * **Unknown body size:** chunked or streaming bodies report `-1` for
 * [okhttp3.RequestBody.contentLength]. In that case the interceptor falls
 * back to the base timeout without any size-derived increment.
 */
class AdaptiveWriteTimeoutInterceptor(
    private val cloudflareDetectionHolder: CloudflareDetectionHolder,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isUploadRequest(request)) {
            return chain.proceed(request)
        }

        val bodyBytes = request.body?.contentLength()?.coerceAtLeast(0L) ?: 0L
        val sizeMb = (bodyBytes + BYTES_PER_MB - 1L) / BYTES_PER_MB
        var timeoutSec =
            NetworkConfig.WRITE_TIMEOUT_BASE_SECONDS +
                sizeMb * NetworkConfig.WRITE_TIMEOUT_PER_MB_SECONDS

        val cloudflareDetected = cloudflareDetectionHolder.current() == true
        if (cloudflareDetected) {
            timeoutSec = timeoutSec.coerceAtMost(NetworkConfig.WRITE_TIMEOUT_CLOUDFLARE_CAP_SECONDS)
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                TAG,
                "Upload writeTimeout=${timeoutSec}s (sizeMB=$sizeMb, cloudflare=$cloudflareDetected)",
            )
        }

        return chain
            .withWriteTimeout(timeoutSec.toInt(), TimeUnit.SECONDS)
            .proceed(request)
    }

    private fun isUploadRequest(request: okhttp3.Request): Boolean {
        if (request.method != "POST") return false
        val path = request.url.encodedPath
        return path.endsWith(UPLOAD_PATH_SUFFIX) || path.endsWith(UPLOAD_PATH_SUFFIX_NO_SLASH)
    }

    companion object {
        private const val TAG = "AdaptiveWriteTimeout"
        private const val UPLOAD_PATH_SUFFIX = "/api/documents/post_document/"
        private const val UPLOAD_PATH_SUFFIX_NO_SLASH = "/api/documents/post_document"
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
