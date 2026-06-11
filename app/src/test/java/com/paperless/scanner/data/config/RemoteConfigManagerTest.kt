package com.paperless.scanner.data.config

import android.util.Log
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RemoteConfigManagerTest {

    @Before
    fun setup() {
        // android.util.Log is a JVM stub in unit tests; mock it so the
        // AppLogger.w call in the fetch-failure path does not throw UnsatisfiedLinkError.
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
        val manager = RemoteConfigManager(
            remoteConfig(fetchSucceeds = true, enabled = true, endEpochMs = 1_750_000_000_000)
        )
        manager.initialize()
        assertEquals(
            LaunchPromoConfig(enabled = true, endEpochMs = 1_750_000_000_000),
            manager.launchPromoConfig.value
        )
    }

    @Test
    fun `failed fetch publishes activated or default values instead of hanging`() {
        val manager = RemoteConfigManager(
            remoteConfig(fetchSucceeds = false, enabled = false, endEpochMs = 0L)
        )
        manager.initialize()
        assertEquals(LaunchPromoConfig(enabled = false, endEpochMs = 0L), manager.launchPromoConfig.value)
    }
}
