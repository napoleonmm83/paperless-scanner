package com.paperless.scanner.ui.screens.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class ScannedPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val pageNumber: Int,
    val rotation: Int = 0  // 0, 90, 180, 270
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
    private val authRepository: AuthRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
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

    fun rotatePage(pageId: String) {
        _uiState.update { state ->
            val updatedPages = state.pages.map { page ->
                if (page.id == pageId) {
                    page.copy(rotation = (page.rotation + 90) % 360)
                } else {
                    page
                }
            }
            state.copy(pages = updatedPages)
        }
    }

    fun clearPages() {
        _uiState.update { it.copy(pages = emptyList()) }
    }

    fun getPageUris(): List<Uri> = _uiState.value.pages.map { it.uri }

    fun getPages(): List<ScannedPage> = _uiState.value.pages

    /**
     * Returns URIs with rotation applied. Creates rotated copies for pages with rotation != 0.
     */
    suspend fun getRotatedPageUris(): List<Uri> = withContext(Dispatchers.IO) {
        _uiState.value.pages.map { page ->
            if (page.rotation == 0) {
                page.uri
            } else {
                rotateAndSaveImage(page.uri, page.rotation)
            }
        }
    }

    private fun rotateAndSaveImage(uri: Uri, rotation: Int): Uri {
        // Load bitmap
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return uri
        val originalBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        // Rotate bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotatedBitmap = Bitmap.createBitmap(
            originalBitmap, 0, 0,
            originalBitmap.width, originalBitmap.height,
            matrix, true
        )

        // Save to cache
        val rotatedFile = File(context.cacheDir, "rotated_${System.currentTimeMillis()}.jpg")
        FileOutputStream(rotatedFile).use { out ->
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        // Cleanup
        if (rotatedBitmap != originalBitmap) {
            originalBitmap.recycle()
        }
        rotatedBitmap.recycle()

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            rotatedFile
        )
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}
