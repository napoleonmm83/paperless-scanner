package com.paperless.scanner.testing.fakes

import com.paperless.scanner.data.analytics.CrashlyticsHelperContract

/**
 * Typed fake for [CrashlyticsHelperContract] (#202/#321): records breadcrumbs and
 * non-fatal exceptions so worker tests can assert on real telemetry data.
 */
class FakeCrashlyticsHelper : CrashlyticsHelperContract {
    val actionBreadcrumbs = mutableListOf<Pair<String, String?>>()
    val stateBreadcrumbs = mutableListOf<Pair<String, String?>>()
    val recordedExceptions = mutableListOf<Throwable>()

    override fun logActionBreadcrumb(action: String, details: String?) {
        actionBreadcrumbs += action to details
    }

    override fun logStateBreadcrumb(state: String, details: String?) {
        stateBreadcrumbs += state to details
    }

    override fun recordException(throwable: Throwable) {
        recordedExceptions += throwable
    }
}
