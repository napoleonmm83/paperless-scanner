package com.paperless.scanner.data.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive view of the device-level conditions that gate the upload queue — the same
 * battery-not-low and storage-not-low constraints WorkManager enforces on the upload work
 * ([com.paperless.scanner.worker.UploadConstraintsProvider]). Lets the Sync Center explain
 * *why* an upload is paused instead of just showing it as pending.
 *
 * Both flows use the exact system broadcasts WorkManager's BatteryNotLowTracker /
 * StorageNotLowTracker respond to, so the reported state matches the actual constraint (rather
 * than a re-derived threshold). Each registers its receiver via callbackFlow/awaitClose, so the
 * listeners live only while the Sync Center observes them — no app-lifetime receivers.
 */
@Singleton
class DeviceConditionsMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * True while the system reports a low battery — between [Intent.ACTION_BATTERY_LOW] and
     * [Intent.ACTION_BATTERY_OKAY], the condition behind `setRequiresBatteryNotLow`. The initial
     * value is derived from the sticky battery-changed intent.
     */
    val isBatteryLow: Flow<Boolean> = callbackFlow {
        trySend(readInitialBatteryLow())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_LOW -> trySend(true)
                    Intent.ACTION_BATTERY_OKAY -> trySend(false)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        // RECEIVER_NOT_EXPORTED: these are protected system broadcasts (only the OS can send them),
        // so we never accept them from other apps — and Android 14+ (targetSdk 34+) requires an
        // explicit export flag or registration throws. Mirrors WorkManager's own constraint trackers.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.distinctUntilChanged()

    /**
     * True while the system reports low storage — between [Intent.ACTION_DEVICE_STORAGE_LOW] and
     * [Intent.ACTION_DEVICE_STORAGE_OK], the exact OS signal behind `setRequiresStorageNotLow`
     * (whose threshold is device/OEM-defined). The initial value comes from the sticky
     * storage-low broadcast, mirroring WorkManager's own tracker. These actions are deprecated
     * for manifest receivers but are still delivered to context-registered receivers — which is
     * what WorkManager itself relies on.
     */
    @Suppress("DEPRECATION")
    val isStorageLow: Flow<Boolean> = callbackFlow {
        trySend(context.registerReceiver(null, IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW)) != null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_DEVICE_STORAGE_LOW -> trySend(true)
                    Intent.ACTION_DEVICE_STORAGE_OK -> trySend(false)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
            addAction(Intent.ACTION_DEVICE_STORAGE_OK)
        }
        // RECEIVER_NOT_EXPORTED: these are protected system broadcasts (only the OS can send them),
        // so we never accept them from other apps — and Android 14+ (targetSdk 34+) requires an
        // explicit export flag or registration throws. Mirrors WorkManager's own constraint trackers.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.distinctUntilChanged()

    private fun readInitialBatteryLow(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        // Battery-less / unknown-status devices are never battery-constrained (mirrors
        // WorkManager's BatteryNotLowTracker), so report not-low regardless of the reported level.
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        if (status == BatteryManager.BATTERY_STATUS_UNKNOWN) return false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return false
        // Matches WorkManager's BatteryNotLowTracker initial state: low when battery <= 15%,
        // independent of charging (a charging device at <=15% is still constrained until the
        // system sends ACTION_BATTERY_OKAY, which the runtime flow above already tracks).
        // Exact integer comparison avoids the truncation of `level * 100 / scale` when
        // EXTRA_SCALE is not 100.
        return level * 100 <= scale * LOW_BATTERY_PERCENT
    }

    companion object {
        private const val LOW_BATTERY_PERCENT = 15
    }
}
