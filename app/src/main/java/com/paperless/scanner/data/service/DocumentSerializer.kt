package com.paperless.scanner.data.service

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralizes Gson-based JSON shape transforms used by DocumentRepository.
 * Extracted in issue #51 Phase 1.3.
 *
 * Contract:
 * - serializeCustomFieldsForUpload: returns null for null/empty input. Otherwise
 *   serializes a list of {field, value} maps as JSON wrapped in an
 *   application/json RequestBody.
 * - deserializeCachedTagIds: returns emptyList() for null or unparseable input;
 *   otherwise parses a Gson List<Int>.
 */
@Singleton
class DocumentSerializer @Inject constructor(
    private val gson: Gson
) {
    fun serializeCustomFieldsForUpload(customFields: Map<Int, Any>?): RequestBody? {
        if (customFields.isNullOrEmpty()) return null
        val customFieldsList = customFields.map { (fieldId, value) ->
            mapOf("field" to fieldId, "value" to value)
        }
        return gson.toJson(customFieldsList).toRequestBody("application/json".toMediaTypeOrNull())
    }

    fun deserializeCachedTagIds(cachedJson: String?): List<Int> {
        if (cachedJson.isNullOrBlank()) return emptyList()
        return try {
            val listType = object : TypeToken<List<Int>>() {}.type
            gson.fromJson<List<Int>>(cachedJson, listType) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            emptyList()
        }
    }
}
