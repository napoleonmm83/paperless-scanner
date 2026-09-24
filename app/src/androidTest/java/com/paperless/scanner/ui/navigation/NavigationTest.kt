package com.paperless.scanner.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.paperless.scanner.HiltTestActivity
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.ui.theme.LocalWindowSizeClass
import com.paperless.scanner.ui.theme.PaperlessScannerTheme
import com.paperless.scanner.util.AppLockManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * On-device smoke test: the nav graph's two entry points actually render.
 * Route strings themselves are covered by the JVM ScreenTest.
 */
@HiltAndroidTest
class NavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // A Hilt-enabled host: the graph's hiltViewModel() calls cannot resolve in the plain
    // ComponentActivity that createComposeRule() uses.
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var routeArgsHolder: AppLockRouteArgsHolder

    @Inject
    lateinit var analyticsService: AnalyticsService

    @Inject
    lateinit var crashlyticsHelper: CrashlyticsHelper

    @Before
    fun setup() {
        // AppLockManager registers a ProcessLifecycleOwner observer in its init block,
        // and LifecycleRegistry.addObserver throws off the main thread. @Before runs on
        // the instrumentation thread, so the singleton must be created on main — as it
        // is in the real app, where Activity/Application inject it.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { hiltRule.inject() }
    }

    // Mirrors MainActivity.setContent: screens read LocalWindowSizeClass and fail without it.
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    private fun showGraph(startDestination: String) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalWindowSizeClass provides calculateWindowSizeClass(composeTestRule.activity)
            ) {
                PaperlessScannerTheme {
                    PaperlessNavGraph(
                        navController = rememberNavController(),
                        startDestination = startDestination,
                        tokenManager = tokenManager,
                        appLockManager = appLockManager,
                        routeArgsHolder = routeArgsHolder,
                        analyticsService = analyticsService,
                        crashlyticsHelper = crashlyticsHelper
                    )
                }
            }
        }
    }

    // Headlines are uppercased by the style guide, so match case-insensitively.
    private fun assertTextShown(@StringRes id: Int) {
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(id), ignoreCase = true)
            .assertIsDisplayed()
    }

    @Test
    fun welcomeDestination_showsServerSetup() {
        // Logged-out start destination (MainActivity): the unified onboarding setup.
        showGraph(Screen.Welcome.route)

        assertTextShown(R.string.setup_title_connect)
        assertTextShown(R.string.setup_server_url)
    }

    @Test
    fun scanDestination_showsSourceOptions() {
        showGraph(Screen.Scan.route)

        // Not scan_option_scan: "Scan" is also the bottom-nav label, so it matches twice.
        assertTextShown(R.string.scan_new_document_title)
        assertTextShown(R.string.scan_choose_option)
        assertTextShown(R.string.scan_option_gallery)
        assertTextShown(R.string.scan_option_files)
    }
}
