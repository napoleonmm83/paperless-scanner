package com.paperless.scanner.data.analytics

import javax.inject.Inject

/**
 * Centralizes the upload-flow Crashlytics breadcrumbs that were previously
 * scattered as hardcoded label/detail strings across [com.paperless.scanner.data.repository.DocumentRepository]'s
 * upload paths (#60).
 *
 * Owns the breadcrumb label constants and the start/success message formatting,
 * and delegates to [CrashlyticsHelper] so the analytics-enabled gating stays in
 * one place. Error details remain caller-supplied (they carry contextual
 * exception messages); only the `UPLOAD_ERROR` label is centralized here.
 */
class UploadMetricsTracker @Inject constructor(
    private val crashlyticsHelper: CrashlyticsHelper,
) {
    fun logSinglePageUploadStart() =
        crashlyticsHelper.logActionBreadcrumb(ACTION_UPLOAD_START, "single-page")

    fun logMultiPageUploadStart(pageCount: Int) =
        crashlyticsHelper.logActionBreadcrumb(ACTION_UPLOAD_START, "multi-page, $pageCount pages")

    fun logSinglePageUploadSuccess(taskId: String) =
        crashlyticsHelper.logActionBreadcrumb(ACTION_UPLOAD_SUCCESS, "taskId=$taskId")

    fun logMultiPageUploadSuccess(taskId: String) =
        crashlyticsHelper.logActionBreadcrumb(ACTION_UPLOAD_SUCCESS, "multi-page, taskId=$taskId")

    fun logUploadError(detail: String) =
        crashlyticsHelper.logStateBreadcrumb(STATE_UPLOAD_ERROR, detail)

    companion object {
        private const val ACTION_UPLOAD_START = "UPLOAD_START"
        private const val ACTION_UPLOAD_SUCCESS = "UPLOAD_SUCCESS"
        private const val STATE_UPLOAD_ERROR = "UPLOAD_ERROR"
    }
}
