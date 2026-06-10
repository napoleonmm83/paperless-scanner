package com.paperless.scanner.testing.fakes

import com.paperless.scanner.data.datastore.TokenManagerContract

/**
 * Typed fake for [TokenManagerContract] (#202/#321): set [pendingTrashDeletes] to the
 * raw "id:timestamp,id:timestamp" string the worker parses; removals are recorded in
 * [removedPendingTrashDeletes].
 */
class FakeTokenManager : TokenManagerContract {
    var pendingTrashDeletes: String? = null

    val removedPendingTrashDeletes = mutableListOf<Int>()

    override fun getPendingTrashDeletesSync(): String? = pendingTrashDeletes

    override suspend fun removePendingTrashDelete(documentId: Int) {
        removedPendingTrashDeletes += documentId
    }
}
