package com.paperless.scanner.ui.screens.scan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScannedPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val pageNumber: Int
)

data class ScanUiState(
    val pages: List<ScannedPage> = emptyList(),
    val isProcessing: Boolean = false
) {
    val pageCount: Int get() = pages.size
    val hasPages: Boolean get() = pages.isNotEmpty()
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun addPages(uris: List<Uri>) {
        _uiState.update { state ->
            val startIndex = state.pageCount
            val newPages = uris.mapIndexed { index, uri ->
                ScannedPage(
                    uri = uri,
                    pageNumber = startIndex + index + 1
                )
            }
            state.copy(pages = state.pages + newPages)
        }
    }

    fun removePage(pageId: String) {
        _uiState.update { state ->
            val filteredPages = state.pages.filter { it.id != pageId }
            val renumberedPages = filteredPages.mapIndexed { index, page ->
                page.copy(pageNumber = index + 1)
            }
            state.copy(pages = renumberedPages)
        }
    }

    fun movePage(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            if (fromIndex < 0 || fromIndex >= state.pageCount ||
                toIndex < 0 || toIndex >= state.pageCount) {
                return@update state
            }

            val mutablePages = state.pages.toMutableList()
            val page = mutablePages.removeAt(fromIndex)
            mutablePages.add(toIndex, page)

            val renumberedPages = mutablePages.mapIndexed { index, p ->
                p.copy(pageNumber = index + 1)
            }
            state.copy(pages = renumberedPages)
        }
    }

    fun clearPages() {
        _uiState.update { it.copy(pages = emptyList()) }
    }

    fun getPageUris(): List<Uri> = _uiState.value.pages.map { it.uri }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}
