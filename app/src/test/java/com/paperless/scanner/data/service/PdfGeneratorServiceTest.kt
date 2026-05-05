package com.paperless.scanner.data.service

import android.content.Context
import android.net.Uri
import com.paperless.scanner.R
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PdfGeneratorServiceTest {

    private lateinit var context: Context
    private lateinit var imageProcessor: ImageProcessorService
    private lateinit var service: PdfGeneratorService
    private lateinit var tempCacheDir: File

    private val uri1: Uri = Uri.parse("content://test/page1.jpg")
    private val uri2: Uri = Uri.parse("content://test/page2.jpg")
    private val uri3: Uri = Uri.parse("content://test/page3.jpg")

    /**
     * Real 1x1 white JPEG bytes. Decodes successfully via iText's ImageDataFactory.
     * Generated once and inlined to keep tests hermetic.
     */
    private val realJpegBytes: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 'J'.code.toByte(),
        'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00, 0x01, 0x01, 0x00,
        0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43,
        0x00, 0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09,
        0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12,
        0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20,
        0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29,
        0x2C, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32,
        0x3C, 0x2E, 0x33, 0x34, 0x32, 0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B,
        0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xFF.toByte(),
        0xC4.toByte(), 0x00, 0x1F, 0x00, 0x00, 0x01, 0x05, 0x01, 0x01, 0x01,
        0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
        0xFF.toByte(), 0xC4.toByte(), 0x00, 0xB5.toByte(), 0x10, 0x00, 0x02,
        0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00,
        0x01, 0x7D, 0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21,
        0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32,
        0x81.toByte(), 0x91.toByte(), 0xA1.toByte(), 0x08, 0x23, 0x42,
        0xB1.toByte(), 0xC1.toByte(), 0x15, 0x52, 0xD1.toByte(), 0xF0.toByte(),
        0x24, 0x33, 0x62, 0x72, 0x82.toByte(), 0xFF.toByte(), 0xDA.toByte(),
        0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0xFB.toByte(),
        0xD0.toByte(), 0xFF.toByte(), 0xD9.toByte()
    )

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        imageProcessor = mockk()
        tempCacheDir = createTempDirectory(prefix = "pdfgen-test").toFile()

        every { context.cacheDir } returns tempCacheDir
        every { context.getString(R.string.error_first_image_process_failed) } returns "first_image_failed"
        every { context.getString(R.string.error_pdf_no_pages) } returns "no_pages"
        every { context.getString(R.string.error_pdf_not_created) } returns "pdf_not_created"
        every { context.getString(R.string.error_pdf_creation_failed, any<String>()) } returns "pdf_creation_failed"

        service = PdfGeneratorService(context, imageProcessor)
    }

    @After
    fun teardown() {
        tempCacheDir.deleteRecursively()
    }

    @Test
    fun `creates PDF from a single image successfully`() {
        every { imageProcessor.getImageBytesFromUri(uri1) } returns realJpegBytes

        val pdf = service.createPdfFromImages(listOf(uri1))

        assertTrue(pdf.exists())
        assertTrue(pdf.length() > 0)
        assertTrue(pdf.name.endsWith(".pdf"))
    }

    @Test
    fun `creates PDF from three images successfully`() {
        every { imageProcessor.getImageBytesFromUri(any()) } returns realJpegBytes

        val pdf = service.createPdfFromImages(listOf(uri1, uri2, uri3))

        assertTrue(pdf.exists())
        assertTrue(pdf.length() > 0)
    }

    @Test
    fun `rethrows IllegalStateException when first image fails`() {
        every { imageProcessor.getImageBytesFromUri(uri1) } throws RuntimeException("boom")

        assertThrows(IllegalStateException::class.java) {
            service.createPdfFromImages(listOf(uri1))
        }
    }

    @Test
    fun `skips failing non-first page and produces partial PDF`() {
        every { imageProcessor.getImageBytesFromUri(uri1) } returns realJpegBytes
        every { imageProcessor.getImageBytesFromUri(uri2) } throws RuntimeException("page-2-bad")
        every { imageProcessor.getImageBytesFromUri(uri3) } returns realJpegBytes

        val pdf = service.createPdfFromImages(listOf(uri1, uri2, uri3))

        assertTrue(pdf.exists())
        assertTrue(pdf.length() > 0)
    }

    @Test
    fun `throws IllegalStateException when uri list is empty`() {
        assertThrows(IllegalStateException::class.java) {
            service.createPdfFromImages(emptyList())
        }
    }

    @Test
    fun `cleans up partial PDF file when first image fails`() {
        every { imageProcessor.getImageBytesFromUri(uri1) } throws RuntimeException("boom")

        runCatching { service.createPdfFromImages(listOf(uri1)) }

        // No leftover PDF file in cacheDir
        val leftovers = tempCacheDir.listFiles { f -> f.name.endsWith(".pdf") } ?: emptyArray()
        assertTrue("Partial PDF should be cleaned up", leftovers.isEmpty())
    }
}
