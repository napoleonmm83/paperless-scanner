package com.paperless.scanner.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.paperless.scanner.data.api.models.TasksResponse
import com.paperless.scanner.data.api.models.TasksResponseDeserializer

/**
 * Centralized Gson instance provider.
 *
 * Provides a single, shared Gson instance for the entire application.
 * This ensures consistent JSON serialization/deserialization and avoids
 * creating multiple Gson instances (which wastes memory).
 *
 * Usage:
 * - For Hilt-injectable classes: Use constructor injection via AppModule.provideGson()
 * - For extension functions, companion objects, Room TypeConverters: Use GsonProvider.instance
 */
object GsonProvider {
    /**
     * Shared Gson instance with default configuration.
     * Thread-safe and lazily initialized.
     *
     * [TasksResponseDeserializer] is the one custom adapter: `/api/tasks/` is a
     * bare array up to API v9 and a paginated object from v10 on, and Gson needs
     * help mapping both onto one type.
     */
    val instance: Gson by lazy {
        GsonBuilder()
            .registerTypeAdapter(TasksResponse::class.java, TasksResponseDeserializer())
            .create()
    }
}
