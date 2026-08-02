package com.paperless.scanner.data.api.models

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type

/**
 * Reads [TasksResponse] from either Paperless-ngx task-list format.
 *
 * `/api/tasks/` changed shape in API v10:
 *
 * ```
 * v9 and older:  [ {...}, {...} ]
 * v10 and newer: { "count": 2, "next": null, "previous": null, "results": [ {...}, {...} ] }
 * ```
 *
 * Gson cannot map both onto one type on its own — handed the v10 object it
 * would fail a bare `List<PaperlessTask>` with "Expected BEGIN_ARRAY but was
 * BEGIN_OBJECT", which is precisely how document-processing status would have
 * broken against a 3.0 server.
 *
 * Registered by class (not by generic type) in
 * [com.paperless.scanner.di.GsonProvider]: Retrofit resolves a suspend function's
 * return type through wildcard bounds, so a `TypeToken<List<PaperlessTask>>`
 * registration would not reliably match and could silently never apply.
 *
 * @see com.paperless.scanner.data.api.ApiVersionInterceptor which pins v9, making
 *   the v10 branch here the forward-compatibility net rather than the hot path.
 */
class TasksResponseDeserializer : JsonDeserializer<TasksResponse> {

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): TasksResponse {
        // v9 and older: bare array, no pagination envelope.
        if (json.isJsonArray) {
            val tasks = json.asJsonArray.toTasks(context)
            return TasksResponse(count = tasks.size, results = tasks)
        }

        // v10 and newer: DRF page object.
        if (!json.isJsonObject) {
            throw unrecognised(json)
        }
        val obj = json.asJsonObject
        val results = obj.get("results")
        val tasks = when {
            // `getAsJsonArray` is an unchecked cast, so a JSON `null` or a
            // non-array member would throw ClassCastException past any `?:`.
            results != null && results.isJsonArray -> results.asJsonArray.toTasks(context)
            // A page envelope that merely omits `results` still identifies
            // itself via `count`; treat that as a genuinely empty page.
            (results == null || results.isJsonNull) && obj.has("count") -> emptyList()
            // Anything else is not a task page. Failing loudly puts this on the
            // existing error path (JsonParseException -> PaperlessException
            // .ParseError -> Result.failure) instead of reporting "no tasks",
            // which would leave the processing list silently empty and stop the
            // poll — the exact failure mode PR #403 was filed to remove.
            else -> throw unrecognised(json)
        }
        return TasksResponse(
            count = obj.optInt("count", tasks.size),
            next = obj.optString("next"),
            previous = obj.optString("previous"),
            results = tasks
        )
    }

    private fun com.google.gson.JsonArray.toTasks(
        context: JsonDeserializationContext
    ): List<PaperlessTask> = mapNotNull { element ->
        context.deserialize<PaperlessTask>(element, PaperlessTask::class.java)
    }

    private fun unrecognised(json: JsonElement): JsonParseException = JsonParseException(
        "Unrecognised /api/tasks/ payload: expected the API v9 array or a v10 page " +
            "object with `results`, got ${json.javaClass.simpleName}"
    )

    // `asString`/`asInt` throw if the member is an object or array, so both
    // accessors insist on a primitive rather than trusting the shape.
    private fun com.google.gson.JsonObject.optString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun com.google.gson.JsonObject.optInt(name: String, fallback: Int): Int =
        get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: fallback
}
