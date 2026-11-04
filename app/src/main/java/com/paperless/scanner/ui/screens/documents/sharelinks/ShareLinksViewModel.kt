package com.paperless.scanner.ui.screens.documents.sharelinks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.repository.ShareLinkRepository
import com.paperless.scanner.domain.model.DocumentShareLink
import com.paperless.scanner.domain.model.FeatureStatus
import com.paperless.scanner.domain.model.ServerFeatureCatalog
import com.paperless.scanner.domain.model.ShareFileVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareLinksUiState(
    val availability: FeatureStatus = FeatureStatus.UNKNOWN,
    val links: List<DocumentShareLink> = emptyList(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val hasArchiveVersion: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class ShareLinksViewModel @Inject constructor(private val repository: ShareLinkRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ShareLinksUiState())
    val state = mutableState.asStateFlow()
    private var documentId = 0
    private var generation = repository.sessionGeneration.value
    private var job: Job? = null
    private var operation = 0L

    init {
        viewModelScope.launch {
            combine(repository.capabilityState, repository.sessionGeneration) { capabilities, generation ->
                ServerFeatureCatalog.features.first { it.id == "share_links" }.evaluate(capabilities.serverVersion) to generation
            }.collect { (availability, currentGeneration) ->
                if (generation != currentGeneration) {
                    generation = currentGeneration
                    operation++
                    job?.cancel()
                    mutableState.value = ShareLinksUiState(availability = availability)
                } else {
                    mutableState.update { it.copy(availability = availability) }
                }
            }
        }
        viewModelScope.launch { repository.refreshCapabilities() }
    }

    fun open(documentId: Int) {
        if (documentId <= 0) return
        val operationGeneration = generation
        if (operationGeneration != repository.sessionGeneration.value) return
        this.documentId = documentId
        job?.cancel()
        val currentOperation = ++operation
        mutableState.update { ShareLinksUiState(availability = it.availability, busy = true) }
        job = viewModelScope.launch {
            if (operationGeneration != repository.sessionGeneration.value || currentOperation != operation) return@launch
            repository.load(documentId, operationGeneration).fold(
                onSuccess = { result ->
                    if (currentOperation == operation && operationGeneration == repository.sessionGeneration.value) {
                        mutableState.update { it.copy(links = result.links, hasArchiveVersion = result.hasArchiveVersion, loaded = true, busy = false) }
                    }
                },
                onFailure = { error ->
                    if (currentOperation == operation && operationGeneration == repository.sessionGeneration.value) {
                        mutableState.update { it.copy(busy = false, error = error) }
                    }
                },
            )
        }
    }

    fun create(days: Int?, version: ShareFileVersion) {
        val operationGeneration = generation
        val state = mutableState.value
        if (operationGeneration != repository.sessionGeneration.value || state.busy || !state.loaded || state.availability != FeatureStatus.AVAILABLE) return
        if (version == ShareFileVersion.ARCHIVE && !state.hasArchiveVersion) return
        val currentDocument = documentId
        val currentOperation = ++operation
        mutableState.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            if (operationGeneration != repository.sessionGeneration.value || currentOperation != operation) return@launch
            repository.create(currentDocument, days, version, operationGeneration).fold(
                onSuccess = { link ->
                    if (currentOperation == operation && operationGeneration == repository.sessionGeneration.value) {
                        mutableState.update { it.copy(links = listOf(link) + it.links, busy = false) }
                    }
                },
                onFailure = { error ->
                    if (currentOperation == operation && operationGeneration == repository.sessionGeneration.value) mutableState.update { it.copy(busy = false, error = error) }
                },
            )
        }
    }

    fun revoke(id: Int) {
        val operationGeneration = generation
        if (operationGeneration != repository.sessionGeneration.value || mutableState.value.busy || urlFor(id) == null) return
        val currentOperation = ++operation
        mutableState.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            if (operationGeneration != repository.sessionGeneration.value || currentOperation != operation) return@launch
            repository.delete(id, operationGeneration).fold(
                onSuccess = {
                    if (currentOperation == operation && operationGeneration == repository.sessionGeneration.value) mutableState.update { it.copy(links = it.links.filterNot { link -> link.id == id }, busy = false) }
                },
                onFailure = { error ->
                    if (currentOperation == operation && operationGeneration == repository.sessionGeneration.value) mutableState.update { it.copy(busy = false, error = error) }
                },
            )
        }
    }

    fun urlFor(id: Int): String? = if (generation == repository.sessionGeneration.value && mutableState.value.availability == FeatureStatus.AVAILABLE) {
        mutableState.value.links.firstOrNull { it.id == id }?.url
    } else null
}
