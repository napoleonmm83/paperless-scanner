package com.paperless.scanner.ui.screens.login

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.paperless.scanner.ui.theme.PaperlessScannerTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class LoginScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun loginScreen_displaysAllElements() {
        composeTestRule.setContent {
            PaperlessScannerTheme {
                LoginScreen(onLoginSuccess = {})
            }
        }

        composeTestRule.onNodeWithText("Paperless Scanner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mit deiner Paperless-ngx Instanz verbinden").assertIsDisplayed()
        composeTestRule.onNodeWithText("Server URL").assertIsDisplayed()
        composeTestRule.onNodeWithText("Username").assertIsDisplayed()
        composeTestRule.onNodeWithText("Password").assertIsDisplayed()
        composeTestRule.onNodeWithText("Login").assertIsDisplayed()
    }

    @Test
    fun loginButton_disabledWhenFieldsEmpty() {
        composeTestRule.setContent {
            PaperlessScannerTheme {
                LoginScreen(onLoginSuccess = {})
            }
        }

        composeTestRule.onNodeWithText("Login").assertIsNotEnabled()
    }

    @Test
    fun loginButton_enabledWhenAllFieldsFilled() {
        composeTestRule.setContent {
            PaperlessScannerTheme {
                LoginScreen(onLoginSuccess = {})
            }
        }

        composeTestRule.onNodeWithText("Server URL").performTextInput("https://test.example.com")
        composeTestRule.onNodeWithText("Username").performTextInput("testuser")
        composeTestRule.onNodeWithText("Password").performTextInput("testpassword")

        composeTestRule.onNodeWithText("Login").assertIsEnabled()
    }

    @Test
    fun loginButton_disabledWhenOnlyServerUrlFilled() {
        composeTestRule.setContent {
            PaperlessScannerTheme {
                LoginScreen(onLoginSuccess = {})
            }
        }

        composeTestRule.onNodeWithText("Server URL").performTextInput("https://test.example.com")

        composeTestRule.onNodeWithText("Login").assertIsNotEnabled()
    }

    @Test
    fun passwordVisibilityToggle_works() {
        composeTestRule.setContent {
            PaperlessScannerTheme {
                LoginScreen(onLoginSuccess = {})
            }
        }

        // Initially password is hidden, so "Show password" button should be visible
        composeTestRule.onNodeWithContentDescription("Show password").assertIsDisplayed()

        // Click to show password
        composeTestRule.onNodeWithContentDescription("Show password").performClick()

        // Now "Hide password" button should be visible
        composeTestRule.onNodeWithContentDescription("Hide password").assertIsDisplayed()

        // Click to hide password again
        composeTestRule.onNodeWithContentDescription("Hide password").performClick()

        // "Show password" button should be visible again
        composeTestRule.onNodeWithContentDescription("Show password").assertIsDisplayed()
    }
}
