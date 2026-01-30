package com.paperless.scanner.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder

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
     */
    val instance: Gson by lazy {
        GsonBuilder()
            .create()
    }
}
