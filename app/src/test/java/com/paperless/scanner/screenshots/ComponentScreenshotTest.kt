package com.paperless.scanner.screenshots

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paperless.scanner.ui.theme.PaperlessScannerTheme
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests using Paparazzi.
 *
 * These tests generate golden images of UI components.
 * Run: ./gradlew recordPaparazziDebug (to record new golden images)
 * Run: ./gradlew verifyPaparazziDebug (to verify against golden images)
 *
 * Golden images are stored in: app/src/test/snapshots/
 *
 * Note: Tests are @Ignored by default until golden images are recorded.
 * Remove @Ignore after running recordPaparazziDebug for the first time.
 */
class ComponentScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.Light.NoActionBar"
    )

    @Test
    @Ignore("Run './gradlew recordPaparazziDebug' first to create golden images")
    fun primaryButton_default() {
        paparazzi.snapshot {
            PaperlessScannerTheme {
                Surface {
                    Button(
                        onClick = {},
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("Upload Document")
                    }
                }
            }
        }
    }

    @Test
    @Ignore("Run './gradlew recordPaparazziDebug' first to create golden images")
    fun card_withContent() {
        paparazzi.snapshot {
            PaperlessScannerTheme {
                Surface {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Document Title",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}
