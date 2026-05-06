package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateNoteRequest
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuditRepositoryTest {

    private lateinit var context: Context
    private lateinit var api: PaperlessApi
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var repo: AuditRepository

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        api = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        every { context.getString(any()) } returns "offline"
        repo = AuditRepository(context, api, networkMonitor)
    }

    @Test
    fun `getDocumentHistory online returns mapped audit log`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getDocumentHistory(42) } returns emptyList()

        val result = repo.getDocumentHistory(42)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<Any>(), result.getOrNull())
        coVerify { api.getDocumentHistory(42) }
    }

    @Test
    fun `getDocumentHistory offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.getDocumentHistory(42)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
        coVerify(exactly = 0) { api.getDocumentHistory(any()) }
    }

    @Test
    fun `addNote online sends CreateNoteRequest and returns mapped notes`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val requestSlot = slot<CreateNoteRequest>()
        coEvery { api.addNote(42, capture(requestSlot)) } returns emptyList()

        val result = repo.addNote(42, "Hello world")

        assertTrue(result.isSuccess)
        assertEquals("Hello world", requestSlot.captured.note)
    }

    @Test
    fun `addNote offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.addNote(42, "ignored")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
        coVerify(exactly = 0) { api.addNote(any(), any()) }
    }

    @Test
    fun `deleteNote online returns mapped notes`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.deleteNote(42, 7) } returns emptyList()

        val result = repo.deleteNote(42, 7)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<Any>(), result.getOrNull())
        coVerify { api.deleteNote(42, 7) }
    }

    @Test
    fun `deleteNote offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.deleteNote(42, 7)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
        coVerify(exactly = 0) { api.deleteNote(any(), any()) }
    }
}
