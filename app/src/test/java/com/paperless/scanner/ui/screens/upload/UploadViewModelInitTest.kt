package com.paperless.scanner.ui.screens.upload

import com.paperless.scanner.ui.navigation.AppLockRouteArgsHolder
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.CustomFieldRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [UploadViewModel] init/SavedStateHandle behavior (#70).
 *
 * Lives separately from [UploadViewModelTest] because these tests need real
 * [Uri.parse]/[Uri.encode] (Android framework) which require Robolectric.
 * The legacy [UploadViewModelTest] uses `mockk<Uri>()` and runs without Robolectric,
 * which is faster — moving it would slow down ~30 unrelated tests for no benefit.
 *
 * Coverage:
 * - init {} parses URL-encoded `documentUris` nav arg synchronously into the
 *   public `documentUris` StateFlow (AC #1, AC #2).
 * - init {} parses unencoded process-death-restored value synchronously.
 * - empty SavedStateHandle yields empty `documentUris`.
 * - Turbine: a fresh subscriber sees the populated list as the *first* emission,
 *   never empty-then-populated (AC #3 — proves the LaunchedEffect race is gone).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class UploadViewModelInitTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var tagRepository: TagRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var correspondentRepository: CorrespondentRepository
    private lateinit var customFieldRepository: CustomFieldRepository
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var uploadWorkManager: com.paperless.scanner.worker.UploadWorkManager
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var serverHealthMonitor: com.paperless.scanner.data.health.ServerHealthMonitor
    private lateinit var analyticsService: AnalyticsService
    private lateinit var suggestionOrchestrator: SuggestionOrchestrator
    private lateinit var aiUsageRepository: AiUsageRepository
    private lateinit var premiumFeatureManager: PremiumFeatureManager
    private lateinit var tokenManager: TokenManager
    private lateinit var routeArgsHolder: AppLockRouteArgsHolder

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock android.util.Log (project-wide convention; see AppLockManagerTest).
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any()) } returns 0

        context = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)
        customFieldRepository = mockk(relaxed = true)
        uploadQueueRepository = mockk(relaxed = true)
        uploadWorkManager = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        serverHealthMonitor = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)
        suggestionOrchestrator = mockk(relaxed = true)
        aiUsageRepository = mockk(relaxed = true)
        premiumFeatureManager = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        routeArgsHolder = AppLockRouteArgsHolder()

        // The init {}-block uses these reactive Flows; provide empty defaults.
        every { tagRepository.observeTags() } returns flowOf(emptyList())
        every { documentTypeRepository.observeDocumentTypes() } returns flowOf(emptyList())
        every { correspondentRepository.observeCorrespondents() } returns flowOf(emptyList())
        every { customFieldRepository.observeCustomFields() } returns flowOf(emptyList())
        every { aiUsageRepository.observeCurrentMonthCallCount() } returns flowOf(0)
        every { tokenManager.aiNewTagsEnabled } returns flowOf(true)
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(true)
        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        every { networkMonitor.isWifiConnected } returns MutableStateFlow(true)
        every { premiumFeatureManager.isPremiumAccessEnabled } returns flowOf(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    /** Builds an [UploadViewModel] with the given [SavedStateHandle], reusing all the @Before mocks. */
    private fun buildViewModel(savedStateHandle: SavedStateHandle): UploadViewModel = UploadViewModel(
        context = context,
        savedStateHandle = savedStateHandle,
        tagRepository = tagRepository,
        documentTypeRepository = documentTypeRepository,
        correspondentRepository = correspondentRepository,
        customFieldRepository = customFieldRepository,
        uploadQueueRepository = uploadQueueRepository,
        uploadWorkManager = uploadWorkManager,
        networkMonitor = networkMonitor,
        serverHealthMonitor = serverHealthMonitor,
        analyticsService = analyticsService,
        suggestionOrchestrator = suggestionOrchestrator,
        aiUsageRepository = aiUsageRepository,
        premiumFeatureManager = premiumFeatureManager,
        tokenManager = tokenManager,
        routeArgsHolder = routeArgsHolder,
        ioDispatcher = testDispatcher
    )

    @Test
    fun `init mirrors documentUris into the route-args holder for AppLock reconstruction`() = runTest {
        val uri1 = Uri.parse("content://media/external/images/media/123")
        val uri2 = Uri.parse("content://media/external/images/media/456")
        val encoded = listOf(uri1, uri2).joinToString("|") { Uri.encode(it.toString()) }
        val savedState = SavedStateHandle(mapOf(UploadViewModel.KEY_DOCUMENT_URIS to encoded))

        buildViewModel(savedStateHandle = savedState)

        // Single source of truth (#30): the AppLock interceptor reads documentUris from
        // the holder, which UploadViewModel populates in lock-step with its SavedStateHandle.
        assertEquals(
            listOf(uri1, uri2).joinToString("|") { it.toString() },
            routeArgsHolder.get(UploadViewModel.KEY_DOCUMENT_URIS)
        )
    }

    @Test
    fun `init parses URL-encoded documentUris nav arg into documentUris StateFlow synchronously`() = runTest {
        val uri1 = Uri.parse("content://media/external/images/media/123")
        val uri2 = Uri.parse("content://media/external/images/media/456")
        val encoded = listOf(uri1, uri2).joinToString("|") { Uri.encode(it.toString()) }
        val savedStateWithNavArg = SavedStateHandle(mapOf(UploadViewModel.KEY_DOCUMENT_URIS to encoded))

        val viewModel = buildViewModel(savedStateHandle = savedStateWithNavArg)

        // No advanceUntilIdle, no collect-then-await: documentUris.value MUST be populated synchronously
        // by init {}. This is the contract that breaks the LaunchedEffect race.
        assertEquals(listOf(uri1, uri2), viewModel.documentUris.value)
    }

    @Test
    fun `init parses unencoded documentUris from process-death SavedStateHandle synchronously`() = runTest {
        val uri1 = Uri.parse("content://media/external/images/media/123")
        val uri2 = Uri.parse("content://media/external/images/media/456")
        // Unencoded form — what we re-write to SavedStateHandle on init so subsequent
        // process-death restorations are idempotent.
        val unencoded = listOf(uri1, uri2).joinToString("|") { it.toString() }
        val savedStateAfterDeath = SavedStateHandle(mapOf(UploadViewModel.KEY_DOCUMENT_URIS to unencoded))

        val viewModel = buildViewModel(savedStateHandle = savedStateAfterDeath)

        assertEquals(listOf(uri1, uri2), viewModel.documentUris.value)
    }

    @Test
    fun `init with empty SavedStateHandle exposes empty documentUris`() = runTest {
        val viewModel = buildViewModel(savedStateHandle = SavedStateHandle())

        assertEquals(emptyList<Uri>(), viewModel.documentUris.value)
    }

    @Test
    fun `documentUris first emission to a fresh subscriber is the populated list, never empty`() = runTest {
        val uri = Uri.parse("content://media/external/images/media/789")
        val encoded = Uri.encode(uri.toString())
        val savedStateWithNavArg = SavedStateHandle(mapOf(UploadViewModel.KEY_DOCUMENT_URIS to encoded))

        val viewModel = buildViewModel(savedStateHandle = savedStateWithNavArg)

        viewModel.documentUris.test {
            // First emission MUST be the populated list. If we ever see an empty one first,
            // the LaunchedEffect(observedDocumentUris) in MultiPageUploadScreen would fire twice
            // and dependent flows would race.
            assertEquals(listOf(uri), awaitItem())
            // No further emissions: the StateFlow has reached its terminal value.
            expectNoEvents()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `init preserves percent-encoded characters in URI path on process-death restore`() = runTest {
        // Edge case: a URI whose path contains a percent-encoded character that, if
        // blindly Uri.decoded, would change the URI's structural semantics
        // (e.g., %23 -> '#' would shift content into the fragment position).
        // The canonicalised process-death form must round-trip without that corruption.
        val uri = Uri.parse("content://media/external/file/foo%2Bbar.jpg")
        val unencoded = uri.toString()
        val savedState = SavedStateHandle(mapOf(UploadViewModel.KEY_DOCUMENT_URIS to unencoded))

        val viewModel = buildViewModel(savedStateHandle = savedState)

        val parsed = viewModel.documentUris.value
        assertEquals(1, parsed.size)
        // The literal "%2B" in the URI's path must survive parsing; a naive
        // Uri.decode-then-parse would turn it into "+".
        assertEquals(uri.path, parsed[0].path)
    }
}
