package com.paperless.scanner.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the disposable debug test app's own pin preferences and Keystore alias. */
@RunWith(AndroidJUnit4::class)
class EncryptedCertPinRecoveryDeviceTest {
    @Test
    fun resetRecreatesAUsableEmptyEncryptedPinStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = EncryptedCertPinStorage(context)
        storage.put("paperless.lan", "sha256/OLD")
        EncryptedCertPinStorage::class.java.getDeclaredField("pinLoadFailed").apply {
            isAccessible = true
            setBoolean(storage, true)
        }
        assertTrue(storage.hasLoadFailure())

        storage.resetCorruptedStore()

        assertFalse(storage.hasLoadFailure())
        assertNull(storage.loadAll()["paperless.lan"])
        storage.put("paperless.lan", "sha256/NEW")
        assertEquals("sha256/NEW", EncryptedCertPinStorage(context).loadAll()["paperless.lan"])
    }
}
