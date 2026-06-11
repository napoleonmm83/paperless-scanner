package com.paperless.scanner.data.network

import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [NetworkMonitor.hasUsableUplink] — the single capability check behind
 * checkOnlineStatus / the isOnline StateFlow / upload gating (#364).
 *
 * The dead-VPN case is a real device finding: Cloudflare 1.1.1.1 in airplane mode
 * keeps its tun up and advertises INTERNET|VALIDATED with transports = [VPN] only,
 * which made the app believe it was online while nothing was reachable.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkMonitorUplinkTest {

    private fun caps(
        internet: Boolean = true,
        validated: Boolean = true,
        transports: Set<Int> = setOf(NetworkCapabilities.TRANSPORT_WIFI),
    ): NetworkCapabilities {
        val capabilities = mockk<NetworkCapabilities>()
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns internet
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
        every { capabilities.hasTransport(any()) } answers { firstArg<Int>() in transports }
        return capabilities
    }

    @Test
    fun `validated wifi is online`() {
        assertTrue(NetworkMonitor.hasUsableUplink(caps(transports = setOf(NetworkCapabilities.TRANSPORT_WIFI))))
    }

    @Test
    fun `validated cellular is online`() {
        assertTrue(NetworkMonitor.hasUsableUplink(caps(transports = setOf(NetworkCapabilities.TRANSPORT_CELLULAR))))
    }

    @Test
    fun `unvalidated network is offline`() {
        assertFalse(NetworkMonitor.hasUsableUplink(caps(validated = false)))
    }

    @Test
    fun `network without internet capability is offline`() {
        assertFalse(NetworkMonitor.hasUsableUplink(caps(internet = false)))
    }

    @Test
    fun `dead VPN without underlying transport is offline`() {
        // Exact device-observed state: INTERNET|VALIDATED claimed, transports = [VPN] only.
        assertFalse(NetworkMonitor.hasUsableUplink(caps(transports = setOf(NetworkCapabilities.TRANSPORT_VPN))))
    }

    @Test
    fun `VPN over wifi is online`() {
        assertTrue(
            NetworkMonitor.hasUsableUplink(
                caps(transports = setOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI))
            )
        )
    }

    @Test
    fun `VPN over cellular is online`() {
        assertTrue(
            NetworkMonitor.hasUsableUplink(
                caps(transports = setOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_CELLULAR))
            )
        )
    }

    @Test
    fun `VPN over USB reverse-tethering is online`() {
        // codex P2: API 31+ exposes TRANSPORT_USB; a VPN riding on it must not be
        // misclassified as a dead VPN.
        assertTrue(
            NetworkMonitor.hasUsableUplink(
                caps(transports = setOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_USB))
            )
        )
    }
}
