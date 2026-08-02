package com.paperless.scanner.ui.screens.pdfviewer

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsServiceContract
import com.paperless.scanner.data.analytics.CrashlyticsHelperContract
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = RuntimeEnvironment.getApplication()
        documentRepository = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)
        crashlyticsHelper = mockk(relaxed = true)
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

    private companion object {
        const val DOWNLOAD_DELAY_MS = 10L
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
    fun `unknown error surfaces the localized fallback, not the English literal`() = runTest(testDispatcher) {
        // message == null is exactly what makes UnknownError fall back to the
        // untranslated literal "Unknown error" in the old code path.
        stubDownloadFailure(PaperlessException.UnknownError(RuntimeException()))

        val state = viewModel().awaitErrorState()

        assertEquals(context.getString(R.string.error_unknown), state.message)
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

        verify {
            analyticsService.trackEvent(AnalyticsEvent.PdfViewerDownloadFailed("ServerUnreachable"))
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
}
