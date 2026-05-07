package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateCustomFieldRequest
import com.paperless.scanner.data.api.models.CustomField
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.util.withRetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for managing custom fields.
 * Uses in-memory cache only (no Room DAO) since custom fields are rarely edited.
 * Implements feature detection - gracefully handles servers without custom fields API.
 */
class CustomFieldRepository @Inject constructor(
    private val api: PaperlessApi,
    private val networkMonitor: NetworkMonitor
) {
    // In-memory cache only (no Room DAO - custom fields are rarely used)
    private val _customFields = MutableStateFlow<List<CustomField>>(emptyList())

    /**
     * BEST PRACTICE: Reactive Flow for automatic UI updates.
     * Observes custom fields and automatically notifies when data changes.
     */
    fun observeCustomFields(): Flow<List<CustomField>> = _customFields.asStateFlow()

    /**
     * Gets all custom fields with optional force refresh.
     * Implements feature detection - returns empty list if API not available.
     */
    suspend fun getCustomFields(forceRefresh: Boolean = false): Result<List<CustomField>> {
        return try {
            // Return cached data if available and not forcing refresh
            if (!forceRefresh && _customFields.value.isNotEmpty()) {
                return Result.success(_customFields.value)
            }

            // Network fetch (if online)
            if (networkMonitor.checkOnlineStatus()) {
                val response = withRetry { api.getCustomFields(page = 1, pageSize = 100) }
                _customFields.value = response.results
                Result.success(response.results)
            } else {
                // Offline, return cached data
                Result.success(_customFields.value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // If API not available (404), return empty list silently (feature detection)
            if (e is HttpException && e.code() == 404) {
                _customFields.value = emptyList()
                Result.success(emptyList())
            } else {
                Result.failure(PaperlessException.from(e))
            }
        }
    }

    /**
     * Creates a new custom field.
     * Updates cache immediately to trigger reactive Flow.
     */
    suspend fun createCustomField(name: String, dataType: String = "string"): Result<CustomField> {
        return try {
            // POST: non-idempotent — no withRetry, would risk duplicate custom field on 5xx.
            val response = api.createCustomField(
                CreateCustomFieldRequest(name = name, dataType = dataType)
            )

            // Add to cache to trigger reactive Flow update immediately
            _customFields.value = _customFields.value + response

            Result.success(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Deletes a custom field.
     * Removes from cache to trigger reactive Flow.
     */
    suspend fun deleteCustomField(id: Int): Result<Unit> {
        return try {
            try {
                withRetry { api.deleteCustomField(id) }
            } catch (e: HttpException) {
                // DELETE is idempotent: 404 = "already gone" — treat as success
                // and still evict from local cache so the UI converges. Same
                // pattern as TrashRepository.deleteDocument.
                if (e.code() != 404) throw e
            }
            _customFields.value = _customFields.value.filter { it.id != id }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Checks if custom fields API is available on the server.
     * Used for feature detection in UI (hide tab if not available).
     */
    suspend fun isCustomFieldsApiAvailable(): Boolean {
        return try {
            api.getCustomFields(page = 1, pageSize = 1)
            true
        } catch (e: HttpException) {
            e.code() != 404
        } catch (e: CancellationException) {
            // Never treat coroutine cancellation as "API unavailable".
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
