package com.paperless.scanner.ui.components.documentlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.paperless.scanner.R
import com.paperless.scanner.util.ThumbnailUrlBuilder

/**
 * DocumentThumbnail - Reusable thumbnail component for document previews.
 *
 * **FEATURES:**
 * - Authenticated thumbnail loading via Coil 3 ImageLoader (configured globally)
 * - Automatic fallback to generic document icon when thumbnails are disabled
 * - Consistent styling (48.dp box, 12.dp corner radius, surfaceVariant background)
 * - Uses global ThumbnailUrlBuilder for URL construction
 *
 * **THUMBNAIL SYSTEM:**
 * - Thumbnails are loaded from Paperless-ngx API: `/api/documents/{id}/thumb/`
 * - Authentication handled globally via Coil ImageLoader (see AppModule.kt)
 * - 250MB disk cache for fast repeated loads
 * - SSL/TLS support for self-signed certificates
 *
 * **FALLBACK BEHAVIOR:**
 * - If showThumbnails = false: Shows generic document icon
 * - If serverUrl is blank: Shows generic document icon
 * - If thumbnail load fails: Coil handles error state (shows placeholder)
 *
 * **USAGE:**
 * ```kotlin
 * DocumentThumbnail(
 *     documentId = 123,
 *     serverUrl = "https://paperless.example.com",
 *     showThumbnails = true
 * )
 * ```
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
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = stringResource(R.string.cd_document_thumbnail),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
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
