package com.paperless.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.ui.components.AnalyticsConsentDialog
import com.paperless.scanner.ui.navigation.PaperlessNavGraph
import com.paperless.scanner.ui.navigation.Screen
import com.paperless.scanner.ui.theme.LocalWindowSizeClass
import com.paperless.scanner.ui.theme.PaperlessScannerTheme
import com.paperless.scanner.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var analyticsService: AnalyticsService

    @Inject
    lateinit var appLockManager: com.paperless.scanner.util.AppLockManager

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
                    val token by tokenManager.token.collectAsState(initial = null)
                    val onboardingCompleted by tokenManager.onboardingCompleted.collectAsState(initial = false)
                    val analyticsConsentAsked by tokenManager.analyticsConsentAsked.collectAsState(initial = true)
                    val navController = rememberNavController()
                    val coroutineScope = rememberCoroutineScope()

                    val startDestination = when {
                        // New user - show new onboarding
                        !onboardingCompleted -> Screen.OnboardingWelcome.route
                        // Onboarding completed but no token (logged out) - show old onboarding
                        token.isNullOrBlank() -> Screen.Welcome.route
                        // Logged in - go to home
                        else -> Screen.Home.route
                    }

                    PaperlessNavGraph(
                        navController = navController,
                        startDestination = startDestination,
                        sharedUris = sharedUris,
                        tokenManager = tokenManager,
                        appLockManager = appLockManager
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
