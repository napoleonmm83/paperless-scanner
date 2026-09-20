package com.paperless.scanner.ui.screens.pdfviewer

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsServiceContract
import com.paperless.scanner.data.analytics.CrashlyticsHelperContract
import com.paperless.scanner.data.analytics.DiagnosticReport
import com.paperless.scanner.data.analytics.DiagnosticsReportService
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.domain.error.ServerOfflineReason
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [PdfViewerViewModel] download-failure handling.
 *
 * Regression cover for the Play-Store report (2026-07-27, Galaxy S25 Ultra /
 * Android 16 / v1.5.235): opening a document in the in-app viewer showed a bare
 * "Unbekannter Fehler". Two defects fed that report:
 *
 *  1. The error branch rendered the RAW [Throwable.message] instead of resolving
 *     [PaperlessException.messageResId] via `getLocalizedMessage`. Users saw
 *     untranslated internals — literally `"DNS_FAILURE"` (the enum name) for an
 *     unreachable server, or the English literal `"Unknown error"`.
 *  2. The branch had no telemetry at all, so the real cause never reached
 *     Crashlytics and we stayed blind to it.
 *
 * Needs Robolectric for real string-resource resolution (the whole point is that
 * a @StringRes id gets resolved), pinned to a fixed SDK so the run does not drift
 * with targetSdk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class PdfViewerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var documentRepository: DocumentRepository
    private lateinit var analyticsService: AnalyticsServiceContract
    private lateinit var crashlyticsHelper: CrashlyticsHelperContract
    private lateinit var diagnosticsReportService: DiagnosticsReportService

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = RuntimeEnvironment.getApplication()
        documentRepository = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)
        crashlyticsHelper = mockk(relaxed = true)
        diagnosticsReportService = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PdfViewerViewModel(
        savedStateHandle = SavedStateHandle(mapOf("documentId" to "42")),
        documentRepository = documentRepository,
        analyticsService = analyticsService,
        crashlyticsHelper = crashlyticsHelper,
        diagnosticsReportService = diagnosticsReportService,
        context = context
    )

    /**
     * Fails the download after a virtual-time delay, modelling a real (suspending)
     * network call.
     *
     * The delay is not strictly required: an instantly-returning stub was measured to
     * still surface the intermediate Downloading state, because [PdfViewerViewModel.uiState]
     * only conflates when the collector cannot keep up, which it can here. But then
     * the ordering rests on dispatcher scheduling details rather than on anything this
     * test states, so an unrelated scheduling change could quietly turn the transition
     * assertion into a no-op. The delay makes it deterministic under virtual time.
     */
    private fun stubDownloadFailure(error: Throwable) {
        coEvery { documentRepository.downloadDocument(any(), any()) } coAnswers {
            delay(DOWNLOAD_DELAY_MS)
            Result.failure(error)
        }
    }

    /** Drives Idle -> Downloading -> Error and hands back the terminal error state. */
    private suspend fun PdfViewerViewModel.awaitErrorState(): PdfViewerUiState.Error {
        lateinit var error: PdfViewerUiState.Error
        uiState.test {
            assertEquals(PdfViewerUiState.Idle, awaitItem())
            assertTrue(
                "Downloading state was skipped - the progress indicator never shows",
                awaitItem() is PdfViewerUiState.Downloading
            )
            error = awaitItem() as PdfViewerUiState.Error
            cancelAndIgnoreRemainingEvents()
        }
        return error
    }

    /**
     * Succeeds the download with a file holding exactly [bytes].
     *
     * The name always ends in `.pdf` because that is what DocumentRepository writes,
     * whatever actually arrived. That detail is the point of several tests below.
     */
    private fun stubDownloadSuccess(bytes: ByteArray): File {
        val file = File.createTempFile("document_42_", ".pdf").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
        coEvery { documentRepository.downloadDocument(any(), any()) } coAnswers {
            delay(DOWNLOAD_DELAY_MS)
            Result.success(file)
        }
        return file
    }

    /** Drives Idle -> Downloading -> <terminal> and hands back the terminal state. */
    private suspend fun PdfViewerViewModel.awaitTerminalState(): PdfViewerUiState {
        lateinit var terminal: PdfViewerUiState
        uiState.test {
            assertEquals(PdfViewerUiState.Idle, awaitItem())
            assertTrue(awaitItem() is PdfViewerUiState.Downloading)
            terminal = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return terminal
    }

    private companion object {
        const val DOWNLOAD_DELAY_MS = 10L

        val PDF_BYTES = "%PDF-1.7\n1 0 obj".toByteArray()
        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val HTML_LOGIN_PAGE = "<!DOCTYPE html>\n<html><body>Sign in</body></html>".toByteArray()
        val DOCX_BYTES = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00)
    }

    @Test
    fun `unreachable server surfaces the localized message, not the enum name`() = runTest(testDispatcher) {
        stubDownloadFailure(PaperlessException.ServerUnreachable(ServerOfflineReason.DNS_FAILURE))

        val state = viewModel().awaitErrorState()

        assertEquals(context.getString(R.string.error_dns_failure), state.message)
        // The raw message of ServerUnreachable is the enum name - it must never reach the UI.
        assertTrue("Raw enum name leaked to the user", !state.message.contains("DNS_FAILURE"))
    }

    @Test
    fun `an unclassified error names what could not be classified`() = runTest(testDispatcher) {
        // This test used to assert `getString(R.string.error_unknown)` and passed, while
        // the user-visible defect it was written for survived untouched. That is worth
        // keeping in view: PR #403's real fix was replacing the raw Throwable.message
        // with a resource, and asserting the resource proved exactly that much. What it
        // could not see is that the resource says "Unknown error" — the same sentence
        // the release notes claimed to have removed, now correctly translated. A user
        // reported it again seven weeks later, with a screenshot of the German form.
        //
        // So the assertion moved from "which resource" to "does the message identify
        // anything": the exception class has to appear, because that string is the only
        // thing a support screenshot can carry out of an unclassified failure.
        stubDownloadFailure(PaperlessException.UnknownError(RuntimeException()))

        val state = viewModel().awaitErrorState()

        assertEquals(
            context.getString(R.string.error_unknown_with_detail, "RuntimeException"),
            state.message
        )
        assertTrue(
            "the message identifies nothing a support request could act on",
            state.message.contains("RuntimeException")
        )
    }

    @Test
    fun `download failure is recorded to Crashlytics`() = runTest(testDispatcher) {
        val error = PaperlessException.ServerUnreachable(ServerOfflineReason.TIMEOUT)
        stubDownloadFailure(error)

        viewModel()
        advanceUntilIdle()

        verify { crashlyticsHelper.recordException(error) }
    }

    @Test
    fun `download failure is tracked with its error type`() = runTest(testDispatcher) {
        stubDownloadFailure(PaperlessException.ServerUnreachable(ServerOfflineReason.TIMEOUT))

        viewModel()
        advanceUntilIdle()

        // errorType stays the subtype; the tag now names WHICH of the five unreachable
        // reasons it was. This assertion used to read PdfViewerDownloadFailed(
        // "ServerUnreachable") and passed while DNS failure, refused connection, timeout
        // and TLS trust all reported that single string — a count of a set nobody could
        // split. It went red on exactly that change, which is the point of it.
        verify {
            analyticsService.trackEvent(
                AnalyticsEvent.PdfViewerDownloadFailed(
                    errorType = "ServerUnreachable",
                    diagnosticTag = "TIMEOUT"
                )
            )
        }
    }

    @Test
    fun `an unclassified failure is tracked with something that tells cases apart`() =
        runTest(testDispatcher) {
            // The denominator existed and the event existed, and between them they still
            // could not answer "why". Every unclassifiable failure reported the single
            // value "UnknownError", so the dashboard counted a set it could not split.
            // The tag is what makes the count actionable.
            stubDownloadFailure(PaperlessException.fromHttpCode(304))

            viewModel()
            advanceUntilIdle()

            verify {
                analyticsService.trackEvent(
                    AnalyticsEvent.PdfViewerDownloadFailed(
                        errorType = "ContentError",
                        diagnosticTag = "HTTP 304"
                    )
                )
            }
        }

    @Test
    fun `opening the viewer is tracked so failures have a denominator`() = runTest(testDispatcher) {
        stubDownloadFailure(PaperlessException.UnknownError(RuntimeException()))

        viewModel()
        advanceUntilIdle()

        verify { analyticsService.trackEvent(AnalyticsEvent.PdfViewerOpened) }
    }

    @Test
    fun `non-Paperless throwable falls back to the generic download error string`() = runTest(testDispatcher) {
        stubDownloadFailure(IllegalStateException("boom"))

        val state = viewModel().awaitErrorState()

        assertEquals(context.getString(R.string.error_download), state.message)
    }

    // --------------------------------------------------- when the report is offered

    @Test
    fun `an unclassified failure offers the report`() = runTest(testDispatcher) {
        stubDownloadFailure(PaperlessException.UnknownError(RuntimeException()))

        val state = viewModel().awaitErrorState()

        assertTrue("the one case we cannot diagnose did not offer a report", state.canSendReport)
    }

    @Test
    fun `a classified failure does NOT offer the report`() = runTest(testDispatcher) {
        // The counterpart, and the more important half. A report about a DNS failure
        // tells us nothing the message does not already say — and a button that appears
        // on every error is one the user has learned to ignore by the time it matters.
        stubDownloadFailure(PaperlessException.ServerUnreachable(ServerOfflineReason.DNS_FAILURE))

        val state = viewModel().awaitErrorState()

        assertTrue("the report was offered for a fully classified error", !state.canSendReport)
    }

    @Test
    fun `the report is filled before the button can be pressed`() = runTest(testDispatcher) {
        // Without this call lastReport stays null, the report is empty, and the feature
        // is dead on exactly the path it was built for. The button being visible is not
        // the same as the button having something to send.
        stubDownloadFailure(PaperlessException.UnknownError(RuntimeException()))

        viewModel()
        advanceUntilIdle()

        verify {
            diagnosticsReportService.logFailure(
                authType = DiagnosticReport.Source.DOCUMENT_DOWNLOAD,
                serverUrl = null,
                errorType = any(),
                errorMessage = any()
            )
        }
    }

    @Test
    fun `the offer is counted so a low send rate can be read`() = runTest(testDispatcher) {
        // #403 shipped an event that existed and was never fired, which left its failure
        // counterpart uninterpretable for seven weeks. Without this denominator, "few
        // reports arrive" cannot be told from "nobody finds the button".
        stubDownloadFailure(PaperlessException.UnknownError(RuntimeException()))

        viewModel()
        advanceUntilIdle()

        verify { analyticsService.trackEvent(AnalyticsEvent.DiagnosticReportOffered) }
    }

    // ------------------------------------------------- what actually came down the wire

    @Test
    fun `a login page served as a document is named, not rendered`() = runTest(testDispatcher) {
        // The regression this whole section exists for. DocumentRepository names every
        // download "document_<id>_<ts>.pdf" no matter what arrived, and the old check
        // read `file.extension == "pdf" || isPdfFile(file)` — whose first term was
        // therefore ALWAYS true. The magic-byte check behind the `||` never executed.
        //
        // So a reverse proxy answering with its own HTML login page counted as a valid
        // PDF, went to PdfRenderer, threw, and left the screen on its progress indicator
        // with no message and no report. That is the one failure shape a user cannot
        // even describe, and it is indistinguishable from "the app hangs".
        stubDownloadSuccess(HTML_LOGIN_PAGE)

        val state = viewModel().awaitTerminalState()

        assertTrue("HTML was accepted as a document", state is PdfViewerUiState.Error)
        assertEquals(
            context.getString(R.string.error_server_sent_webpage),
            (state as PdfViewerUiState.Error).message
        )
    }

    @Test
    fun `a real PDF still opens in the viewer`() = runTest(testDispatcher) {
        // The counterpart. Without it, the test above would pass just as well if the
        // content check rejected everything.
        stubDownloadSuccess(PDF_BYTES)

        val state = viewModel().awaitTerminalState()

        assertTrue(state is PdfViewerUiState.Viewing)
        assertTrue("a PDF was routed to the image path", (state as PdfViewerUiState.Viewing).isPdf)
    }

    @Test
    fun `an image document takes the image path despite its pdf file name`() =
        runTest(testDispatcher) {
            stubDownloadSuccess(PNG_BYTES)

            val state = viewModel().awaitTerminalState()

            assertTrue(state is PdfViewerUiState.Viewing)
            assertTrue(
                "a PNG was handed to PdfRenderer",
                !(state as PdfViewerUiState.Viewing).isPdf
            )
        }

    @Test
    fun `an office document offers the system viewer instead of a dead end`() =
        runTest(testDispatcher) {
            // Not a defect anywhere: /download/ serves the ORIGINAL file, so a DOCX is a
            // perfectly normal answer. "Try again" would repeat it forever.
            stubDownloadSuccess(DOCX_BYTES)

            val state = viewModel().awaitTerminalState() as PdfViewerUiState.Error

            assertEquals(
                context.getString(R.string.error_unsupported_document_type),
                state.message
            )
            assertTrue("no way forward was offered", state.canOpenExternally)
        }

    @Test
    fun `an empty download is reported rather than shown as a blank page`() =
        runTest(testDispatcher) {
            stubDownloadSuccess(ByteArray(0))
            val vm = viewModel()

            val state = vm.awaitTerminalState() as PdfViewerUiState.Error

            assertEquals(context.getString(R.string.error_document_unreadable), state.message)
            // The message alone cannot tell the read <= 0 guard from the catch-all: drop
            // the guard and copyOf(-1) throws NegativeArraySizeException, lands in the
            // catch, and produces exactly this text. The signature is what separates them.
            advanceUntilIdle()
            verify {
                analyticsService.trackEvent(
                    AnalyticsEvent.PdfViewerDownloadFailed(
                        errorType = "UnusableContent",
                        diagnosticTag = "UNREADABLE/empty"
                    )
                )
            }
        }

    @Test
    fun `a PDF preceded by a byte-order mark still opens`() = runTest(testDispatcher) {
        // Regression guard. pdfium scans the first kilobyte for the %PDF marker, and the
        // old code reached PdfRenderer for every download because the file name always
        // ended in .pdf — so these rendered fine. A content check anchored at offset 0
        // would reject them and tell the user their valid PDF is an unsupported file
        // type: a stricter check that is simply wrong.
        stubDownloadSuccess(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + PDF_BYTES)

        val state = viewModel().awaitTerminalState()

        assertTrue("a BOM-prefixed PDF was rejected", state is PdfViewerUiState.Viewing)
        assertTrue((state as PdfViewerUiState.Viewing).isPdf)
    }

    @Test
    fun `a real bitmap is still recognised`() = runTest(testDispatcher) {
        // The POSITIVE control for the size check below. Without it, a wrong offset or
        // the wrong endianness in littleEndianUInt — or the branch simply returning
        // false — would leave the negative test green and BMP silently unsupported.
        // Header: "BM", little-endian total size, then padding to a plausible length.
        val total = 40
        val header = byteArrayOf(0x42, 0x4D) + byteArrayOf(
            (total and 0xFF).toByte(),
            ((total shr 8) and 0xFF).toByte(),
            ((total shr 16) and 0xFF).toByte(),
            ((total shr 24) and 0xFF).toByte()
        )
        stubDownloadSuccess(header + ByteArray(total - header.size))

        val state = viewModel().awaitTerminalState()

        assertTrue("a valid BMP was rejected", state is PdfViewerUiState.Viewing)
        assertTrue(!(state as PdfViewerUiState.Viewing).isPdf)
    }

    @Test
    fun `a text document beginning BM is not mistaken for a bitmap`() = runTest(testDispatcher) {
        // BMP's magic is the two printable bytes "BM", so any text starting with them
        // matched. The file would have gone to Coil, failed to decode, and been reported
        // as damaged. The declared size has to match the real one as well.
        stubDownloadSuccess("BMW invoice 2026, total 1.234,00".toByteArray())

        val state = viewModel().awaitTerminalState()

        assertTrue(state is PdfViewerUiState.Error)
    }

    @Test
    fun `the format signature never carries document text`() = runTest(testDispatcher) {
        // Paperless serves the ORIGINAL when it holds no archive version, so for a text
        // or CSV document the leading bytes are the user's own content — and this value
        // goes to Firebase as an event dimension and to Crashlytics as a breadcrumb.
        // Anything that reads as text has to collapse to one constant.
        stubDownloadSuccess("Sehr geehrte Frau Muster, anbei die Rechnung".toByteArray())

        viewModel()
        advanceUntilIdle()

        verify {
            analyticsService.trackEvent(
                AnalyticsEvent.PdfViewerDownloadFailed(
                    errorType = "UnusableContent",
                    diagnosticTag = "UNSUPPORTED/text"
                )
            )
        }
    }

    @Test
    fun `unusable content is reported with the format signature`() = runTest(testDispatcher) {
        // "UNSUPPORTED" on its own counts a set without splitting it — the same defect
        // as the bare "Unknown error". The signature is what tells a DOCX (504b0304)
        // from anything else, and it is a format constant, not user data.
        stubDownloadSuccess(DOCX_BYTES)

        viewModel()
        advanceUntilIdle()

        verify {
            analyticsService.trackEvent(
                AnalyticsEvent.PdfViewerDownloadFailed(
                    errorType = "UnusableContent",
                    diagnosticTag = "UNSUPPORTED/504b0304"
                )
            )
        }
    }

    @Test
    fun `the external-app offer on the error screen is not a dead button`() =
        runTest(testDispatcher) {
            // openInExternalApp() began with `if (state !is Viewing) return`, which was
            // correct while the toolbar was its only caller. The error screen now offers
            // it too — for a file that arrived intact and only this app cannot render —
            // and without the fallback to the last download the button would have done
            // absolutely nothing. A dead control is worse than no control: the user
            // concludes the app is broken rather than that the file is unsupported.
            stubDownloadSuccess(DOCX_BYTES)
            val vm = viewModel()
            advanceUntilIdle()
            val before = vm.uiState.value as PdfViewerUiState.Error
            assertTrue(before.canOpenExternally)

            vm.openInExternalApp()
            advanceUntilIdle()

            // What this proves and what it does not, stated precisely because an earlier
            // version of this comment got it wrong: the class runs with
            // @Config(manifest = Config.NONE), so no FileProvider is registered at all
            // and file_paths.xml is never consulted. getUriForFile therefore throws
            // "Couldn't find meta-data for provider" — deterministically, which is the
            // reliable direction under Robolectric (feedback_robolectric_fileprovider_test).
            //
            // So this pins exactly one thing: the early return is gone, because the catch
            // is reached at all. It does NOT prove the button works on a device. And the
            // message must be the generic one, not "no app found" — that text is only
            // true for ActivityNotFoundException, and asserting it here would nail down
            // the very defect the catch was split to remove.
            val after = vm.uiState.value as PdfViewerUiState.Error
            assertEquals(context.getString(R.string.error_open_external_failed), after.message)
            assertTrue("the way forward was removed on failure", after.canOpenExternally)
        }

    @Test
    fun `a failed external open does not destroy the open document`() = runTest(testDispatcher) {
        // openInExternalApp is also reachable from the toolbar while a PDF is on screen.
        // Replacing that with a full-screen error would cost the user their place in a
        // document they were reading because a chooser failed to launch — losing what
        // works to report what does not.
        stubDownloadSuccess(PDF_BYTES)
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is PdfViewerUiState.Viewing)

        vm.openInExternalApp()
        advanceUntilIdle()

        assertTrue(
            "the document was replaced by an error screen",
            vm.uiState.value is PdfViewerUiState.Viewing
        )
        verify { crashlyticsHelper.recordException(any()) }
    }

    @Test
    fun `a renderer failure ends the spinner and is reported`() = runTest(testDispatcher) {
        // PdfRenderer runs in the Composable, so the screen calls this back. Before it
        // existed the exception went to printStackTrace() and the progress indicator
        // spun for as long as the user was willing to wait.
        stubDownloadSuccess(PDF_BYTES)
        val vm = viewModel()
        advanceUntilIdle()

        val cause = IllegalArgumentException("file not in PDF format")
        vm.onRenderFailure(cause)
        advanceUntilIdle()

        val state = vm.uiState.value as PdfViewerUiState.Error
        assertEquals(context.getString(R.string.error_document_unreadable), state.message)
        assertTrue(state.canOpenExternally)
        verify { crashlyticsHelper.recordException(cause) }
        verify {
            analyticsService.trackEvent(
                AnalyticsEvent.PdfViewerDownloadFailed(
                    errorType = "RenderFailure",
                    diagnosticTag = "IllegalArgumentException"
                )
            )
        }
    }
}
