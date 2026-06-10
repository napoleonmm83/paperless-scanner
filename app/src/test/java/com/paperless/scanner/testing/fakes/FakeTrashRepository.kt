package com.paperless.scanner.testing.fakes

import com.paperless.scanner.data.repository.TrashRepositoryContract

/**
 * Typed fake for [TrashRepositoryContract] (#202/#321): configure per-document results
 * via [deleteResults] (fallback [defaultDeleteResult]); every attempted delete is
 * recorded in [deletedDocumentIds].
 */
class FakeTrashRepository : TrashRepositoryContract {
    val deleteResults = mutableMapOf<Int, Result<Unit>>()
    var defaultDeleteResult: Result<Unit> = Result.success(Unit)

    val deletedDocumentIds = mutableListOf<Int>()

    override suspend fun permanentlyDeleteDocument(documentId: Int): Result<Unit> {
        deletedDocumentIds += documentId
        return deleteResults[documentId] ?: defaultDeleteResult
    }
}
