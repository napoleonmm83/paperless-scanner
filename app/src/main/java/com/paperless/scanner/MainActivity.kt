package com.paperless.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.BuildConfig
import com.paperless.scanner.data.billing.BillingManager
import com.paperless.scanner.data.billing.analyticsName
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.ui.components.AnalyticsConsentDialog
import com.paperless.scanner.ui.navigation.PaperlessNavGraph
import com.paperless.scanner.ui.navigation.Screen
import com.paperless.scanner.ui.theme.LocalWindowSizeClass
import com.paperless.scanner.ui.theme.PaperlessScannerTheme
import com.paperless.scanner.ui.theme.ThemeMode
import com.paperless.scanner.util.DeepLinkAction
import com.paperless.scanner.util.DeepLinkHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var analyticsService: AnalyticsService

    @Inject
    lateinit var appLockManager: com.paperless.scanner.util.AppLockManager

    @Inject
    lateinit var routeArgsHolder: com.paperless.scanner.ui.navigation.AppLockRouteArgsHolder

    @Inject
    lateinit var crashlyticsHelper: CrashlyticsHelper

    @Inject
    lateinit var billingManager: BillingManager

    /** Pending deep link action from widget or external source. Consumed once by NavGraph. */
    private val _pendingDeepLink = MutableStateFlow<DeepLinkAction?>(null)
    val pendingDeepLink = _pendingDeepLink.asStateFlow()

    /** Consume the pending deep link (called after navigation handles it). */
    fun consumeDeepLink() {
        _pendingDeepLink.value = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled silently */ }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable Edge-to-Edge (Android 15+ compatible)
        enableEdgeToEdge()

        requestNotificationPermission()

        val sharedUris = handleShareIntent(intent)

        // Parse deep link from launch intent (widget taps)
        _pendingDeepLink.value = DeepLinkHandler.parseIntent(intent)

        // Initialize analytics based on stored consent.
        // Intentional one-time synchronous read on the main thread during startup,
        // before analytics/Crashlytics init. @WorkerThread (#40) now flags it; the
        // blocking read here is deliberate and not moved off-main (init ordering).
        @Suppress("WrongThread")
        val hasConsent = tokenManager.isAnalyticsConsentGrantedSync()
        analyticsService.setEnabled(hasConsent)

        // Initialize Crashlytics custom keys if consent is granted
        if (hasConsent) {
            // Intentional one-time synchronous startup read for Crashlytics keys
            // (see the @Suppress note above); deliberately on main, not moved off (#40).
            @Suppress("WrongThread")
            val serverUrl = tokenManager.getServerUrlSync()
            // Non-blocking StateFlow read. Billing connects asynchronously, so this is
            // usually still FREE here — SubscriptionAnalyticsSync (PaperlessApp) keeps
            // the key current once the first purchase query lands (#296).
            val subscriptionStatus = billingManager.subscriptionStatusSync().analyticsName()
            analyticsService.initializeCrashlyticsKeys(
                serverUrl = serverUrl,
                appVersion = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                subscriptionStatus = subscriptionStatus,
                isOffline = false // Initial state, will be updated by NetworkMonitor
            )
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val themeModeKey by tokenManager.themeMode.collectAsState(initial = "system")
            val themeMode = ThemeMode.entries.find { it.key == themeModeKey } ?: ThemeMode.SYSTEM

            CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                PaperlessScannerTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Resolve startDestination asynchronously to keep onCreate non-blocking.
                    // While the token is unresolved (loggedIn == null), the Surface holds the
                    // splash background so the system splash blends seamlessly into the
                    // first frame; the NavGraph mounts only after the token is known, so all
                    // existing route checks (sharedUris, deep links, savedInstanceState) keep
                    // their original semantics. F-114 / Issue #141.
                    val loggedIn by produceState<Boolean?>(initialValue = null) {
                        // Run the DataStore read on Dispatchers.IO so the first
                        // (cold) emission's disk read does not stall a main-thread
                        // frame. CancellationException must be re-thrown so the
                        // produceState coroutine can be cancelled cleanly when the
                        // composable leaves the composition (e.g., Activity destroy).
                        value = withContext(Dispatchers.IO) {
                            try {
                                !tokenManager.token.first().isNullOrBlank()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // DataStore corruption or similar — default to
                                // logged-out so the user lands on Welcome and can
                                // re-authenticate rather than getting stuck on a
                                // blank Surface.
                                false
                            }
                        }
                    }
                    val resolvedLogin = loggedIn ?: return@Surface

                    val startDestination = if (resolvedLogin) {
                        // Logged in - go to home (AppLockNavigationInterceptor handles locking)
                        Screen.Home.route
                    } else {
                        // Not logged in - show unified onboarding (SimplifiedSetup on Welcome route)
                        Screen.Welcome.route
                    }

                    val analyticsConsentAsked by tokenManager.analyticsConsentAsked.collectAsState(initial = true)
                    val navController = rememberNavController()
                    val coroutineScope = rememberCoroutineScope()

                    // Reset navigation to startDestination after process death to avoid invalid state
                    // When savedInstanceState != null, Android tried to restore the back stack but
                    // navigation args may be lost, causing issues like documentId=0
                    LaunchedEffect(savedInstanceState) {
                        if (savedInstanceState != null) {
                            navController.navigate(startDestination) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }

                    val pendingDeepLinkAction by pendingDeepLink.collectAsState()

                    PaperlessNavGraph(
                        navController = navController,
                        startDestination = startDestination,
                        sharedUris = sharedUris,
                        pendingDeepLink = pendingDeepLinkAction,
                        onDeepLinkConsumed = { consumeDeepLink() },
                        tokenManager = tokenManager,
                        appLockManager = appLockManager,
                        routeArgsHolder = routeArgsHolder,
                        analyticsService = analyticsService,
                        crashlyticsHelper = crashlyticsHelper
                    )

                    // Show consent dialog on first launch
                    if (!analyticsConsentAsked) {
                        AnalyticsConsentDialog(
                            onAccept = {
                                coroutineScope.launch {
                                    tokenManager.setAnalyticsConsent(true)
                                    analyticsService.setEnabled(true)
                                    analyticsService.trackEvent(AnalyticsEvent.AnalyticsConsentChanged(granted = true))
                                }
                            },
                            onDecline = {
                                coroutineScope.launch {
                                    tokenManager.setAnalyticsConsent(false)
                                    analyticsService.setEnabled(false)
                                }
                            }
                        )
                    }
                }
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep links when app is already running (singleTask launch mode)
        val deepLinkAction = DeepLinkHandler.parseIntent(intent)
        if (deepLinkAction != null) {
            Log.d("MainActivity", "onNewIntent: deepLinkAction=$deepLinkAction")
            _pendingDeepLink.value = deepLinkAction
        }
    }

    private fun handleShareIntent(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                // Single image or PDF
                if (intent.type?.startsWith("image/") == true ||
                    intent.type == "application/pdf"
                ) {
                    @Suppress("DEPRECATION")
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    listOfNotNull(uri)
                } else {
                    emptyList()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple images
                if (intent.type?.startsWith("image/") == true) {
                    @Suppress("DEPRECATION")
                    val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                    uris?.filterNotNull() ?: emptyList()
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
