package com.paperless.scanner.data.repository

/**
 * Test-double seam for [TrashRepository] (#321): the permanent-delete surface consumed
 * by TrashDeleteWorker.
 */
interface TrashRepositoryContract {
    suspend fun permanentlyDeleteDocument(documentId: Int): Result<Unit>
}
