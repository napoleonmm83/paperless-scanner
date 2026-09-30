package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.ServerCapabilityStore
import com.paperless.scanner.domain.model.FeatureStatus
import com.paperless.scanner.domain.model.ServerFeatureCatalog
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class ServerCapabilityRepository @Inject constructor(
    private val api: PaperlessApi,
    private val store: ServerCapabilityStore
) {
    val state = store.state

    suspend fun refresh(): Result<Unit> {
        if (state.value.serverVersion != null) return Result.success(Unit)
        return try {
            api.getDocuments(pageSize = 1)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** A call-site guard; role/configuration failures are still handled by the endpoint. */
    fun requireFeature(featureId: String) {
        val feature = ServerFeatureCatalog.features.find { it.id == featureId }
        val status = feature?.evaluate(state.value.serverVersion)
        if (status != FeatureStatus.AVAILABLE) {
            throw UnsupportedOperationException("Server feature unavailable: $featureId ($status)")
        }
    }
}
