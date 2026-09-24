package com.paperless.scanner.data.network

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class EncryptedCertPinStorageTest {

    @Test
    fun `failed durable commit rejects pin instead of silently accepting it`() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.edit() } returns editor
        every { editor.putString("paperless.lan", "sha256/AAA") } returns editor
        every { editor.commit() } returns false
        every { editor.apply() } returns Unit
        val storage = EncryptedCertPinStorage(mockk<Context>())
        EncryptedCertPinStorage::class.java.getDeclaredField("cachedPrefs").apply {
            isAccessible = true
            set(storage, prefs)
        }

        assertThrows(IOException::class.java) {
            storage.put("paperless.lan", "sha256/AAA")
        }
        verify(exactly = 1) { editor.commit() }
        verify(exactly = 0) { editor.apply() }
    }

    @Test
    fun `unreadable existing pins cannot be replaced by a new TOFU pin`() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.all } throws IOException("encrypted pins unreadable")
        every { prefs.edit() } returns editor
        every { editor.putString("paperless.lan", "sha256/NEW") } returns editor
        every { editor.commit() } returns true
        val storage = EncryptedCertPinStorage(mockk<Context>())
        EncryptedCertPinStorage::class.java.getDeclaredField("cachedPrefs").apply {
            isAccessible = true
            set(storage, prefs)
        }

        assertTrue(storage.loadAll().isEmpty())
        assertThrows(IOException::class.java) {
            storage.put("paperless.lan", "sha256/NEW")
        }
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `keystore open failure never deletes existing encrypted pins`() {
        val context = mockk<Context>(relaxed = true)
        val storage = EncryptedCertPinStorage(context)

        assertTrue(storage.loadAll().isEmpty())
        assertThrows(IOException::class.java) {
            storage.put("paperless.lan", "sha256/NEW")
        }
        verify(exactly = 0) { context.deleteSharedPreferences(any()) }
    }

    @Test
    fun `malformed stored pin is not treated as first contact`() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.all } returns mapOf("paperless.lan" to 42)
        every { prefs.edit() } returns editor
        every { editor.putString("paperless.lan", "sha256/NEW") } returns editor
        every { editor.commit() } returns true
        val storage = EncryptedCertPinStorage(mockk<Context>())
        EncryptedCertPinStorage::class.java.getDeclaredField("cachedPrefs").apply {
            isAccessible = true
            set(storage, prefs)
        }

        assertTrue(storage.loadAll().isEmpty())
        assertThrows(IOException::class.java) {
            storage.put("paperless.lan", "sha256/NEW")
        }
        verify(exactly = 0) { prefs.edit() }
    }
}
