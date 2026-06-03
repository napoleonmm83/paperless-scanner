package com.paperless.scanner.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.network.CertificatePinStore
import com.paperless.scanner.data.network.ObservedCertHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-wide handler for certificate-pin mismatches that occur DURING a session
 * (Issue #249). The login/setup flow already re-trusts via
 * [com.paperless.scanner.ui.screens.login.LoginViewModel.acceptCertificateChange];
 * this hosts the same choreography for any other screen so a renewed/changed
 * server certificate surfaces the blocking re-trust dialog instead of a generic
 * request failure.
 *
 * Mismatches are recorded app-wide by
 * [com.paperless.scanner.data.network.CertificatePinningInterceptor] into
 * [ObservedCertHolder]; [pendingMismatch] makes the most recent one observable so
 * [CertificateReTrustInterceptor] can render the dialog over any screen.
 */
@HiltViewModel
class CertReTrustViewModel @Inject constructor(
    private val certificatePinStore: CertificatePinStore,
    private val observedCertHolder: ObservedCertHolder,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** The pending app-wide certificate mismatch to surface, or null. */
    val pendingMismatch: StateFlow<ObservedCertHolder.Mismatch?> = observedCertHolder.latest

    /**
     * Re-trust: pin the certificate the user actually saw ([approvedPin], the
     * `actualPin` of the displayed mismatch) so the next connection to [host]
     * succeeds and the user can continue without re-running setup.
     *
     * We re-read the live mismatch from [ObservedCertHolder] and only pin when it
     * STILL matches [approvedPin]. This guards two cases:
     *  - A concurrent failing request may have recorded a DIFFERENT certificate for
     *    this host after the dialog rendered (load balancer / MITM). Pinning the
     *    current holder value blindly would trust a cert the user never approved.
     *  - A null result means the mismatch was already resolved (a race). Unlike the
     *    login flow this dialog is driven purely by in-memory state (it can never
     *    show after process death), so we intentionally do NOT drop the pin — a
     *    `removePin` here would let the next connection silently TOFU-trust whatever
     *    cert is presented. On any mismatch the next connection re-records and
     *    re-prompts with the correct fingerprint.
     */
    fun acceptCertificateChange(host: String, approvedPin: String) {
        // Pin-store mutations write through to EncryptedSharedPreferences (disk +
        // AES), so run them off the main thread to avoid any ANR on slow storage.
        viewModelScope.launch(ioDispatcher) {
            // Atomic compare-and-remove: only pin (and clear the dialog) when the
            // live mismatch still matches the fingerprint the user approved. A
            // newer/different cert recorded mid-dialog is left in place to re-prompt.
            if (observedCertHolder.consumeIfMatches(host, approvedPin) != null) {
                certificatePinStore.replacePin(host, approvedPin)
            }
        }
    }

    /**
     * Decline: dismiss the dialog without trusting the new certificate. The pin is
     * left unchanged, so connections keep failing until the cert reverts or the
     * user re-trusts on the next attempt (the interceptor re-records the mismatch).
     */
    fun declineCertificateChange(host: String) {
        observedCertHolder.consume(host)
    }
}
