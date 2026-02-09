package com.paperless.scanner.ui.components.documentlist

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.paperless.scanner.R
import com.paperless.scanner.util.ThumbnailUrlBuilder

/**
 * DocumentThumbnail - Reusable thumbnail component for document previews.
 *
 * Loads thumbnails from Paperless-ngx API: `/api/documents/{id}/thumb/`
 * Authentication handled globally via Coil ImageLoader (see AppModule.kt).
 *
 * @param documentId Document ID for thumbnail URL construction
 * @param serverUrl Paperless-ngx server URL
 * @param showThumbnails User preference to show/hide thumbnails
 * @param modifier Optional modifier for the thumbnail box
 */
@Composable
fun DocumentThumbnail(
    documentId: Int,
    serverUrl: String,
    showThumbnails: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val thumbnailUrl = if (showThumbnails && serverUrl.isNotBlank()) {
            ThumbnailUrlBuilder.buildThumbnailUrl(
                serverUrl = serverUrl,
                documentId = documentId
            )
        } else null

        if (thumbnailUrl != null) {
            val context = LocalContext.current
            val painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .build()
            )

            when (val state = painter.state) {
                is AsyncImagePainter.State.Error -> {
                    Log.e(
                        "DocumentThumbnail",
                        "Failed to load doc $documentId from $thumbnailUrl: ${state.result.throwable.message}",
                        state.result.throwable
                    )
                    Icon(
                        imageVector = Icons.Filled.BrokenImage,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                else -> {
                    Image(
                        painter = painter,
                        contentDescription = stringResource(R.string.cd_document_thumbnail),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        } else {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = stringResource(R.string.cd_document_thumbnail),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
