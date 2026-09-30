package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.ServerCapabilityStore
import com.paperless.scanner.data.api.ServerRequestSession
import com.paperless.scanner.data.api.models.CreateShareLinkRequest
import com.paperless.scanner.data.api.models.ShareLinkResponse
import com.paperless.scanner.domain.model.DocumentShareLink
import com.paperless.scanner.domain.model.DocumentShareLinks
import com.paperless.scanner.domain.model.ShareFileVersion
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

class ShareLinkRepository @Inject constructor(
    private val api: PaperlessApi,
    private val capabilities: ServerCapabilityRepository,
    private val store: ServerCapabilityStore,
) {
    val capabilityState = capabilities.state
    val sessionGeneration = store.sessionGeneration

    suspend fun refreshCapabilities() = capabilities.refresh()

    private fun session(expectedGeneration: Long): ServerRequestSession {
        capabilities.requireFeature("share_links")
        return store.captureRequest(capabilityState.value.serverBase, expectedGeneration)
            ?: throw IOException("Server session changed")
    }

    private fun requireCurrent(session: ServerRequestSession) {
        if (!store.isCurrent(session)) throw IOException("Server session changed")
    }

    private suspend fun <T> inSession(expectedGeneration: Long, block: suspend (ServerRequestSession) -> T): Result<T> = try {
        val session = session(expectedGeneration)
        val value = block(session)
        requireCurrent(session)
        Result.success(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun load(documentId: Int, expectedGeneration: Long = store.snapshotGeneration()): Result<DocumentShareLinks> = inSession(expectedGeneration) { session ->
        require(documentId > 0)
        val metadata = api.getShareDocumentMetadata(session.serverBase.resolve("api/documents/$documentId/metadata/")!!.toString(), session)
        requireCurrent(session)
        val links = api.getDocumentShareLinks(session.serverBase.resolve("api/documents/$documentId/share_links/")!!.toString(), session)
        DocumentShareLinks(links.map { it.toDomain(session) }, metadata.hasArchiveVersion)
    }

    suspend fun create(documentId: Int, days: Int?, version: ShareFileVersion, expectedGeneration: Long = store.snapshotGeneration()): Result<DocumentShareLink> = inSession(expectedGeneration) { session ->
        require(documentId > 0)
        require(days == null || days in 1..365)
        if (version == ShareFileVersion.ARCHIVE) {
            val metadata = api.getShareDocumentMetadata(session.serverBase.resolve("api/documents/$documentId/metadata/")!!.toString(), session)
            requireCurrent(session)
            require(metadata.hasArchiveVersion) { "Archive version unavailable" }
        }
        val expiration = days?.let { Instant.now().plus(it.toLong(), ChronoUnit.DAYS).toString() }
        api.createShareLink(
            session.serverBase.resolve("api/share_links/")!!.toString(),
            CreateShareLinkRequest(documentId, expiration, version.apiValue), session,
        ).toDomain(session)
    }

    suspend fun delete(linkId: Int, expectedGeneration: Long = store.snapshotGeneration()): Result<Unit> = inSession(expectedGeneration) { session ->
        require(linkId > 0)
        val response = api.deleteShareLink(session.serverBase.resolve("api/share_links/$linkId/")!!.toString(), session)
        if (!response.isSuccessful) throw HttpException(response)
    }

    private fun ShareLinkResponse.toDomain(session: ServerRequestSession): DocumentShareLink {
        // A slug is one path segment, never a server-supplied arbitrary URL.
        require(id > 0 && slug.isNotBlank() && slug.length <= 128 && slug.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val url = session.serverBase.newBuilder().addPathSegment("share").addPathSegment(slug).build()
        return DocumentShareLink(id, created, expiration, url.toString())
    }
}
