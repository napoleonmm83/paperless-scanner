package com.paperless.scanner.ui.screens.pdfviewer

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed class PdfViewerUiState {
    data object Idle : PdfViewerUiState()
    data class Downloading(val progress: Float = 0f) : PdfViewerUiState()
    data class Viewing(val pdfFile: File, val currentPage: Int = 0, val totalPages: Int = 0, val isPdf: Boolean = true) : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
}

@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val documentRepository: DocumentRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val documentId: Int = savedStateHandle.get<String>("documentId")?.toIntOrNull() ?: 0
    val documentTitle: String = savedStateHandle.get<String>("documentTitle") ?: "Dokument"

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Idle)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    init {
        downloadDocument()
    }

    fun downloadDocument() {
        viewModelScope.launch {
            _uiState.update { PdfViewerUiState.Downloading(0f) }

            documentRepository.downloadDocument(
                documentId = documentId,
                onProgress = { progress ->
                    _uiState.update { PdfViewerUiState.Downloading(progress) }
                }
            ).onSuccess { file ->
                // Detect file type from extension or content
                val isPdf = file.extension.lowercase() == "pdf" || isPdfFile(file)
                _uiState.update { PdfViewerUiState.Viewing(file, isPdf = isPdf) }
            }.onFailure { error ->
                _uiState.update {
                    PdfViewerUiState.Error(
                        error.message ?: "Fehler beim Herunterladen"
                    )
                }
            }
        }
    }

    private fun isPdfFile(file: File): Boolean {
        return try {
            // Check PDF magic bytes: %PDF
            file.inputStream().use { input ->
                val header = ByteArray(4)
                input.read(header)
                header.contentEquals(byteArrayOf(0x25, 0x50, 0x44, 0x46))
            }
        } catch (e: Exception) {
            false
        }
    }

    fun updatePageInfo(currentPage: Int, totalPages: Int) {
        _uiState.update { state ->
            if (state is PdfViewerUiState.Viewing) {
                state.copy(currentPage = currentPage, totalPages = totalPages)
            } else {
                state
            }
        }
    }

    fun shareDocument() {
        val state = _uiState.value
        if (state !is PdfViewerUiState.Viewing) return

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                state.pdfFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, documentTitle)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(shareIntent, "Teilen via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            // Handle share error silently or show toast
        }
    }

    fun openInExternalApp() {
        val state = _uiState.value
        if (state !is PdfViewerUiState.Viewing) return

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                state.pdfFile
            )

            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(openIntent)
        } catch (e: Exception) {
            _uiState.update {
                PdfViewerUiState.Error("Keine App zum Öffnen gefunden")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up PDF file when ViewModel is destroyed
        val state = _uiState.value
        if (state is PdfViewerUiState.Viewing) {
            state.pdfFile.delete()
        }
    }
}
