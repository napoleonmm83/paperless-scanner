package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateCustomFieldRequest
import com.paperless.scanner.data.api.models.CustomField
import com.paperless.scanner.data.network.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import javax.inject.Inject

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
                val response = api.getCustomFields(page = 1, pageSize = 100)
                _customFields.value = response.results
                Result.success(response.results)
            } else {
                // Offline, return cached data
                Result.success(_customFields.value)
            }
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
            val response = api.createCustomField(
                CreateCustomFieldRequest(name = name, dataType = dataType)
            )

            // Add to cache to trigger reactive Flow update immediately
            _customFields.value = _customFields.value + response

            Result.success(response)
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
            api.deleteCustomField(id)

            // Remove from cache to trigger reactive Flow update immediately
            _customFields.value = _customFields.value.filter { it.id != id }

            Result.success(Unit)
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
        } catch (e: Exception) {
            false
        }
    }
}
