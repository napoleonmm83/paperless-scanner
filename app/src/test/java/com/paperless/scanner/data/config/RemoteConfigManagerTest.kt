package com.paperless.scanner.data.config

import android.util.Log
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RemoteConfigManagerTest {

    @Before
    fun setup() {
        // android.util.Log is not functional in JVM unit tests: this module's test
        // classpath ships real Log code backed by native methods (observed:
        // UnsatisfiedLinkError from Log.println_native, not the mockable-jar
        // "not mocked" RuntimeException). Mock it so the AppLogger.w call in the
        // fetch-failure path does not throw.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    private fun immediateBoolTask(success: Boolean): Task<Boolean> {
        val task = mockk<Task<Boolean>>(relaxed = true)
        every { task.isSuccessful } returns success
        every { task.addOnCompleteListener(any<OnCompleteListener<Boolean>>()) } answers {
            firstArg<OnCompleteListener<Boolean>>().onComplete(task)
            task
        }
        return task
    }

    private fun remoteConfig(fetchSucceeds: Boolean, enabled: Boolean, endEpochMs: Long): FirebaseRemoteConfig =
        mockk(relaxed = true) {
            every { fetchAndActivate() } returns immediateBoolTask(fetchSucceeds)
            every { getBoolean(RemoteConfigManager.KEY_LAUNCH_PROMO_ENABLED) } returns enabled
            every { getLong(RemoteConfigManager.KEY_LAUNCH_PROMO_END_EPOCH_MS) } returns endEpochMs
        }

    @Test
    fun `before initialize the config is fail-closed`() {
        val manager = RemoteConfigManager(mockk(relaxed = true))
        assertEquals(LaunchPromoConfig(enabled = false, endEpochMs = 0L), manager.launchPromoConfig.value)
    }

    @Test
    fun `successful fetch publishes remote values`() {
        val config = remoteConfig(fetchSucceeds = true, enabled = true, endEpochMs = 1_750_000_000_000)
        val manager = RemoteConfigManager(config)
        manager.initialize()
        assertEquals(
            LaunchPromoConfig(enabled = true, endEpochMs = 1_750_000_000_000),
            manager.launchPromoConfig.value
        )
        // Pins the fail-closed in-app defaults wiring (guards against accidental removal).
        verify { config.setDefaultsAsync(RemoteConfigManager.DEFAULTS) }
    }

    @Test
    fun `failed fetch publishes previously activated values instead of hanging`() {
        // Values differ from the StateFlow's initial fail-closed state, so this
        // assertion only passes if the listener actually published on the failure path.
        val manager = RemoteConfigManager(
            remoteConfig(fetchSucceeds = false, enabled = true, endEpochMs = 123L)
        )
        manager.initialize()
        assertEquals(LaunchPromoConfig(enabled = true, endEpochMs = 123L), manager.launchPromoConfig.value)
    }
}
