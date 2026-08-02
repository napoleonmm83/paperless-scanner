package com.paperless.scanner.data.api.models

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
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
            return TasksResponse()
        }
        val obj = json.asJsonObject
        val tasks = obj.getAsJsonArray("results")?.toTasks(context) ?: emptyList()
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

    private fun com.google.gson.JsonObject.optString(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString

    private fun com.google.gson.JsonObject.optInt(name: String, fallback: Int): Int =
        get(name)?.takeIf { !it.isJsonNull }?.asInt ?: fallback
}
