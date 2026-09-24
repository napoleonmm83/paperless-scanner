package com.paperless.scanner.ui.navigation

import com.paperless.scanner.data.network.PinRecoveryCoordinator
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PinRecoveryViewModelTest {
    @Test
    fun `manual introduction appears only after a successful reset in this session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val coordinator = mockk<PinRecoveryCoordinator>(relaxed = true)
            coEvery { coordinator.resetCorruptedPins() } returns Unit
            val viewModel = PinRecoveryViewModel(coordinator, dispatcher)
            assertFalse(viewModel.manualIntroVisible.value)

            viewModel.reset()
            advanceUntilIdle()

            assertTrue(viewModel.manualIntroVisible.value)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
