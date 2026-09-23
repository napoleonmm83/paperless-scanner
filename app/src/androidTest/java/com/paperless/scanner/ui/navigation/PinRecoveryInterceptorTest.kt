package com.paperless.scanner.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.paperless.scanner.data.network.ObservedCertHolder
import com.paperless.scanner.data.network.PinRecoveryCoordinator
import com.paperless.scanner.data.network.PinRecoveryPhase
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class PinRecoveryInterceptorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun corruptPinsOfferExplicitResetWithoutShowingFirstTrust() {
        val coordinator = mockk<PinRecoveryCoordinator>()
        every { coordinator.recoveryRequired } returns MutableStateFlow(true)
        every { coordinator.phase } returns MutableStateFlow(PinRecoveryPhase.NONE)
        every { coordinator.firstTrust } returns MutableStateFlow(null)
        coEvery { coordinator.resetCorruptedPins() } returns Unit
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Text("Recovery test host")
                PinRecoveryInterceptor(true, viewModel = PinRecoveryViewModel(coordinator, Dispatchers.Unconfined))
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Certificate trust needs recovery").assertIsDisplayed()
        compose.onNodeWithText("Reset certificate trust").assertIsDisplayed()
        compose.onNodeWithText("Trust this fingerprint").assertDoesNotExist()
        compose.onNodeWithText("Reset certificate trust").performClick()
        coVerify(exactly = 1) { coordinator.resetCorruptedPins() }
    }

    @Test
    fun firstTrustShowsExactHostAndFingerprint() {
        val coordinator = mockk<PinRecoveryCoordinator>()
        every { coordinator.recoveryRequired } returns MutableStateFlow(false)
        every { coordinator.phase } returns MutableStateFlow(PinRecoveryPhase.MANUAL_ENROLLMENT)
        every { coordinator.firstTrust } returns MutableStateFlow(
            ObservedCertHolder.FirstTrust("paperless.lan", "sha256/KNOWN")
        )
        every { coordinator.confirmFirstTrust("paperless.lan", "sha256/KNOWN") } returns true
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Text("Recovery test host")
                PinRecoveryInterceptor(true, viewModel = PinRecoveryViewModel(coordinator, Dispatchers.Unconfined))
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Verify server certificate").assertIsDisplayed()
        compose.onNodeWithText("paperless.lan", substring = true).assertIsDisplayed()
        compose.onNodeWithText("sha256/KNOWN").assertIsDisplayed()
        compose.onNodeWithText("Trust this fingerprint").assertIsDisplayed()
        compose.onNodeWithText("Trust this fingerprint").performClick()
        verify(exactly = 1) { coordinator.confirmFirstTrust("paperless.lan", "sha256/KNOWN") }
    }
}
