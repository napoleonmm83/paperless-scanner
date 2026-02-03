package com.paperless.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.BuildConfig
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.ui.components.AnalyticsConsentDialog
import com.paperless.scanner.ui.navigation.PaperlessNavGraph
import com.paperless.scanner.ui.navigation.Screen
import com.paperless.scanner.ui.theme.LocalWindowSizeClass
import com.paperless.scanner.ui.theme.PaperlessScannerTheme
import com.paperless.scanner.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var analyticsService: AnalyticsService

    @Inject
    lateinit var appLockManager: com.paperless.scanner.util.AppLockManager

    @Inject
    lateinit var crashlyticsHelper: CrashlyticsHelper

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

        // Initialize analytics based on stored consent
        val hasConsent = tokenManager.isAnalyticsConsentGrantedSync()
        analyticsService.setEnabled(hasConsent)

        // Initialize Crashlytics custom keys if consent is granted
        if (hasConsent) {
            val serverUrl = tokenManager.getServerUrlSync()
            val subscriptionStatus = "free" // TODO: Get from BillingManager when available
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
                    val analyticsConsentAsked by tokenManager.analyticsConsentAsked.collectAsState(initial = true)
                    val navController = rememberNavController()
                    val coroutineScope = rememberCoroutineScope()

                    // CRITICAL: Load startDestination synchronously to avoid race conditions
                    // Using collectAsState with wrong initials causes wrong startDestination on Activity recreation
                    val startDestination = remember {
                        runBlocking {
                            val token = tokenManager.token.first()

                            when {
                                // Not logged in - show unified onboarding (SimplifiedSetup on Welcome route)
                                token.isNullOrBlank() -> Screen.Welcome.route
                                // Logged in - go to home (AppLockNavigationInterceptor handles locking)
                                else -> Screen.Home.route
                            }
                        }
                    }

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

                    PaperlessNavGraph(
                        navController = navController,
                        startDestination = startDestination,
                        sharedUris = sharedUris,
                        tokenManager = tokenManager,
                        appLockManager = appLockManager,
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
