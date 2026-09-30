package com.paperless.scanner.data.api

import com.paperless.scanner.domain.model.PaperlessServerVersion
import com.paperless.scanner.domain.model.ServerCapabilityState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Request provenance without credentials. An account change invalidates every old session. */
data class ServerRequestSession internal constructor(val serverBase: HttpUrl, val generation: Long)

/** In-memory capability evidence, scoped to a server and a credential generation. */
@Singleton
class ServerCapabilityStore @Inject constructor() {
    private val mutableState = MutableStateFlow(ServerCapabilityState())
    val state: StateFlow<ServerCapabilityState> = mutableState.asStateFlow()
    private var generation = 0L
    private var initialized = false
    private var active = false
    private var base: HttpUrl? = null

    @Synchronized
    fun beginCredentialChange(): Long {
        generation++
        initialized = true
        active = false
        base = null
        mutableState.value = ServerCapabilityState()
        return generation
    }

    @Synchronized
    fun finishCredentialChange(generation: Long, serverUrl: String?) {
        if (generation != this.generation) return
        initialized = true
        base = normalizeBase(serverUrl)
        active = base != null
        mutableState.value = ServerCapabilityState(serverBase = base?.toString())
    }

    @Synchronized
    fun snapshotGeneration(): Long = generation

    @Synchronized
    fun captureRequest(serverUrl: String?, generation: Long): ServerRequestSession? {
        if (generation != this.generation) return null
        val requestedBase = normalizeBase(serverUrl) ?: return null
        if (!initialized) {
            initialized = true
            base = requestedBase
            active = true
            mutableState.value = ServerCapabilityState(serverBase = requestedBase.toString())
        }
        if (!active || base != requestedBase) return null
        return ServerRequestSession(requestedBase, generation)
    }

    @Synchronized
    fun observe(session: ServerRequestSession, url: HttpUrl, rawVersion: String?, rawApiVersion: String?) {
        val currentBase = base ?: return
        if (!active || session.generation != generation || session.serverBase != currentBase) return
        if (url.scheme != currentBase.scheme || url.host != currentBase.host || url.port != currentBase.port ||
            !url.encodedPath.startsWith(currentBase.encodedPath)) return
        val displayVersion = rawVersion?.trim()?.takeIf { value ->
            value.length in 1..128 && value.none { it.isISOControl() }
        }
        val apiVersion = rawApiVersion?.trim()?.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
            ?.toIntOrNull()?.takeIf { it > 0 }
        mutableState.value = ServerCapabilityState(
            serverVersion = PaperlessServerVersion.parse(displayVersion),
            displayVersion = displayVersion,
            apiVersion = apiVersion,
            serverBase = currentBase.toString()
        )
    }

    private fun normalizeBase(value: String?): HttpUrl? {
        val url = value?.trim()?.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        return if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
    }
}
