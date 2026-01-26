package com.paperless.scanner.ui.screens.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.upload.components.CorrespondentDropdown
import com.paperless.scanner.ui.screens.upload.components.DocumentTypeDropdown
import com.paperless.scanner.ui.screens.upload.components.TagSelectionSection
import com.paperless.scanner.util.FileUtils

/**
 * Step-by-step metadata editor for batch upload with individual metadata per page.
 *
 * Shows pages one by one with metadata input fields:
 * - Image preview (top)
 * - Title, Tags, Correspondent, DocumentType (bottom)
 * - Navigation: Back / Next / Finish
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepByStepMetadataScreen(
    pages: List<ScannedPage>,
    onFinish: (List<ScannedPage>) -> Unit,  // Returns pages with customMetadata filled
    onNavigateBack: () -> Unit,
    viewModel: StepByStepMetadataViewModel = hiltViewModel()
) {
    // Initialize pages in ViewModel
    LaunchedEffect(pages) {
        if (pages.isNotEmpty()) {
            viewModel.initializePages(pages)
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val currentPage = uiState.currentPage

    // Observe metadata collections from ViewModel
    val tags by viewModel.tags.collectAsState()
    val documentTypes by viewModel.documentTypes.collectAsState()
    val correspondents by viewModel.correspondents.collectAsState()

    // Local state for current page metadata
    // Auto-populate title with filename if no existing metadata title
    val context = LocalContext.current
    var title by remember(uiState.currentPageIndex) {
        val existingTitle = viewModel.getCurrentPageMetadata()?.title
        val pageUri = currentPage?.uri
        val filename = pageUri?.let { FileUtils.getFileName(context, it) }
        mutableStateOf(existingTitle ?: filename ?: "")
    }
    var selectedTagIds by remember(uiState.currentPageIndex) {
        mutableStateOf(viewModel.getCurrentPageMetadata()?.tags?.toSet() ?: emptySet())
    }
    var selectedCorrespondentId by remember(uiState.currentPageIndex) {
        mutableIntStateOf(viewModel.getCurrentPageMetadata()?.correspondent ?: 0)
    }
    var selectedDocumentTypeId by remember(uiState.currentPageIndex) {
        mutableIntStateOf(viewModel.getCurrentPageMetadata()?.documentType ?: 0)
    }

    // Save metadata when navigating or finishing
    fun saveCurrentPageMetadata() {
        val metadata = PageMetadata(
            title = title.takeIf { it.isNotBlank() },
            tags = selectedTagIds.toList().takeIf { it.isNotEmpty() },
            correspondent = selectedCorrespondentId.takeIf { it > 0 },
            documentType = selectedDocumentTypeId.takeIf { it > 0 }
        )
        viewModel.updateCurrentPageMetadata(metadata)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.scan_step_metadata_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.scan_step_metadata_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (currentPage == null) {
            // No pages to show
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("No pages available")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                // Progress Indicator
                Text(
                    text = stringResource(
                        R.string.scan_step_metadata_page_progress,
                        uiState.currentPageIndex + 1,
                        uiState.totalPages
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Image Preview
                AsyncImage(
                    model = currentPage.uri,
                    contentDescription = "Page ${uiState.currentPageIndex + 1}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Fit
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Metadata Form
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    // Title Input
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.multipage_upload_title_label)) },
                        placeholder = { Text(stringResource(R.string.multipage_upload_title_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Document Type Dropdown
                    DocumentTypeDropdown(
                        documentTypes = documentTypes,
                        selectedId = selectedDocumentTypeId,
                        onSelect = { selectedDocumentTypeId = it ?: 0 }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Correspondent Dropdown
                    CorrespondentDropdown(
                        correspondents = correspondents,
                        selectedId = selectedCorrespondentId,
                        onSelect = { selectedCorrespondentId = it ?: 0 }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tag Selection
                    TagSelectionSection(
                        tags = tags,
                        selectedTagIds = selectedTagIds,
                        onToggleTag = { tagId ->
                            selectedTagIds = if (selectedTagIds.contains(tagId)) {
                                selectedTagIds - tagId
                            } else {
                                selectedTagIds + tagId
                            }
                        },
                        onCreateNew = { /* TODO: Implement tag creation dialog */ }
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Navigation Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back Button
                    if (uiState.hasPrevious) {
                        TextButton(
                            onClick = {
                                saveCurrentPageMetadata()
                                viewModel.previousPage()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text(stringResource(R.string.scan_step_metadata_back))
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Spacer(modifier = Modifier.padding(8.dp))

                    // Next/Finish Button
                    Button(
                        onClick = {
                            saveCurrentPageMetadata()
                            if (uiState.isLastPage) {
                                // Apply all metadata to pages and finish
                                val pagesWithMetadata = uiState.pages.map { page ->
                                    val metadata = uiState.pageMetadata[page.id]
                                    page.copy(customMetadata = metadata)
                                }
                                onFinish(pagesWithMetadata)
                            } else {
                                viewModel.nextPage()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = CircleShape
                    ) {
                        if (uiState.isLastPage) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text(stringResource(R.string.scan_step_metadata_finish))
                        } else {
                            Text(stringResource(R.string.scan_step_metadata_next))
                            Spacer(modifier = Modifier.padding(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
