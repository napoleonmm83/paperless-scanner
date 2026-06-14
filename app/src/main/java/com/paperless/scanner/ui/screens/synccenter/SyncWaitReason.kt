package com.paperless.scanner.ui.screens.synccenter

/**
 * Why the upload queue is currently not draining — surfaced as a banner in the Sync Center so
 * the user knows exactly what is being waited on. Mirrors [com.paperless.scanner.worker.UploadWorker]'s
 * pre-checks and the WorkManager constraints applied to the upload work.
 */
enum class SyncWaitReason {
    /** Nothing is waiting: no pending/uploading work to drain. */
    NONE,
    OFFLINE,
    SERVER_UNREACHABLE,
    WAITING_FOR_WIFI,
    BATTERY_LOW,
    STORAGE_LOW,
    /** Work is present and unblocked — actively uploading / about to start. */
    UPLOADING,
}

/**
 * The single most relevant waiting reason for the upload queue. The order mirrors
 * [com.paperless.scanner.worker.UploadWorker]'s pre-checks so the banner always names the same
 * blocker the worker is actually deferring on: a missing internet connection blocks everything,
 * then the unmetered-only preference (the worker defers for a metered network before it even
 * contacts the server), then an unreachable server, then the device conditions (battery,
 * storage). Pure and side-effect free.
 *
 * @param hasWaitingWork whether at least one upload is PENDING or UPLOADING (FAILED items are
 *   not "waiting" — they need an explicit retry).
 */
internal fun computeWaitReason(
    hasWaitingWork: Boolean,
    online: Boolean,
    serverReachable: Boolean,
    unmeteredOnly: Boolean,
    unmetered: Boolean,
    batteryLow: Boolean,
    storageLow: Boolean,
): SyncWaitReason = when {
    !hasWaitingWork -> SyncWaitReason.NONE
    !online -> SyncWaitReason.OFFLINE
    unmeteredOnly && !unmetered -> SyncWaitReason.WAITING_FOR_WIFI
    !serverReachable -> SyncWaitReason.SERVER_UNREACHABLE
    batteryLow -> SyncWaitReason.BATTERY_LOW
    storageLow -> SyncWaitReason.STORAGE_LOW
    else -> SyncWaitReason.UPLOADING
}
