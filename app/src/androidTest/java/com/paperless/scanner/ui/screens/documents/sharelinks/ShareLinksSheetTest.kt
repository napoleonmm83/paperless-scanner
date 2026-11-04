package com.paperless.scanner.ui.screens.documents.sharelinks

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.test.espresso.Espresso
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.paperless.scanner.domain.model.DocumentShareLink
import com.paperless.scanner.domain.model.FeatureStatus
import com.paperless.scanner.domain.model.ShareFileVersion
import com.paperless.scanner.ui.theme.PaperlessScannerTheme
import com.paperless.scanner.ui.theme.ThemeMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ShareLinksSheetTest {
    @get:Rule val compose = createComposeRule()
    private val link = DocumentShareLink(8, "2026-09-30T10:00:00Z", null, "https://paperless.example/share/testslug")

    @Test fun loadingSheetCanExpandWhileDismissalIsBlocked() {
        val busy = mutableStateOf(true)
        var closed = 0
        compose.setContent {
            PaperlessScannerTheme {
                ShareLinksDialog(ShareLinksUiState(FeatureStatus.AVAILABLE, busy = busy.value), {}, { _, _ -> }, {}, {}, {}, { closed++ })
            }
        }
        compose.onNodeWithText("Anyone with this link can access the file without signing in.").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("Anyone with this link can access the file without signing in.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, closed); busy.value = false }
        compose.onNodeWithText("Anyone with this link can access the file without signing in.").assertIsDisplayed()
        Espresso.pressBack()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun backCannotHideAnInFlightCreationAfterSheetWasAlreadyOpen() {
        val busy = mutableStateOf(false)
        var closed = 0
        compose.setContent {
            PaperlessScannerTheme {
                ShareLinksDialog(ShareLinksUiState(FeatureStatus.AVAILABLE, loaded = true, busy = busy.value), {}, { _, _ -> }, {}, {}, {}, { closed++ })
            }
        }
        compose.onNodeWithText("Anyone with this link can access the file without signing in.").assertIsDisplayed()
        compose.runOnIdle { busy.value = true }
        Espresso.pressBack()
        compose.onNodeWithText("Anyone with this link can access the file without signing in.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, closed) }
    }

    @Test fun creationIsExplicitAndDefaultsToSevenDays() {
        var created: Pair<Int?, ShareFileVersion>? = null
        compose.setContent {
            PaperlessScannerTheme(themeMode = ThemeMode.LIGHT) {
                Surface(Modifier.width(320.dp)) {
                    ShareLinksSheet(ShareLinksUiState(FeatureStatus.AVAILABLE, loaded = true), {}, { days, version -> created = days to version }, {}, {}, {}, {})
                }
            }
        }
        assertNull(created)
        compose.onNodeWithText("7 days").assertIsSelected()
        compose.onNodeWithText("Archived PDF").assertDoesNotExist()
        screenshot("share-links-light-320")
        compose.onNodeWithTag("share-link-create").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(7 to ShareFileVersion.ORIGINAL, created) }
    }

    @Test fun expiryAndArchiveSelectionReachCreateAction() {
        var created: Pair<Int?, ShareFileVersion>? = null
        compose.setContent {
            PaperlessScannerTheme(themeMode = ThemeMode.DARK) {
                Surface {
                    ShareLinksSheet(ShareLinksUiState(FeatureStatus.AVAILABLE, loaded = true, hasArchiveVersion = true), {}, { days, version -> created = days to version }, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("30 days").performScrollTo().performClick()
        compose.onNodeWithText("Archived PDF").assertIsSelected()
        screenshot("share-links-dark")
        compose.onNodeWithTag("share-link-create").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(30 to ShareFileVersion.ARCHIVE, created) }
    }

    @Test fun linkActionsAndConfirmedRevokeUseCorrectId() {
        var copied: Int? = null
        var shared: Int? = null
        var revoked: Int? = null
        compose.setContent {
            PaperlessScannerTheme(themeMode = ThemeMode.LIGHT) {
                Surface {
                    ShareLinksSheet(ShareLinksUiState(FeatureStatus.AVAILABLE, listOf(link), loaded = true), {}, { _, _ -> }, { copied = it }, { shared = it }, { revoked = it }, {})
                }
            }
        }
        compose.onNodeWithText("Copy").performScrollTo().performClick()
        compose.onNodeWithText("Share", substring = false).performClick()
        compose.onNodeWithText("Revoke", substring = false).performClick()
        compose.onNodeWithText("Revoke share link?").assertIsDisplayed()
        assertNull(revoked)
        compose.onNodeWithTag("share-link-revoke-confirm").performClick()
        compose.runOnIdle {
            assertEquals(8, copied)
            assertEquals(8, shared)
            assertEquals(8, revoked)
        }
    }

    @Test fun busyStatePreventsDuplicateCreation() {
        compose.setContent {
            PaperlessScannerTheme {
                Surface { ShareLinksSheet(ShareLinksUiState(FeatureStatus.AVAILABLE, loaded = true, busy = true), {}, { _, _ -> }, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("share-link-create").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Close").performScrollTo().assertIsNotEnabled()
    }

    private fun screenshot(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
