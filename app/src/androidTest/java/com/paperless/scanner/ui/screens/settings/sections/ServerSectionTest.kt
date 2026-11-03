package com.paperless.scanner.ui.screens.settings.sections

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.paperless.scanner.domain.model.PaperlessServerVersion
import com.paperless.scanner.domain.model.ServerFeature
import com.paperless.scanner.ui.theme.PaperlessScannerTheme
import com.paperless.scanner.ui.theme.ThemeMode
import java.io.File
import org.junit.Rule
import org.junit.Test

class ServerSectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unknownVersionDoesNotPromiseAnUpdate() {
        compose.setContent {
            PaperlessScannerTheme(themeMode = ThemeMode.LIGHT) {
                Surface { Column { ServerSection("https://paperless.example.com", null, {}, {}) } }
            }
        }
        compose.onNodeWithText("Not detected yet.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Additional features with a server update").assertDoesNotExist()
        saveScreenshot("server-unknown-light")
    }

    @Test
    fun emptyUpgradeListIsHiddenForKnownServer() {
        compose.setContent {
            PaperlessScannerTheme(themeMode = ThemeMode.LIGHT) {
                Surface { Column { ServerSection("https://paperless.example.com", "3.0.0", {}, {}) } }
            }
        }
        compose.onNodeWithText("3.0.0").assertIsDisplayed()
        compose.onNodeWithText("Additional features with a server update").assertDoesNotExist()
        saveScreenshot("server-known-light")
    }

    @Test
    fun blockedImplementedFeatureExplainsMinimumVersion() {
        compose.setContent {
            PaperlessScannerTheme(themeMode = ThemeMode.LIGHT) {
                Surface { Column {
                ServerSection(
                    "https://paperless.example.com", "2.9.0", {}, {},
                    upgradeFeatures = listOf(ServerFeature("share_links", true, PaperlessServerVersion.parse("2.10.0"))),
                )
                } }
            }
        }
        compose.onNodeWithText("Additional features with a server update").assertIsDisplayed()
        compose.onNodeWithText("Share links").assertIsDisplayed()
        compose.onNodeWithText("Requires Paperless 2.10.0 or later").assertIsDisplayed()
        saveScreenshot("server-upgrade-light")
    }

    @Test
    fun updateHintRemainsReadableInDarkTheme() {
        compose.setContent {
            PaperlessScannerTheme(themeMode = ThemeMode.DARK) {
                Surface { Column {
                    ServerSection(
                        "https://paperless.example.com", "2.9.0", {}, {},
                        upgradeFeatures = listOf(ServerFeature("share_links", true, PaperlessServerVersion.parse("2.10.0"))),
                    )
                } }
            }
        }
        compose.onNodeWithText("Requires Paperless 2.10.0 or later").assertIsDisplayed()
        saveScreenshot("server-upgrade-dark")
    }

    private fun saveScreenshot(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
