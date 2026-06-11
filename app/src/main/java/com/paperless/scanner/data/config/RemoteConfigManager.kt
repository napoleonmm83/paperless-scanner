package com.paperless.scanner.data.config

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.paperless.scanner.util.AppLogger
import dagger.Lazy
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
 *
 * [FirebaseRemoteConfig] is injected via [dagger.Lazy]: `getInstance()` requires
 * an initialized FirebaseApp, which does not exist under Robolectric (no
 * FirebaseInitProvider) — eager resolution at Hilt member-injection time would
 * crash every Robolectric test in `Application.onCreate`. Resolution happens in
 * [initialize], guarded so a missing FirebaseApp degrades to the fail-closed
 * defaults instead of crashing startup (matches the lazy/guarded pattern of the
 * other Firebase usages in this app, e.g. AnalyticsService).
 */
@Singleton
class RemoteConfigManager @Inject constructor(
    private val remoteConfigProvider: Lazy<FirebaseRemoteConfig>
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
        val config = try {
            remoteConfigProvider.get()
        } catch (e: IllegalStateException) {
            // FirebaseApp not initialized (e.g. Robolectric unit tests, stripped builds):
            // stay on the fail-closed defaults instead of crashing app startup.
            AppLogger.w(TAG, "Firebase unavailable — launch promo stays disabled", e)
            return
        }
        config.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(MIN_FETCH_INTERVAL_SECONDS)
                .build()
        )
        config.setDefaultsAsync(DEFAULTS)
        config.fetchAndActivate().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                AppLogger.w(TAG, "Remote Config fetch failed — publishing activated/default values")
            }
            // Previously activated values (or the fail-closed defaults) are still valid reads.
            publishCurrentValues(config)
        }
    }

    private fun publishCurrentValues(config: FirebaseRemoteConfig) {
        _launchPromoConfig.value = LaunchPromoConfig(
            enabled = config.getBoolean(KEY_LAUNCH_PROMO_ENABLED),
            endEpochMs = config.getLong(KEY_LAUNCH_PROMO_END_EPOCH_MS)
        )
    }
}
