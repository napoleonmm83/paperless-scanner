package com.paperless.scanner.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.network.ObservedCertHolder
import com.paperless.scanner.data.network.PinRecoveryCoordinator
import com.paperless.scanner.data.network.PinRecoveryPhase
import com.paperless.scanner.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PinRecoveryViewModel @Inject constructor(
    private val coordinator: PinRecoveryCoordinator,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    val recoveryRequired: StateFlow<Boolean> = coordinator.recoveryRequired
    val phase: StateFlow<PinRecoveryPhase> = coordinator.phase
    val firstTrust: StateFlow<ObservedCertHolder.FirstTrust?> = coordinator.firstTrust
    val busy = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    val manualIntroVisible = MutableStateFlow(false)

    fun reset() {
        if (!busy.compareAndSet(false, true)) return
        viewModelScope.launch(ioDispatcher) {
            try {
                failed.value = false
                coordinator.resetCorruptedPins()
                manualIntroVisible.value = true
            } catch (e: Exception) {
                AppLogger.e("PinRecoveryViewModel", "Certificate pin reset failed", e)
                failed.value = true
            } finally {
                busy.value = false
            }
        }
    }

    fun confirm(host: String, pin: String) {
        if (!busy.compareAndSet(false, true)) return
        viewModelScope.launch(ioDispatcher) {
            try {
                failed.value = false
                if (!coordinator.confirmFirstTrust(host, pin)) failed.value = true
            } catch (e: Exception) {
                AppLogger.e("PinRecoveryViewModel", "Certificate pin confirmation failed", e)
                failed.value = true
            } finally {
                busy.value = false
            }
        }
    }

    fun decline(host: String, pin: String) = coordinator.declineFirstTrust(host, pin)
    fun dismissIntro() { manualIntroVisible.value = false }
}
