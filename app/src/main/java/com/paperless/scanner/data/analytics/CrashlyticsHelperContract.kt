package com.paperless.scanner.data.analytics

/**
 * Test-double seam for [CrashlyticsHelper] (#321): the breadcrumb/non-fatal surface
 * consumed by the workers and AuthRepository. Default parameter values live HERE
 * (Kotlin forbids them on overrides) — callers that rely on them must hold this
 * contract type, not the concrete class.
 */
interface CrashlyticsHelperContract {
    fun logActionBreadcrumb(action: String, details: String? = null)
    fun logStateBreadcrumb(state: String, details: String? = null)
    fun recordException(throwable: Throwable)
}
