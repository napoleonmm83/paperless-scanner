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
 * - deserializeCachedTagIdsOrNull: returns null for null/blank/unparseable input —
 *   the old tag set is UNKNOWN, as opposed to a genuinely empty set ("[]" parses to
 *   emptyList()). Callers computing tag-count deltas must skip the delta on null (#334).
 * - deserializeCachedTagIds: same parse, but collapses the unknown case to emptyList()
 *   for callers that don't need the distinction.
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

    fun deserializeCachedTagIds(cachedJson: String?): List<Int> =
        deserializeCachedTagIdsOrNull(cachedJson) ?: emptyList()

    fun deserializeCachedTagIdsOrNull(cachedJson: String?): List<Int>? {
        if (cachedJson.isNullOrBlank()) return null
        return try {
            val listType = object : TypeToken<List<Int>>() {}.type
            gson.fromJson<List<Int>>(cachedJson, listType)
        } catch (e: JsonSyntaxException) {
            null
        }
    }
}
