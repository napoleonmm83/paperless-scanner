package com.paperless.scanner.worker

import androidx.work.NetworkType
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [UploadConstraintsProvider] — the single source of truth for upload-queue
 * WorkManager constraints. [androidx.work.Constraints] is a plain value holder, so these run
 * on the JVM without the Robolectric/WorkManager harness.
 */
class UploadConstraintsProviderTest {

    private val tokenManager: TokenManager = mockk()

    @Test
    fun `build requires CONNECTED when unmetered-only is disabled`() {
        every { tokenManager.getUploadUnmeteredOnlySync() } returns false

        val constraints = UploadConstraintsProvider(tokenManager).build()

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresStorageNotLow())
    }

    @Test
    fun `build requires UNMETERED when unmetered-only is enabled`() {
        every { tokenManager.getUploadUnmeteredOnlySync() } returns true

        val constraints = UploadConstraintsProvider(tokenManager).build()

        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        // Battery/storage deferral (#134) must hold regardless of the network preference.
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresStorageNotLow())
    }
}
