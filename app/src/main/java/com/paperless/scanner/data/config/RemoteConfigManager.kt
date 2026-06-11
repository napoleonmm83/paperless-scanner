package com.paperless.scanner.data.config

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.paperless.scanner.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot of the launch-promo flags from Firebase Remote Config. */
data class LaunchPromoConfig(
    val enabled: Boolean,
    val endEpochMs: Long
)

/**
 * Thin wrapper around Firebase Remote Config for the launch promo.
 *
 * Fail-closed by design: until a fetch succeeds (or previously activated values
 * exist), the published config keeps the promo disabled. Listener-based — no
 * coroutine scope to manage or tear down.
 */
@Singleton
class RemoteConfigManager @Inject constructor(
    private val remoteConfig: FirebaseRemoteConfig
) {
    companion object {
        // <=11 chars so AppLogger's "Paperless.{tag}" stays within Android's 23-char tag cap.
        private const val TAG = "RemoteCfg"
        const val KEY_LAUNCH_PROMO_ENABLED = "launch_promo_enabled"
        const val KEY_LAUNCH_PROMO_END_EPOCH_MS = "launch_promo_end_epoch_ms"

        // 1h instead of the 12h default so ending the promo propagates same-day;
        // the authoritative off-switch remains the Play Console offer itself.
        private const val MIN_FETCH_INTERVAL_SECONDS = 3_600L

        // Fail-closed defaults: with no fetched values the promo stays hidden.
        val DEFAULTS: Map<String, Any> = mapOf(
            KEY_LAUNCH_PROMO_ENABLED to false,
            KEY_LAUNCH_PROMO_END_EPOCH_MS to 0L
        )
    }

    private val _launchPromoConfig = MutableStateFlow(LaunchPromoConfig(enabled = false, endEpochMs = 0L))
    val launchPromoConfig: StateFlow<LaunchPromoConfig> = _launchPromoConfig.asStateFlow()

    /** Sets in-app defaults and fetches remote values. Safe to call once from Application.onCreate. */
    fun initialize() {
        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(MIN_FETCH_INTERVAL_SECONDS)
                .build()
        )
        remoteConfig.setDefaultsAsync(DEFAULTS)
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                AppLogger.w(TAG, "Remote Config fetch failed — publishing activated/default values")
            }
            // Previously activated values (or the fail-closed defaults) are still valid reads.
            publishCurrentValues()
        }
    }

    private fun publishCurrentValues() {
        _launchPromoConfig.value = LaunchPromoConfig(
            enabled = remoteConfig.getBoolean(KEY_LAUNCH_PROMO_ENABLED),
            endEpochMs = remoteConfig.getLong(KEY_LAUNCH_PROMO_END_EPOCH_MS)
        )
    }
}
