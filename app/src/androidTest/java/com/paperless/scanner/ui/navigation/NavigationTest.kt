package com.paperless.scanner.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.ui.theme.PaperlessScannerTheme
import com.paperless.scanner.util.AppLockManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class NavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var appLockManager: AppLockManager

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun startDestination_isLoginScreen() {
        composeTestRule.setContent {
            PaperlessScannerTheme {
                val navController = rememberNavController()
                PaperlessNavGraph(
                    navController = navController,
                    startDestination = Screen.Login.route,
                    tokenManager = tokenManager,
                    appLockManager = appLockManager
                )
            }
        }

        // Login screen should be displayed
        composeTestRule.onNodeWithText("Paperless Scanner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Server URL").assertIsDisplayed()
    }

    @Test
    fun scanScreen_displaysCorrectly() {
        composeTestRule.setContent {
            PaperlessScannerTheme {
                val navController = rememberNavController()
                PaperlessNavGraph(
                    navController = navController,
                    startDestination = Screen.Scan.routeBase,
                    tokenManager = tokenManager,
                    appLockManager = appLockManager
                )
            }
        }

        // Scan screen elements should be displayed
        composeTestRule.onNodeWithText("Paperless Scanner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dokument scannen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Kamera öffnen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Galerie Import").assertIsDisplayed()
    }

    @Test
    fun screenRoutes_areCorrect() {
        // Verify route strings are correct
        assert(Screen.Login.route == "login")
        assert(Screen.Scan.route == "scan")
        assert(Screen.Upload.route == "upload/{documentUri}")
        assert(Screen.MultiPageUpload.route == "upload-multi/{documentUris}")
        assert(Screen.BatchImport.route == "batch-import/{imageUris}")
    }
}
