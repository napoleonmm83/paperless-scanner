package com.paperless.scanner.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: SyncManager
) : CoroutineWorker(context, params) {

    private val TAG = "SyncWorker"

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting periodic sync work...")

        return try {
            val result = syncManager.performFullSync()

            if (result.isSuccess) {
                Log.d(TAG, "Periodic sync completed successfully")
                Result.success()
            } else {
                Log.e(TAG, "Periodic sync failed: ${result.exceptionOrNull()?.message}")

                // Retry if we haven't exceeded max attempts
                if (runAttemptCount < MAX_RETRIES) {
                    Log.d(TAG, "Retrying sync (attempt ${runAttemptCount + 1}/$MAX_RETRIES)")
                    Result.retry()
                } else {
                    Log.e(TAG, "Max retry attempts reached, marking as failure")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Periodic sync failed with exception", e)

            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val WORK_NAME = "paperless_sync"
        const val MAX_RETRIES = 3

        /**
         * BEST PRACTICE: Background sync following Android & modern app conventions.
         *
         * Sync Strategy (like Gmail, Google Drive, Dropbox):
         * - Interval: 30 minutes (battery-efficient, catches most web changes)
         * - Flex: 10 minutes (Android batches work for efficiency)
         * - Constraints: Network + Battery not low
         *
         * This ensures:
         * - Web/multi-device changes are synced within 20-40 minutes
         * - Battery-efficient batching by Android OS
         * - No sync during low battery conditions
         * - Complements Phase 1 event-based refresh (ON_RESUME, Pull-to-Refresh)
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)  // BEST PRACTICE: Don't drain low battery
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                repeatInterval = 30,  // BEST PRACTICE: 30 min like Gmail/Drive
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
                flexTimeInterval = 10,  // BEST PRACTICE: Flex window for Android batching
                flexTimeIntervalUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing work if already scheduled
                request
            )

            Log.d("SyncWorker", "Periodic sync work scheduled (30 min interval with 10 min flex)")
        }

        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d("SyncWorker", "Periodic sync work cancelled")
        }
    }
}
