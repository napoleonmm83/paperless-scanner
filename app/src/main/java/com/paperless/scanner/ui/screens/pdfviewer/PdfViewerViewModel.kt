package com.paperless.scanner.ui.screens.pdfviewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsServiceContract
import com.paperless.scanner.data.analytics.CrashlyticsHelperContract
import com.paperless.scanner.data.analytics.DiagnosticReport
import com.paperless.scanner.data.analytics.DiagnosticsReportService
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.domain.error.getLocalizedMessage
import com.paperless.scanner.util.DiagnosticReportSender
import com.paperless.scanner.util.SharedFileCache
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed class PdfViewerUiState {
    data object Idle : PdfViewerUiState()
    data class Downloading(val progress: Float = 0f) : PdfViewerUiState()
    data class Viewing(val pdfFile: File, val currentPage: Int = 0, val totalPages: Int = 0, val isPdf: Boolean = true) : PdfViewerUiState()

    /**
     * @param canOpenExternally the file arrived but this app cannot render it. Offering
     *   the system handler turns a dead end into a possible way forward. `/download/`
     *   serves the archive version when Paperless has built one and the original
     *   otherwise, so the realistic case here is a format Paperless does not archive —
     *   a text or CSV document — rather than the DOCX one would first guess. Unverified
     *   against a live server; the branch does not depend on which it is.
     */
    /**
     * @param canSendReport offer the diagnostic report. True only where the app does NOT
     *   know what went wrong — an unclassified mapper result, unusable content, a render
     *   failure. Deliberately false for "check your internet connection" and an expired
     *   session: a report about those teaches us nothing, and a button that appears on
     *   every error is a button nobody reads.
     */
    data class Error(
        val message: String,
        val canOpenExternally: Boolean = false,
        val canSendReport: Boolean = false
    ) : PdfViewerUiState()
}

/**
 * What actually came down the wire, decided by magic bytes rather than by the
 * `Content-Type` header — a reverse proxy that answers with its own login page sets
 * whatever header it likes, and the bytes are the only thing that cannot lie.
 */
internal enum class DocumentContent { PDF, IMAGE, WEB_PAGE, UNSUPPORTED, UNREADABLE }

/**
 * @param signature a bounded format label, e.g. `504b0304` for the ZIP container behind
 *   DOCX and ODT. Carried alongside the kind because the kind alone repeats the mistake
 *   this change exists to fix: "UNSUPPORTED" counts a set without splitting it, and the
 *   signature is what tells one case from another.
 *
 *   It is produced by `formatSignature`, which collapses anything that reads as plain
 *   text to the constant `"text"` — an earlier version shipped the raw leading bytes,
 *   and for a text or CSV original those bytes are the opening characters of the user's
 *   own document. See that function for why the collapse loses nothing diagnostic.
 */
internal data class ContentProbe(
    val kind: DocumentContent,
    val signature: String
)

