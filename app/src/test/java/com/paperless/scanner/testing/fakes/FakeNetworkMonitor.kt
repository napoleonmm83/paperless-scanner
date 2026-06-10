package com.paperless.scanner.testing.fakes

import com.paperless.scanner.data.network.NetworkMonitorContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Typed fake for [NetworkMonitorContract] (#239/#321): drive [online] to simulate
 * connectivity transitions instead of stubbing a relaxed mock.
 */
class FakeNetworkMonitor(initiallyOnline: Boolean = true) : NetworkMonitorContract {
    val online = MutableStateFlow(initiallyOnline)

    override val isOnline: StateFlow<Boolean> = online

    override fun hasValidatedInternet(): Boolean = online.value
}
