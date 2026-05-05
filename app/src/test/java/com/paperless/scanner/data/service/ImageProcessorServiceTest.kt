package com.paperless.scanner.data.service

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.slot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ImageProcessorServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var crashlyticsHelper: CrashlyticsHelper
    private lateinit var service: ImageProcessorService
    private lateinit var tempCacheDir: File

    private val testUri: Uri = Uri.parse("content://test/image.jpg")

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        crashlyticsHelper = mockk(relaxed = true)
        tempCacheDir = tempFolder.root

        every { context.contentResolver } returns contentResolver
        every { context.cacheDir } returns tempCacheDir
        every { context.getString(R.string.error_open_input_stream) } returns "open_input_stream_failed"
        every { context.getString(R.string.error_decode_image) } returns "decode_image_failed"

        service = ImageProcessorService(context, crashlyticsHelper)
    }

    // ---- getImageBytesFromUri ----

    @Test
    fun `getImageBytesFromUri fails fast with IllegalArgumentException when openInputStream returns null`() {
        // Both passes (decodeBounds + full decode) call openInputStream; null on first pass throws immediately.
        every { contentResolver.openInputStream(testUri) } returns null

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.getImageBytesFromUri(testUri)
        }
        assertEquals("open_input_stream_failed", ex.message)
    }

    @Test
    fun `getImageBytesFromUri throws IllegalStateException when BitmapFactory decodeStream returns null`() {
        val emptyStream: InputStream = ByteArrayInputStream(ByteArray(0))
        every { contentResolver.openInputStream(testUri) } returns emptyStream

        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeStream(any(), null, any()) } returns null

        try {
            val ex = assertThrows(IllegalStateException::class.java) {
                service.getImageBytesFromUri(testUri)
            }
            assertEquals("decode_image_failed", ex.message)
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }

    @Test
    fun `getImageBytesFromUri logs breadcrumb with last URI segment`() {
        val emptyStream: InputStream = ByteArrayInputStream(ByteArray(0))
        every { contentResolver.openInputStream(testUri) } returns emptyStream
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeStream(any(), null, any()) } returns null

        try {
            runCatching { service.getImageBytesFromUri(testUri) }
            io.mockk.verify { crashlyticsHelper.logActionBreadcrumb("IMAGE_PROCESS", "image.jpg") }
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }

    @Test
    fun `getImageBytesFromUri returns compressed JPEG bytes for valid bitmap`() {
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.width } returns 1000
        every { bitmap.height } returns 1000
        every { bitmap.compress(Bitmap.CompressFormat.JPEG, any(), any()) } answers {
            (thirdArg<java.io.OutputStream>()).write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            true
        }

        every { contentResolver.openInputStream(testUri) } returns ByteArrayInputStream(ByteArray(0))
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeStream(any(), null, any()) } returns bitmap

        try {
            val bytes = service.getImageBytesFromUri(testUri)
            assertNotNull(bytes)
            assertEquals(3, bytes.size) // we wrote 3 bytes via compress mock
            io.mockk.verify { bitmap.recycle() }
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }

    // ---- compression-quality bucket coverage (via getImageBytesFromUri) ----

    @Test
    fun `compression quality is 85 for small images of 4MP or less`() {
        val capturedQuality = exerciseCompressionWithDimensions(width = 1000, height = 1000) // 1MP
        assertEquals(85, capturedQuality)
    }

    @Test
    fun `compression quality is 80 for medium images above 4MP`() {
        val capturedQuality = exerciseCompressionWithDimensions(width = 3000, height = 2000) // 6MP
        assertEquals(80, capturedQuality)
    }

    @Test
    fun `compression quality is 75 for large images above 8MP`() {
        val capturedQuality = exerciseCompressionWithDimensions(width = 4000, height = 3000) // 12MP
        // Note: pixel count is exactly 12_000_000 -> falls into 8MP bucket per strict greater-than boundary.
        assertEquals(75, capturedQuality)
    }

    @Test
    fun `compression quality is 70 for very large images above 12MP`() {
        val q = exerciseCompressionWithDimensions(width = 5000, height = 3000) // 15MP
        assertEquals(70, q)
    }

    // ---- getFileFromUri ----

    @Test
    fun `getFileFromUri writes bytes to a new file in cacheDir`() {
        val payload = "hello-bytes".toByteArray()
        every { contentResolver.openInputStream(testUri) } returns ByteArrayInputStream(payload)

        val file = service.getFileFromUri(testUri)

        assertTrue(file.exists())
        assertEquals(tempCacheDir.absolutePath, file.parentFile?.absolutePath)
        assertTrue(file.name.startsWith("document_"))
        assertTrue(file.name.endsWith(".jpg"))
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun `getFileFromUri throws IllegalArgumentException when openInputStream returns null`() {
        every { contentResolver.openInputStream(testUri) } returns null

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.getFileFromUri(testUri)
        }
        assertEquals("open_input_stream_failed", ex.message)
    }

    @Test
    fun `getFileFromUri closes the input stream even when copyTo fails`() {
        val failingStream: InputStream = mockk(relaxed = true)
        every { failingStream.read(any()) } throws java.io.IOException("read-boom")
        every { failingStream.read(any(), any(), any()) } throws java.io.IOException("read-boom")
        every { contentResolver.openInputStream(testUri) } returns failingStream

        runCatching { service.getFileFromUri(testUri) }

        io.mockk.verify { failingStream.close() }
    }

    // ---- helpers ----

    /** Drives getImageBytesFromUri end-to-end with a synthetic bitmap and returns the JPEG quality used. */
    private fun exerciseCompressionWithDimensions(width: Int, height: Int): Int {
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.width } returns width
        every { bitmap.height } returns height
        val qualitySlot = slot<Int>()
        every { bitmap.compress(Bitmap.CompressFormat.JPEG, capture(qualitySlot), any()) } returns true

        every { contentResolver.openInputStream(testUri) } returns ByteArrayInputStream(ByteArray(0))
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeStream(any(), null, any()) } returns bitmap

        return try {
            service.getImageBytesFromUri(testUri)
            qualitySlot.captured
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }
}
