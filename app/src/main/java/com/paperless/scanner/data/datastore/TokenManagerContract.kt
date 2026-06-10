package com.paperless.scanner.data.datastore

/**
 * Test-double seam for [TokenManager] (#321): the pending-trash-delete surface consumed
 * by TrashDeleteWorker. The full TokenManager API (token/server-url storage, allowlist,
 * AppLock state) intentionally stays on the concrete class — see plan-02 (#320) for the
 * storage-failure taxonomy work.
 */
interface TokenManagerContract {
    fun getPendingTrashDeletesSync(): String?
    suspend fun removePendingTrashDelete(documentId: Int)
}
