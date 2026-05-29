package com.paperless.scanner.ui.screens.documents

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.paperless.scanner.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsTabContent(
    uiState: DocumentDetailUiState,
    context: Context,
    onOpenPdf: (Int, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        // Thumbnail
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.thumbnailUrl != null && uiState.authToken != null) {
                    // Auth header is automatically added by ImageLoader's OkHttp interceptor
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(uiState.thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = stringResource(R.string.document_detail_preview),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = stringResource(R.string.cd_document_thumbnail),
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Title
        Text(
            text = uiState.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Tags
        if (uiState.tags.isNotEmpty()) {
            val primaryColor = MaterialTheme.colorScheme.primary
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.tags.forEach { tag ->
                    val tagColor = parseTagColor(tag.color, primaryColor)
                    Box(
                        modifier = Modifier
                            .background(
                                color = tagColor.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(50)
                            )
                            .border(
                                width = 1.dp,
                                color = tagColor,
                                shape = RoundedCornerShape(50)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(tagColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Info Cards
        uiState.correspondent?.let {
            InfoCard(
                icon = Icons.Filled.Person,
                label = stringResource(R.string.document_detail_correspondent),
                value = it
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        uiState.documentType?.let {
            InfoCard(
                icon = Icons.Filled.Folder,
                label = stringResource(R.string.document_detail_type),
                value = it
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        InfoCard(
            icon = Icons.Filled.CalendarToday,
            label = stringResource(R.string.document_detail_created),
            value = uiState.created
        )

        Spacer(modifier = Modifier.height(12.dp))

        uiState.archiveSerialNumber?.let {
            InfoCard(
                icon = Icons.Filled.Description,
                label = stringResource(R.string.document_detail_asn),
                value = it.toString()
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        uiState.originalFileName?.let {
            InfoCard(
                icon = Icons.Filled.Download,
                label = stringResource(R.string.document_detail_filename),
                value = it
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // View PDF Button
        Button(
            onClick = {
                onOpenPdf(uiState.id, uiState.title)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = stringResource(R.string.cd_pdf),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.document_detail_view_pdf))
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    label: String,
    value: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun parseTagColor(colorString: String?, fallbackColor: Color): Color {
    if (colorString == null) return fallbackColor
    return try {
        if (colorString.startsWith("#")) {
            Color(android.graphics.Color.parseColor(colorString))
        } else {
            fallbackColor
        }
    } catch (e: Exception) {
        fallbackColor
    }
}
