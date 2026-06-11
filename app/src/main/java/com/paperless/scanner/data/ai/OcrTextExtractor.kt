package com.paperless.scanner.data.ai

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.paperless.scanner.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * On-device OCR feeding the local tag-matching fallback (#296).
 *
 * Contract for implementations:
 * - NEVER throws except [CancellationException]; any failure returns "" so OCR can
 *   never break an analysis that would otherwise succeed.
 * - NEVER logs recognized content (lengths/exceptions only) — document text is
 *   sensitive, and this exact recognizer output has a log-leak history (#240).
 */
interface OcrTextExtractor {
    suspend fun extractText(bitmap: Bitmap): String
}

/**
 * ML Kit Latin text recognition (bundled model, already shipped for the login token
 * scanner). Latin script covers German incl. umlauts/ß; non-Latin documents yield
 * empty/garbage text, which degrades to the pre-OCR behavior (no local suggestions).
 */
@Singleton
class MlKitOcrTextExtractor @Inject constructor() : OcrTextExtractor {

    // Lazy: keeps construction side-effect-free for the DI graph and JVM tests — the
    // model only loads on first real use. Deliberately NEVER closed, unlike
    // TokenScannerSheet's per-session client: this is a process-wide hot-path
    // singleton, and close() cancels in-flight recognition Tasks (which would strand
    // the canceled-listener path below for concurrent callers).
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun extractText(bitmap: Bitmap): String = try {
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // A Task fires exactly one listener exactly once, and resuming a
                    // CANCELLED continuation is a documented no-op — plain resume is safe.
                    AppLogger.d(TAG, "OCR ok (${visionText.text.length} chars)")
                    cont.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    AppLogger.w(TAG, "OCR failed — falling back to empty text", e)
                    cont.resume("")
                }
                .addOnCanceledListener {
                    // Hang-proofing: a canceled Task fires NEITHER success nor failure.
                    cont.resume("")
                }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Synchronous throws (recycled bitmap in fromBitmap, MlKitException from
        // getClient/process) must honor the never-fail contract too.
        AppLogger.w(TAG, "OCR setup failed — falling back to empty text", e)
        ""
    }

    private companion object {
        // <=11 chars so AppLogger's "Paperless.{tag}" stays within Android's tag cap.
        private const val TAG = "OcrExtract"
    }
}
