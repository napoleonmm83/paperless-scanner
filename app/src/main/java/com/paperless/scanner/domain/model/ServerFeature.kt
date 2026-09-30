package com.paperless.scanner.domain.model

enum class FeatureStatus { AVAILABLE, UPDATE_REQUIRED, UNKNOWN, NOT_IMPLEMENTED }

/** Minimum versions are filled only when the app implementation and evidence exist. */
data class ServerFeature(
    val id: String,
    val implemented: Boolean,
    val minimumVersion: PaperlessServerVersion?
) {
    fun evaluate(serverVersion: PaperlessServerVersion?): FeatureStatus = when {
        !implemented -> FeatureStatus.NOT_IMPLEMENTED
        minimumVersion == null || serverVersion == null -> FeatureStatus.UNKNOWN
        serverVersion < minimumVersion -> FeatureStatus.UPDATE_REQUIRED
        else -> FeatureStatus.AVAILABLE
    }
}

object ServerFeatureCatalog {
    val features: List<ServerFeature> = listOf(
        "share_links", "saved_views", "bulk_edit", "similar_documents", "custom_fields_search",
        "search_assistance", "pdf_edit", "storage_paths", "file_versions"
    ).map {
        if (it == "share_links") ServerFeature(it, true, PaperlessServerVersion.parse("2.0.0"))
        else ServerFeature(it, implemented = false, minimumVersion = null)
    }

    fun upgradeRequired(serverVersion: PaperlessServerVersion?): List<ServerFeature> =
        features.filter { it.evaluate(serverVersion) == FeatureStatus.UPDATE_REQUIRED }
}
