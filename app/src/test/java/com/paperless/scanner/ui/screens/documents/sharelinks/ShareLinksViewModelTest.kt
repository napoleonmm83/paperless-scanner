package com.paperless.scanner.ui.screens.documents.sharelinks

import com.paperless.scanner.data.repository.ShareLinkRepository
import com.paperless.scanner.domain.model.DocumentShareLink
import com.paperless.scanner.domain.model.DocumentShareLinks
import com.paperless.scanner.domain.model.FeatureStatus
import com.paperless.scanner.domain.model.PaperlessServerVersion
import com.paperless.scanner.domain.model.ServerCapabilityState
import com.paperless.scanner.domain.model.ShareFileVersion
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareLinksViewModelTest {
    private class ReadRaceFlow(private val backing: StateFlow<Long>) : StateFlow<Long> by backing {
        var switchBetweenReads = false
        private var reads = 0
        override val value: Long get() = if (switchBetweenReads && reads++ > 0) 2L else backing.value
    }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: ShareLinkRepository
    private lateinit var capabilities: MutableStateFlow<ServerCapabilityState>
    private lateinit var generation: MutableStateFlow<Long>

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk()
        capabilities = MutableStateFlow(ServerCapabilityState(
            serverVersion = PaperlessServerVersion.parse("2.0.0"),
            serverBase = "https://paperless.example/prefix/",
        ))
        generation = MutableStateFlow(1L)
        every { repository.capabilityState } returns capabilities
        every { repository.sessionGeneration } returns generation
        coEvery { repository.refreshCapabilities() } returns Result.success(Unit)
        coEvery { repository.load(any(), any()) } returns Result.success(DocumentShareLinks(listOf(link(17)), true))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun link(id: Int) = DocumentShareLink(
        id, "2026-09-30T12:00:00Z", null, "https://paperless.example/prefix/share/token$id",
    )

    @Test fun `opening supported document loads links and archive availability`() = runTest {
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        assertEquals(FeatureStatus.AVAILABLE, vm.state.value.availability)
        vm.open(42)
        assertTrue(vm.state.value.busy)
        runCurrent()
        assertTrue(vm.state.value.loaded)
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.hasArchiveVersion)
        assertEquals(listOf(link(17)), vm.state.value.links)
        assertEquals(link(17).url, vm.urlFor(17))
        coVerify(exactly = 1) { repository.load(42, any()) }
        coVerify(exactly = 1) { repository.refreshCapabilities() }
    }

    @Test fun `creation blocks duplicate clicks until first request completes`() = runTest {
        val created = CompletableDeferred<Result<DocumentShareLink>>()
        coEvery { repository.create(42, 7, ShareFileVersion.ORIGINAL, any()) } coAnswers { created.await() }
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()

        vm.create(7, ShareFileVersion.ORIGINAL)
        vm.create(7, ShareFileVersion.ORIGINAL)
        runCurrent()
        assertTrue(vm.state.value.busy)
        coVerify(exactly = 1) { repository.create(42, 7, ShareFileVersion.ORIGINAL, any()) }
        assertEquals(listOf(link(17)), vm.state.value.links)

        created.complete(Result.success(link(18)))
        runCurrent()
        assertFalse(vm.state.value.busy)
        assertEquals(listOf(link(18), link(17)), vm.state.value.links)
    }

    @Test fun `failed creation retains existing links and exposes error`() = runTest {
        val error = IOException("create denied")
        coEvery { repository.create(any(), any(), any(), any()) } returns Result.failure(error)
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        vm.create(null, ShareFileVersion.ORIGINAL)
        runCurrent()
        assertEquals(listOf(link(17)), vm.state.value.links)
        assertSame(error, vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `failed revocation retains link and exposes error`() = runTest {
        val error = IOException("revoke denied")
        coEvery { repository.delete(17, any()) } returns Result.failure(error)
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        vm.revoke(17)
        runCurrent()
        assertEquals(listOf(link(17)), vm.state.value.links)
        assertSame(error, vm.state.value.error)
        assertEquals(link(17).url, vm.urlFor(17))
        assertFalse(vm.state.value.busy)
    }

    @Test fun `successful revocation removes only the selected link`() = runTest {
        coEvery { repository.load(42, any()) } returns Result.success(DocumentShareLinks(listOf(link(17), link(18)), true))
        coEvery { repository.delete(17, any()) } returns Result.success(Unit)
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        vm.revoke(17)
        runCurrent()
        assertEquals(listOf(link(18)), vm.state.value.links)
        assertNull(vm.urlFor(17))
        assertNull(vm.state.value.error)
        coVerify(exactly = 1) { repository.delete(17, any()) }
    }

    @Test fun `absent archive prevents archive publishing while original remains possible`() = runTest {
        coEvery { repository.load(42, any()) } returns Result.success(DocumentShareLinks(emptyList(), false))
        coEvery { repository.create(42, 7, ShareFileVersion.ORIGINAL, any()) } returns Result.success(link(18))
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        vm.create(7, ShareFileVersion.ARCHIVE)
        runCurrent()
        coVerify(exactly = 0) { repository.create(any(), any(), ShareFileVersion.ARCHIVE, any()) }
        assertFalse(vm.state.value.busy)
        vm.create(7, ShareFileVersion.ORIGINAL)
        runCurrent()
        assertEquals(listOf(link(18)), vm.state.value.links)
    }

    @Test fun `creation before metadata has loaded is blocked`() = runTest {
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.create(7, ShareFileVersion.ORIGINAL)
        runCurrent()
        coVerify(exactly = 0) { repository.create(any(), any(), any(), any()) }
    }

    @Test fun `account change immediately disables URLs and clears prior account state`() = runTest {
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        assertNotNull(vm.urlFor(17))

        generation.value = 2L
        // The direct URL guard must close before the flow collector gets CPU time.
        assertNull(vm.urlFor(17))
        capabilities.value = ServerCapabilityState()
        runCurrent()
        assertTrue(vm.state.value.links.isEmpty())
        assertFalse(vm.state.value.loaded)
        assertFalse(vm.state.value.busy)
        assertEquals(FeatureStatus.UNKNOWN, vm.state.value.availability)
    }

    @Test fun `late noncancellable old account load cannot repopulate links`() = runTest {
        val oldResult = CompletableDeferred<Result<DocumentShareLinks>>()
        coEvery { repository.load(42, any()) } coAnswers { withContext(NonCancellable) { oldResult.await() } }
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        generation.value = 2L
        capabilities.value = ServerCapabilityState()
        runCurrent()
        oldResult.complete(Result.success(DocumentShareLinks(listOf(link(17)), true)))
        runCurrent()
        assertTrue(vm.state.value.links.isEmpty())
        assertFalse(vm.state.value.loaded)
        assertNull(vm.urlFor(17))
    }

    @Test fun `account change blocks publishing from stale form before collector runs`() = runTest {
        coEvery { repository.create(any(), any(), any(), any()) } returns Result.success(link(18))
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        generation.value = 2L
        vm.create(7, ShareFileVersion.ORIGINAL)
        runCurrent()
        coVerify(exactly = 0) { repository.create(any(), any(), any(), any()) }
        assertTrue(vm.state.value.links.isEmpty())
    }

    @Test fun `queued creation never publishes after account switches before coroutine starts`() = runTest {
        coEvery { repository.create(any(), any(), any(), any()) } returns Result.success(link(18))
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        vm.create(7, ShareFileVersion.ORIGINAL)
        generation.value = 2L
        runCurrent()
        coVerify(exactly = 0) { repository.create(any(), any(), any(), any()) }
    }

    @Test fun `generation changing between synchronous reads cannot rebind old document to new account`() = runTest {
        val racedGeneration = ReadRaceFlow(generation)
        every { repository.sessionGeneration } returns racedGeneration
        coEvery { repository.create(any(), any(), any(), any()) } returns Result.success(link(18))
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        racedGeneration.switchBetweenReads = true
        vm.create(7, ShareFileVersion.ORIGINAL)
        runCurrent()
        coVerify(exactly = 0) { repository.create(any(), any(), any(), any()) }
    }

    @Test fun `server availability loss disables sharing and new creation`() = runTest {
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        capabilities.value = capabilities.value.copy(serverVersion = PaperlessServerVersion.parse("1.17.4"))
        runCurrent()
        assertEquals(FeatureStatus.UPDATE_REQUIRED, vm.state.value.availability)
        assertNull(vm.urlFor(17))
        vm.create(7, ShareFileVersion.ORIGINAL)
        runCurrent()
        coVerify(exactly = 0) { repository.create(any(), any(), any(), any()) }
    }

    @Test fun `opening another document ignores late noncancellable previous document load`() = runTest {
        val oldResult = CompletableDeferred<Result<DocumentShareLinks>>()
        coEvery { repository.load(42, any()) } coAnswers { withContext(NonCancellable) { oldResult.await() } }
        coEvery { repository.load(43, any()) } returns Result.success(DocumentShareLinks(listOf(link(18)), false))
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        vm.open(43)
        runCurrent()
        assertEquals(listOf(link(18)), vm.state.value.links)
        oldResult.complete(Result.success(DocumentShareLinks(listOf(link(17)), true)))
        runCurrent()
        assertEquals(listOf(link(18)), vm.state.value.links)
        assertFalse(vm.state.value.hasArchiveVersion)
        assertNull(vm.urlFor(17))
    }

    @Test fun `late noncancellable creation cannot expose old account public link`() = runTest {
        val created = CompletableDeferred<Result<DocumentShareLink>>()
        coEvery { repository.create(any(), any(), any(), any()) } coAnswers { withContext(NonCancellable) { created.await() } }
        val vm = ShareLinksViewModel(repository)
        runCurrent()
        vm.open(42)
        runCurrent()
        vm.create(7, ShareFileVersion.ORIGINAL)
        runCurrent()
        generation.value = 2L
        capabilities.value = ServerCapabilityState()
        runCurrent()
        created.complete(Result.success(link(18)))
        runCurrent()
        assertTrue(vm.state.value.links.isEmpty())
        assertNull(vm.urlFor(18))
        assertFalse(vm.state.value.busy)
    }
}
