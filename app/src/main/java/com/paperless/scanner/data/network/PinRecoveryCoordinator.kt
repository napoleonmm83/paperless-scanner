package com.paperless.scanner.data.network

import com.paperless.scanner.data.datastore.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class PinRecoveryPhase { NONE, RESET_PENDING, MANUAL_ENROLLMENT }

/** Persists the recovery gate separately from the encrypted pins. */
@Singleton
class PinRecoveryCoordinator @Inject constructor(
    private val tokenManager: TokenManager,
    private val pinStore: CertificatePinStore,
    private val observedCertHolder: ObservedCertHolder,
) {
    private val mutex = Mutex()
    private val _phase = MutableStateFlow(
        try {
            when (tokenManager.getPinRecoveryPhase()) {
                "none" -> PinRecoveryPhase.NONE
                "manual_enrollment" -> PinRecoveryPhase.MANUAL_ENROLLMENT
                else -> PinRecoveryPhase.RESET_PENDING
            }
        } catch (_: Exception) {
            PinRecoveryPhase.RESET_PENDING
        }
    )
    val phase: StateFlow<PinRecoveryPhase> = _phase

    val recoveryRequired: StateFlow<Boolean> = pinStore.recoveryRequired

    fun requireNoPendingRecovery(host: String) {
        if (pinStore.recoveryRequired.value || _phase.value == PinRecoveryPhase.RESET_PENDING) {
            throw CertificatePinRecoveryRequiredException(host)
        }
    }

    fun needsNetworkForFirstTrust(host: String): Boolean {
        requireNoPendingRecovery(host)
        return _phase.value == PinRecoveryPhase.MANUAL_ENROLLMENT && pinStore.getPin(host) == null
    }

    suspend fun resetCorruptedPins() = mutex.withLock {
        if (!pinStore.recoveryRequired.value && _phase.value != PinRecoveryPhase.RESET_PENDING) {
            throw IllegalStateException("No pending pin recovery")
        }
        tokenManager.beginPinRecovery()
        _phase.value = PinRecoveryPhase.RESET_PENDING
        pinStore.resetCorruptedStore()
        tokenManager.finishPinRecovery()
        _phase.value = PinRecoveryPhase.MANUAL_ENROLLMENT
    }

    @Synchronized
    fun requireNetworkAccess(host: String, storedPin: String?, presentedPin: String) {
        requireNoPendingRecovery(host)
        if (_phase.value == PinRecoveryPhase.MANUAL_ENROLLMENT &&
            storedPin == null && pinStore.getPin(host) == null) {
            observedCertHolder.recordFirstTrust(host, presentedPin)
            throw CertificateFirstTrustRequiredException(host, presentedPin)
        }
    }

    @Synchronized
    fun confirmFirstTrust(host: String, approvedPin: String): Boolean {
        if (_phase.value != PinRecoveryPhase.MANUAL_ENROLLMENT) return false
        if (observedCertHolder.peekFirstTrust(host)?.presentedPin != approvedPin) return false
        if (!pinStore.setPinIfAbsent(host, approvedPin) && pinStore.getPin(host) != approvedPin) {
            return false
        }
        return observedCertHolder.consumeFirstTrustIfMatches(host, approvedPin)
    }

    @Synchronized
    fun declineFirstTrust(host: String, pin: String) {
        observedCertHolder.consumeFirstTrustIfMatches(host, pin)
    }

    val firstTrust: StateFlow<ObservedCertHolder.FirstTrust?> = observedCertHolder.firstTrust
}

class CertificatePinRecoveryRequiredException(val host: String) : IOException("Certificate pin recovery required")

class CertificateFirstTrustRequiredException(
    val host: String,
    val presentedPin: String,
) : IOException("Certificate fingerprint confirmation required")
