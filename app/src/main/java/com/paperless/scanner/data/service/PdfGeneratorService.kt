package com.paperless.scanner.data.service

import android.content.Context
import android.net.Uri
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document as ITextDocument
import com.itextpdf.layout.element.Image
import com.paperless.scanner.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-page PDF assembly service extracted from DocumentRepository as part of
 * issue #51 Phase 1.2. Delegates image-byte loading to ImageProcessorService.
 *
 * Contract:
 * - Page 0 failure rethrows as IllegalStateException (cannot create empty PDF).
 * - Page N>0 failure logs and skips (graceful — partial PDF is better than none).
 * - Empty result (numberOfPages == 0) throws IllegalStateException.
 * - Cleans up the partial PDF file on any rethrown exception.
 */
@Singleton
class PdfGeneratorService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageProcessor: ImageProcessorService
) {
    fun createPdfFromImages(uris: List<Uri>): File {
        val fileName = "document_${System.currentTimeMillis()}.pdf"
        val pdfFile = File(context.cacheDir, fileName)

        try {
            PdfWriter(pdfFile).use { writer ->
                PdfDocument(writer).use { pdfDoc ->
                    ITextDocument(pdfDoc).use { document ->
                        uris.forEachIndexed { index, uri ->
                            try {
                                val imageBytes = imageProcessor.getImageBytesFromUri(uri)
                                val imageData = ImageDataFactory.create(imageBytes)
                                val image = Image(imageData)

                                // Calculate page size based on image dimensions
                                val pageWidth = image.imageWidth
                                val pageHeight = image.imageHeight
                                val pageSize = PageSize(pageWidth, pageHeight)

                                // Add new page with image dimensions
                                pdfDoc.addNewPage(pageSize)
                                // Bind image to the page actually created. Using `index + 1` is wrong
                                // because a prior failure leaves PDF page indices misaligned with input indices.
                                val pageNum = pdfDoc.numberOfPages

                                // Scale image to fit page
                                image.setFixedPosition(pageNum, 0f, 0f)
                                image.scaleToFit(pageWidth, pageHeight)

                                document.add(image)
                            } catch (e: Exception) {
                                // Log but continue with next image (partial PDF better than none)
                                android.util.Log.e(
                                    "PdfGeneratorService",
                                    "Failed to add image ${index + 1}/${uris.size} to PDF: ${e.message}",
                                    e
                                )
                                // If first image fails, rethrow (can't create empty PDF)
                                if (index == 0) {
                                    throw IllegalStateException(
                                        context.getString(R.string.error_first_image_process_failed),
                                        e
                                    )
                                }
                            }
                        }

                        // Verify we have at least one page
                        if (pdfDoc.numberOfPages == 0) {
                            throw IllegalStateException(context.getString(R.string.error_pdf_no_pages))
                        }
                    }
                }
            }

            // Verify PDF file was created and is not empty
            if (!pdfFile.exists() || pdfFile.length() == 0L) {
                throw IllegalStateException(context.getString(R.string.error_pdf_not_created))
            }

            return pdfFile
        } catch (e: Exception) {
            // Clean up partial file on error
            if (pdfFile.exists()) {
                pdfFile.delete()
            }
            // Re-throw with more context
            throw when (e) {
                is IllegalStateException -> e
                is IllegalArgumentException -> e
                else -> IllegalStateException(
                    context.getString(R.string.error_pdf_creation_failed, e.message ?: ""),
                    e
                )
            }
        }
    }
}