@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val documentRepository: DocumentRepository,
    private val analyticsService: AnalyticsServiceContract,
    private val crashlyticsHelper: CrashlyticsHelperContract,
    private val diagnosticsReportService: DiagnosticsReportService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val documentId: Int = savedStateHandle.get<String>("documentId")?.toIntOrNull() ?: 0
    val documentTitle: String = savedStateHandle.get<String>("documentTitle") ?: context.getString(R.string.document)

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Idle)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    /**
     * The downloaded file, kept independently of [uiState].
     *
     * [openInExternalApp] used to read the file out of the Viewing state and return
     * early for anything else. That was fine while the only way to reach the button was
     * the toolbar of a document already on screen — but the error screen now offers it
     * too, for the case where the file arrived intact and only this app cannot render
     * it. Reading from the state there would have made the button do nothing at all,
     * which is worse than not offering it.
     */
    private var lastDownload: File? = null
    private var lastMimeType: String = MIME_PDF

    init {
        analyticsService.trackEvent(AnalyticsEvent.PdfViewerOpened)
        downloadDocument()
    }

    fun downloadDocument() {
        viewModelScope.launch {
            // A new attempt is a new document: page failures from the previous one must
            // not suppress reporting for this one.
            reportedPageFailures.clear()
            _uiState.update { PdfViewerUiState.Downloading(0f) }

            documentRepository.downloadDocument(
                documentId = documentId,
                onProgress = { progress ->
                    _uiState.update { PdfViewerUiState.Downloading(progress) }
                }
            ).onSuccess { file ->
                // The extension is deliberately NOT consulted. DocumentRepository names
                // every download "document_<id>_<ts>.pdf" regardless of what arrived, so
                // the old `file.extension == "pdf" || isPdfFile(file)` was always true on
                // its first term and the magic-byte check behind the `||` never ran. An
                // HTML login page from a reverse proxy therefore counted as a PDF, went
                // to PdfRenderer, threw, and left the screen on its loading spinner
                // forever — no message, no telemetry, nothing to report.
                val probe = detectContent(file)
                // Drop the previous download before forgetting it. "Try again" on an
                // error screen that FOLLOWED a successful download — the DOCX case, a
                // web page, an unreadable file — leaves a real file on disk, and
                // overwriting the reference would strand it until the hourly sweep. That
                // sequence did not exist before this change: an error used to mean no
                // file had arrived at all. onCleared only ever sees the last one.
                lastDownload?.takeIf { it != file }?.delete()
                lastDownload = file
                lastMimeType = when (probe.kind) {
                    DocumentContent.PDF -> MIME_PDF
                    DocumentContent.IMAGE -> MIME_IMAGE
                    // We know the bytes are not PDF and not an image, and the magic
                    // number cannot name the format any more precisely — DOCX, ODT and
                    // XLSX are all the same ZIP container. Letting the system decide
                    // beats declaring a type we would be guessing at.
                    else -> MIME_ANY
                }
                when (probe.kind) {
                    DocumentContent.PDF ->
                        _uiState.update { PdfViewerUiState.Viewing(file, isPdf = true) }

                    DocumentContent.IMAGE ->
                        _uiState.update { PdfViewerUiState.Viewing(file, isPdf = false) }

                    DocumentContent.WEB_PAGE -> failWithContent(
                        probe = probe,
                        message = context.getString(R.string.error_server_sent_webpage)
                    )

                    DocumentContent.UNSUPPORTED -> failWithContent(
                        probe = probe,
                        message = context.getString(R.string.error_unsupported_document_type),
                        // Often not a defect at all: Paperless serves the original when
                        // it has no archive version, so a text or CSV document lands here
                        // legitimately. But this is also the fall-through of the content
                        // check, so a download that lost its first bytes lands here too —
                        // which is why the message names no format and the offer is
                        // phrased as something to try, not as a diagnosis.
                        canOpenExternally = true
                    )

                    DocumentContent.UNREADABLE -> failWithContent(
                        probe = probe,
                        message = context.getString(R.string.error_document_unreadable)
                    )
                }
            }.onFailure { error ->
                // Without this the failure was invisible: the raw throwable message
                // went straight to the UI (untranslated, often just an enum name) and
                // nothing was reported, so real-world causes never reached us.
                crashlyticsHelper.recordException(error)

                // errorType alone reported "UnknownError" for every failure the mapper
                // could not classify — the one set we actually needed to split. The tag
                // splits it ("HTTP 304", "JsonSyntaxException"), and PaperlessException
                // overrides it per subtype so this call site does not special-case any.
                val paperlessError = error as? PaperlessException
                analyticsService.trackEvent(
                    AnalyticsEvent.PdfViewerDownloadFailed(
                        errorType = error::class.simpleName ?: "Unknown",
                        diagnosticTag = paperlessError?.diagnosticTag
                            ?: error::class.simpleName ?: "Unknown"
                    )
                )
                // Fill the report so the button on the error screen has something to
                // send. Without this call lastReport stays null, the button never
                // appears, and the feature is dead on exactly the path it was built for.
                diagnosticsReportService.logFailure(
                    authType = DiagnosticReport.Source.DOCUMENT_DOWNLOAD,
                    serverUrl = null, // hashed by the service; the VM does not hold it
                    errorType = error::class.simpleName,
                    errorMessage = paperlessError?.diagnosticTag ?: error::class.simpleName
                )

                // Only where the mapper could not classify. A report about a DNS failure
                // or an expired session tells us nothing we do not already read off the
                // message, and a button that appears on every error is one nobody reads.
                val unclassified = paperlessError is PaperlessException.UnknownError ||
                    paperlessError == null
                if (unclassified) {
                    analyticsService.trackEvent(AnalyticsEvent.DiagnosticReportOffered)
                }

                _uiState.update {
                    PdfViewerUiState.Error(
                        message = (error as? PaperlessException)?.getLocalizedMessage(context)
                            ?: context.getString(R.string.error_download),
                        canSendReport = unclassified
                    )
                }
            }
        }
    }

    /**
     * Reports a download that arrived but cannot be shown.
     *
     * Every branch reports. The predecessor of this code had error paths with no
     * telemetry at all, which is why the cause behind a July 2026 Play review was still
     * unknown when the same complaint arrived in September: the exception was discarded
     * before anything could see it. A branch nobody can observe is a branch nobody
     * learns from.
     */
    private fun failWithContent(
        probe: ContentProbe,
        message: String,
        canOpenExternally: Boolean = false
    ) {
        crashlyticsHelper.logStateBreadcrumb(
            "PDF_VIEWER_CONTENT",
            "${probe.kind.name} sig=${probe.signature}"
        )
        analyticsService.trackEvent(
            AnalyticsEvent.PdfViewerDownloadFailed(
                errorType = "UnusableContent",
                diagnosticTag = "${probe.kind.name}/${probe.signature}"
            )
        )
        diagnosticsReportService.logFailure(
            authType = DiagnosticReport.Source.DOCUMENT_DOWNLOAD,
            serverUrl = null,
            errorType = "UnusableContent",
            errorMessage = "${probe.kind.name}/${probe.signature}"
        )
        // The file arrived and we cannot use it — that is precisely the case nobody can
        // diagnose from a screenshot, so the report is offered here too.
        analyticsService.trackEvent(AnalyticsEvent.DiagnosticReportOffered)
        _uiState.update {
            PdfViewerUiState.Error(message, canOpenExternally, canSendReport = true)
        }
    }

    /**
     * Called by the screen when [android.graphics.pdf.PdfRenderer] refuses the file.
     *
     * This path used to end in `e.printStackTrace()`: the UI stayed on its progress
     * indicator with no error, no retry and no report, for as long as the user cared to
     * wait. An encrypted PDF, a truncated download and a server error page saved under a
     * .pdf name all landed there.
     */
    /** Pages already reported this document, so a pre-rendered neighbour reports once. */
    private val reportedPageFailures = mutableSetOf<Int>()

    /**
     * ONE page could not be rendered. Deliberately does not touch [uiState].
     *
     * The pager pre-composes a neighbour on each side, so this fires for pages the user
     * has not even reached. Routing it into [onRenderFailure] — as an earlier version
     * did — replaced the whole document with an error screen because page 5 of 20 had a
     * corrupt content stream, while pages 1-4 and 6-20 were perfectly readable. That is
     * worse than the endless spinner it was meant to fix: it takes away something that
     * works to report something that does not.
     *
     * The page shows its own failure instead, and the report is deduplicated per page so
     * paging back and forth does not file the same non-fatal repeatedly.
     */
    fun onPageRenderFailure(pageIndex: Int, cause: Throwable) {
        if (!reportedPageFailures.add(pageIndex)) return

        crashlyticsHelper.recordException(cause)
        analyticsService.trackEvent(
            AnalyticsEvent.PdfViewerDownloadFailed(
                errorType = "PageRenderFailure",
                diagnosticTag = cause::class.java.simpleName.ifEmpty { "Unknown" }
            )
        )
    }

    fun onRenderFailure(cause: Throwable) {
        crashlyticsHelper.recordException(cause)
        analyticsService.trackEvent(
            AnalyticsEvent.PdfViewerDownloadFailed(
                errorType = "RenderFailure",
                diagnosticTag = cause::class.simpleName ?: "Unknown"
            )
        )
        diagnosticsReportService.logFailure(
            authType = DiagnosticReport.Source.DOCUMENT_RENDER,
            serverUrl = null,
            errorType = "RenderFailure",
            errorMessage = cause::class.simpleName
        )
        analyticsService.trackEvent(AnalyticsEvent.DiagnosticReportOffered)
        _uiState.update {
            PdfViewerUiState.Error(
                context.getString(R.string.error_document_unreadable),
                canOpenExternally = true,
                canSendReport = true
            )
        }
    }

    /**
     * Builds the report and hands it to the user's mail app.
     *
     * On IO because it spawns logcat and writes a file. The result is surfaced so the
     * screen can say something true rather than appearing to have done nothing — a
     * device without a mail app gets the report on the clipboard instead of silence.
     */
    fun sendDiagnosticReport(onResult: (DiagnosticReportSender.Result) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // The text is built regardless of whether the file could be written: a
                // failed write used to be reported as "no app available", which sent the
                // user looking for a mail app they already had. With the text in hand the
                // sender can still put it on the clipboard.
                val text = diagnosticsReportService.createFullReport()
                DiagnosticReportSender.send(
                    context = context,
                    reportFile = diagnosticsReportService.writeReportFile(context.cacheDir),
                    reportText = text,
                    subjectTag = (uiState.value as? PdfViewerUiState.Error)?.message.orEmpty()
                        .take(40)
                )
            }
            analyticsService.trackEvent(AnalyticsEvent.DiagnosticReportShared(result.name))
            onResult(result)
        }
    }

    /**
     * Classifies the downloaded bytes.
     *
     * Magic bytes only, on purpose: the file name is ours (always `.pdf`) and therefore
     * says nothing, and a `Content-Type` can be whatever a proxy in the way decides to
     * send.
     */
    private fun detectContent(file: File): ContentProbe {
        val header = try {
            file.inputStream().use { input ->
                val buffer = ByteArray(HEADER_BYTES)
                val read = input.read(buffer)
                // A zero-byte file is its own failure: the download "succeeded" and
                // produced nothing. Worth telling apart from a file we cannot open.
                if (read <= 0) return ContentProbe(DocumentContent.UNREADABLE, "empty")
                buffer.copyOf(read)
            }
        } catch (e: Exception) {
            // Previously this returned false and the document silently took the image
            // path. A file we cannot even open is a reportable failure, not a hint.
            crashlyticsHelper.recordException(e)
            // The class, not a constant: a file swept out from under us
            // (FileNotFoundException) and a permission problem (SecurityException) are
            // different bugs and were previously reported under the same label.
            return ContentProbe(
                DocumentContent.UNREADABLE,
                e::class.java.simpleName.ifEmpty { "unreadable" }
            )
        }

        val kind = when {
            header.containsPdfHeader() -> DocumentContent.PDF
            header.looksLikeImage(file.length()) -> DocumentContent.IMAGE
            header.looksLikeMarkup() -> DocumentContent.WEB_PAGE
            else -> DocumentContent.UNSUPPORTED
        }
        return ContentProbe(kind, header.formatSignature())
    }

    private fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean =
        size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

    /**
     * True if `%PDF` appears anywhere in the header window.
     *
     * NOT `startsWith`. pdfium — the engine behind [android.graphics.pdf.PdfRenderer] —
     * scans the first kilobyte for the marker, so a PDF preceded by a UTF-8 BOM, a stray
     * newline, or a few bytes of proxy preamble opens perfectly well. Requiring the
     * marker at offset 0 would reject files the app used to display, which is a
     * regression dressed up as a stricter check: the user would be told their valid PDF
     * is an unsupported file type.
     */
    private fun ByteArray.containsPdfHeader(): Boolean =
        indices.any { startsWith(PDF_MAGIC, it) }

    /**
     * True only for formats Coil can actually decode.
     *
     * Both awkward cases are checked properly rather than by their shortest prefix:
     * BMP's magic is the two printable bytes `BM`, which any text document beginning
     * "BMW invoice" would satisfy, so the declared file size has to match as well; and
     * `RIFF` is a container that also holds WAV and AVI, so the `WEBP` tag at offset 8
     * has to be there too. Matching either loosely would route a text file into the
     * image path and end in "the document could not be opened".
     */
    private fun ByteArray.looksLikeImage(fileLength: Long): Boolean = when {
        startsWith(PNG_MAGIC) || startsWith(JPEG_MAGIC) || startsWith(GIF_MAGIC) -> true
        startsWith(RIFF_MAGIC) -> startsWith(WEBP_TAG, offset = 8)
        startsWith(BMP_MAGIC) -> size >= 6 && littleEndianUInt(2) == fileLength
        else -> false
    }

    private fun ByteArray.littleEndianUInt(offset: Int): Long =
        (0..3).sumOf { (this[offset + it].toLong() and 0xFF) shl (8 * it) }

    /**
     * A bounded, content-free label for telemetry.
     *
     * The hex of the leading bytes identifies a container — `504b0304` is the ZIP behind
     * DOCX and ODT — which is exactly what makes an UNSUPPORTED report actionable. But
     * Paperless serves the ORIGINAL when it holds no archive version, and for a text or
     * CSV original those leading bytes ARE the first characters of the user's document.
     * This value goes to Firebase as an event dimension and to Crashlytics as a
     * breadcrumb, so shipping them would be a leak, and an unbounded one at that.
     *
     * Hence: emit the hex only when the prefix cannot be plain text. Every binary
     * container has at least one non-printable byte up front (`PK\u0003\u0004`,
     * `II*\u0000`), so nothing diagnostic is lost; anything that reads as
     * text collapses to one constant.
     */
    private fun ByteArray.formatSignature(): String {
        val prefix = take(4)
        if (prefix.isEmpty()) return "empty"
        val printable = prefix.all { b ->
            val c = b.toInt() and 0xFF
            c in 0x20..0x7E || c == 0x09 || c == 0x0A || c == 0x0D
        }
        return if (printable) "text" else prefix.joinToString("") { "%02x".format(it) }
    }

    /**
     * True for an HTML or XML document. Leading whitespace and a UTF-8 BOM are skipped
     * because a login page served by a proxy frequently starts with either.
     */
    private fun ByteArray.looksLikeMarkup(): Boolean {
        var index = 0
        if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) {
            index = 3
        }
        while (index < size && this[index].toInt().toChar().isWhitespace()) index++
        return index < size && this[index] == '<'.code.toByte()
    }

    private companion object {
        // One kilobyte because that is the window pdfium scans for the %PDF marker.
        // Reading less would make this check stricter than the renderer it is supposed
        // to predict, and reject PDFs the app can display.
        const val HEADER_BYTES = 1024

        const val MIME_PDF = "application/pdf"
        const val MIME_IMAGE = "image/*"
        const val MIME_ANY = "*/*"

        val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF

        // Only formats Coil can actually display — the point of the check is to decide
        // whether handing the file to the image path can succeed, not to catalogue
        // image formats.
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val GIF_MAGIC = byteArrayOf(0x47, 0x49, 0x46, 0x38) // GIF8
        val BMP_MAGIC = byteArrayOf(0x42, 0x4D) // "BM" — see looksLikeImage
        val RIFF_MAGIC = byteArrayOf(0x52, 0x49, 0x46, 0x46) // container, not a format
        val WEBP_TAG = byteArrayOf(0x57, 0x45, 0x42, 0x50) // at offset 8 inside RIFF
    }

    fun updatePageInfo(currentPage: Int, totalPages: Int) {
        _uiState.update { state ->
            if (state is PdfViewerUiState.Viewing) {
                state.copy(currentPage = currentPage, totalPages = totalPages)
            } else {
                state
            }
        }
    }

    fun shareDocument() {
        val state = _uiState.value
        if (state !is PdfViewerUiState.Viewing) return

        try {
            val uri = FileProvider.getUriForFile(
                context,
                SharedFileCache.authority(context.packageName),
                state.pdfFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                // Was hardcoded to application/pdf, which mislabels every image document
                // the viewer can already display. Now that the content is actually
                // classified, the share can declare what it really is.
                type = lastMimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, documentTitle)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.pdf_viewer_share_via)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            // The comment here used to read "Handle share error silently or show toast"
            // and the block was empty. Silent is what it stayed, so nobody ever learned
            // that sharing failed — the same shape of blindness that left the cause of a
            // Play review unknown for seven weeks. The user-visible behaviour is
            // unchanged (a failed chooser simply does not appear); what changes is that
            // we can now see it happen.
            crashlyticsHelper.recordException(e)
        }
    }

    fun openInExternalApp() {
        // Falls back to lastDownload so the error screen's offer actually works. The old
        // `if (state !is Viewing) return` would have made that button a no-op.
        val file = (_uiState.value as? PdfViewerUiState.Viewing)?.pdfFile
            ?: lastDownload
            ?: return

        try {
            val uri = FileProvider.getUriForFile(
                context,
                SharedFileCache.authority(context.packageName),
                file
            )

            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                // Was hardcoded to application/pdf. On the path that matters most here —
                // a Word or spreadsheet original — that declares a type the file is not,
                // so the system offers a PDF viewer that then fails to open it.
                setDataAndType(uri, lastMimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(openIntent)
        } catch (e: Exception) {
            // Previously: one catch, no reporting, and "No app found to open file" for
            // every cause. Three of the four things that land here are not that.
            // FileProvider.getUriForFile throws IllegalArgumentException when the file is
            // outside the roots in file_paths.xml — our configuration defect — and
            // telling the user to install an app sends them to the Play Store over a bug
            // of ours. Only ActivityNotFoundException means what the old message said.
            crashlyticsHelper.recordException(e)
            analyticsService.trackEvent(
                AnalyticsEvent.PdfViewerDownloadFailed(
                    errorType = "ExternalOpenFailed",
                    diagnosticTag = e::class.java.simpleName.ifEmpty { "Unknown" }
                )
            )

            val message = if (e is ActivityNotFoundException) {
                context.getString(R.string.error_no_app_found)
            } else {
                context.getString(R.string.error_open_external_failed)
            }

            _uiState.update { current ->
                when (current) {
                    // Keep the document on screen. This method is also reachable from the
                    // toolbar while a PDF is open, and replacing it with a full-screen
                    // error would cost the user their place in a document they were
                    // reading because a chooser failed to launch. They keep what works;
                    // the failure is reported rather than shown.
                    //
                    // The honest gap: that leaves no on-screen feedback in the Viewing
                    // case. A snackbar needs host-level scaffolding this screen does not
                    // have, so it is filed rather than bodged in here.
                    is PdfViewerUiState.Viewing -> current

                    // Already on the error screen: update the text, and keep the offer
                    // available. Clearing canOpenExternally would remove the only way
                    // forward and leave "Try again", which re-downloads into the same
                    // dead end.
                    is PdfViewerUiState.Error -> current.copy(
                        message = message,
                        canOpenExternally = true
                    )

                    else -> PdfViewerUiState.Error(message, canOpenExternally = true)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Deletes lastDownload, not the Viewing state's file.
        //
        // It used to read the file out of the Viewing state, which was complete while a
        // successful download could only end in Viewing. This change adds four terminal
        // states that follow a SUCCESSFUL download — WEB_PAGE, UNSUPPORTED, UNREADABLE
        // and a render failure — and in every one of them the file was already on disk
        // and would have been left there. That is a leak this change would have
        // introduced: documents accumulating in the shared cache, cleared only by the
        // hourly sweep at app start.
        lastDownload?.delete()
        lastDownload = null
    }
}
