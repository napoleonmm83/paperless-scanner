package com.paperless.scanner

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PaperlessApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        cleanupOldCacheFiles()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun cleanupOldCacheFiles() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheDir = cacheDir
                val oneHourAgo = System.currentTimeMillis() - CACHE_MAX_AGE_MS
                var deletedCount = 0
                var freedBytes = 0L

                cacheDir.listFiles()?.forEach { file ->
                    if (file.isFile &&
                        file.name.startsWith("document_") &&
                        file.lastModified() < oneHourAgo
                    ) {
                        freedBytes += file.length()
                        if (file.delete()) {
                            deletedCount++
                        }
                    }
                }

                if (deletedCount > 0) {
                    val freedMB = freedBytes / (1024.0 * 1024.0)
                    Log.d(TAG, "Cache cleanup: $deletedCount files deleted (${String.format("%.2f", freedMB)} MB)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cache cleanup failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "PaperlessApp"
        private const val CACHE_MAX_AGE_MS = 60 * 60 * 1000L // 1 hour
    }
}
