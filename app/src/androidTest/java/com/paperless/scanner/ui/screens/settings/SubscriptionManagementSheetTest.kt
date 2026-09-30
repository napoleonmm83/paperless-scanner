package com.paperless.scanner.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.paperless.scanner.data.billing.SubscriptionInfo
import com.paperless.scanner.data.billing.SubscriptionInfoStatus
import java.util.Calendar
import org.junit.Rule
import org.junit.Test

class SubscriptionManagementSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun renewalDateAndSubscriptionDetailsRemainVisible() {
        val renewal = Calendar.getInstance().apply {
            clear()
            set(2024, Calendar.JANUARY, 2, 12, 0)
        }.timeInMillis
        compose.setContent {
            MaterialTheme {
                SubscriptionManagementSheet(
                    subscriptionInfo = SubscriptionInfo(
                        productId = "fixture-yearly",
                        productName = "Fixture Premium",
                        price = "CHF 29.99",
                        renewalDateMs = renewal,
                        status = SubscriptionInfoStatus.ACTIVE,
                        isMonthly = false,
                    ),
                    onDismiss = {},
                    onOpenGooglePlay = {},
                    onRestore = {},
                )
            }
        }
        compose.onNodeWithText("Fixture Premium").assertIsDisplayed()
        compose.onNodeWithText("CHF 29.99").assertIsDisplayed()
        compose.onNodeWithText("02.01.2024").assertIsDisplayed()
    }
}
