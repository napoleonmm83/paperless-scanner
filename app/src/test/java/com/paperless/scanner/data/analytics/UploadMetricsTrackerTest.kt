package com.paperless.scanner.data.analytics

import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

/**
 * Verifies [UploadMetricsTracker] delegates to [CrashlyticsHelper] with the
 * exact breadcrumb labels/details that were previously inlined in
 * DocumentRepository (#60) — guarding against accidental label drift.
 */
class UploadMetricsTrackerTest {

    private lateinit var crashlyticsHelper: CrashlyticsHelper
    private lateinit var tracker: UploadMetricsTracker

    @Before
    fun setup() {
        crashlyticsHelper = mockk(relaxed = true)
        tracker = UploadMetricsTracker(crashlyticsHelper)
    }

    @Test
    fun `logSinglePageUploadStart delegates with UPLOAD_START label`() {
        tracker.logSinglePageUploadStart()
        verify { crashlyticsHelper.logActionBreadcrumb("UPLOAD_START", "single-page") }
    }

    @Test
    fun `logMultiPageUploadStart delegates with page-count detail`() {
        tracker.logMultiPageUploadStart(3)
        verify { crashlyticsHelper.logActionBreadcrumb("UPLOAD_START", "multi-page, 3 pages") }
    }

    @Test
    fun `logSinglePageUploadSuccess delegates with taskId detail`() {
        tracker.logSinglePageUploadSuccess("task-1")
        verify { crashlyticsHelper.logActionBreadcrumb("UPLOAD_SUCCESS", "taskId=task-1") }
    }

    @Test
    fun `logMultiPageUploadSuccess delegates with multi-page taskId detail`() {
        tracker.logMultiPageUploadSuccess("task-2")
        verify { crashlyticsHelper.logActionBreadcrumb("UPLOAD_SUCCESS", "multi-page, taskId=task-2") }
    }

    @Test
    fun `logUploadError delegates with UPLOAD_ERROR state label`() {
        tracker.logUploadError("HTTP 500")
        verify { crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "HTTP 500") }
    }
}
