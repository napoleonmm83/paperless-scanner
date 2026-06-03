package com.paperless.scanner.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.ui.components.CertificateChangedDialog

/**
 * App-wide interceptor that surfaces the blocking certificate re-trust dialog when
 * a pinned server certificate changes DURING a session (Issue #249), mirroring the
 * [AppLockNavigationInterceptor] pattern.
 *
 * The login/setup flow (SimplifiedSetupScreen) already renders this dialog from its
 * own `LoginUiState.CertChanged`, so [enabled] is false on those routes to avoid a
 * duplicate dialog; it is also false while the app is locked, so a mismatch
 * recorded by a background worker cannot be re-trusted before the user unlocks.
 */
@Composable
fun CertificateReTrustInterceptor(
    enabled: Boolean,
    viewModel: CertReTrustViewModel = hiltViewModel(),
) {
    val mismatch by viewModel.pendingMismatch.collectAsState()
    if (enabled) {
        mismatch?.let { m ->
            CertificateChangedDialog(
                host = m.host,
                expectedPin = m.expectedPin,
                actualPin = m.actualPin,
                onReTrust = { viewModel.acceptCertificateChange(m.host, m.actualPin) },
                onCancel = { viewModel.declineCertificateChange(m.host) },
            )
        }
    }
}
