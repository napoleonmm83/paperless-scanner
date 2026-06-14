package com.paperless.scanner.ui.screens.synccenter

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the priority ordering of [computeWaitReason] — the logic behind the Sync Center
 * "what is it waiting for?" banner.
 */
class SyncWaitReasonTest {

    /** "Everything fine" defaults; each test overrides only the relevant blockers. */
    private fun reason(
        hasWaitingWork: Boolean = true,
        online: Boolean = true,
        serverReachable: Boolean = true,
        unmeteredOnly: Boolean = false,
        unmetered: Boolean = true,
        batteryLow: Boolean = false,
        storageLow: Boolean = false,
    ) = computeWaitReason(
        hasWaitingWork = hasWaitingWork,
        online = online,
        serverReachable = serverReachable,
        unmeteredOnly = unmeteredOnly,
        unmetered = unmetered,
        batteryLow = batteryLow,
        storageLow = storageLow,
    )

    @Test
    fun `no waiting work is NONE even with every blocker active`() {
        assertEquals(
            SyncWaitReason.NONE,
            reason(hasWaitingWork = false, online = false, serverReachable = false, batteryLow = true, storageLow = true),
        )
    }

    @Test
    fun `active work with no blockers is UPLOADING`() {
        assertEquals(SyncWaitReason.UPLOADING, reason())
    }

    @Test
    fun `offline has the highest priority`() {
        assertEquals(
            SyncWaitReason.OFFLINE,
            reason(online = false, serverReachable = false, unmeteredOnly = true, unmetered = false, batteryLow = true, storageLow = true),
        )
    }

    @Test
    fun `server unreachable wins once online`() {
        assertEquals(
            SyncWaitReason.SERVER_UNREACHABLE,
            reason(serverReachable = false, batteryLow = true),
        )
    }

    @Test
    fun `waiting for wifi when unmetered-only and on a metered network`() {
        assertEquals(
            SyncWaitReason.WAITING_FOR_WIFI,
            reason(unmeteredOnly = true, unmetered = false, batteryLow = true, storageLow = true),
        )
    }

    @Test
    fun `waiting for wifi outranks server unreachable to match the worker pre-check order`() {
        // UploadWorker defers for a metered network before it contacts the server, so the
        // banner must report WAITING_FOR_WIFI even when the server is (or appears) unreachable.
        assertEquals(
            SyncWaitReason.WAITING_FOR_WIFI,
            reason(serverReachable = false, unmeteredOnly = true, unmetered = false),
        )
    }

    @Test
    fun `unmetered-only on an unmetered network does not wait for wifi`() {
        assertEquals(SyncWaitReason.UPLOADING, reason(unmeteredOnly = true, unmetered = true))
    }

    @Test
    fun `metered network is ignored when the unmetered-only preference is off`() {
        assertEquals(SyncWaitReason.UPLOADING, reason(unmeteredOnly = false, unmetered = false))
    }

    @Test
    fun `battery low ranks below the network checks`() {
        assertEquals(SyncWaitReason.BATTERY_LOW, reason(batteryLow = true, storageLow = true))
    }

    @Test
    fun `storage low is the lowest-priority blocker`() {
        assertEquals(SyncWaitReason.STORAGE_LOW, reason(storageLow = true))
    }
}
