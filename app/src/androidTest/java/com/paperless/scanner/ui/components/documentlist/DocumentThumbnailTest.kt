package com.paperless.scanner.ui.components.documentlist

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.paperless.scanner.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real HTTPS fetch and PNG decode; also pins the observable Coil error transition. */
@OptIn(DelicateCoilApi::class)
class DocumentThumbnailTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val result = AtomicReference<ImageResult?>()
    private lateinit var previousLoader: ImageLoader
    private lateinit var loader: ImageLoader
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false) }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        previousLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .eventListenerFactory {
                object : EventListener() {
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        this@DocumentThumbnailTest.result.set(result)
                    }
                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        this@DocumentThumbnailTest.result.set(result)
                    }
                }
            }
            .build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After
    fun cleanup() {
        SingletonImageLoader.setUnsafe(previousLoader)
        loader.shutdown()
        server.shutdown()
    }

    private fun showThumbnail() {
        val baseUrl = server.url("/").toString()
        compose.setContent {
            MaterialTheme {
                DocumentThumbnail(7, baseUrl, true, Modifier.testTag("thumbnail"))
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { result.get() != null }
        compose.waitForIdle()
        assertEquals("/api/documents/7/thumb/", server.takeRequest().path)
    }

    @Test
    fun successfulFetchDrawsDecodedThumbnail() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
        showThumbnail()
        assertTrue("PNG must actually decode", result.get() is SuccessResult)
        compose.onNodeWithContentDescription(context.getString(R.string.cd_document_thumbnail)).assertIsDisplayed()
        // Wait for the existing crossfade before checking the actual rendered pixels.
        compose.mainClock.advanceTimeBy(500)
        val pixels = compose.onNodeWithTag("thumbnail").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("decoded green image must be visible", center.green > 0.9f && center.red < 0.1f)
    }

    @Test
    fun failedFetchReplacesImageWithBrokenImageFallback() {
        server.enqueue(MockResponse().setResponseCode(404))
        showThumbnail()
        assertTrue("request must enter Coil's error state", result.get() is ErrorResult)
        compose.onNodeWithContentDescription(context.getString(R.string.cd_document_thumbnail)).assertDoesNotExist()
        compose.onNodeWithTag("thumbnail").assertIsDisplayed()
    }
}
