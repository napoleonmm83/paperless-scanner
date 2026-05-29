package com.paperless.scanner.ui.screens.upload.usecase

import android.content.Context
import android.util.Log
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.api.models.CustomField
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.CustomFieldRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.util.CoroutineDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Result of a tag-creation attempt (incl. duplicate recovery). UI-agnostic. */
sealed interface CreateTagResult {
    data class Success(val tag: Tag) : CreateTagResult
    data class Failure(val message: String) : CreateTagResult
}

/**
 * Owns the upload-form metadata sources extracted from `UploadViewModel` (issue #42):
 * reactive label flows (tags / document types / correspondents / custom fields) plus the
 * tag-creation write path with duplicate recovery.
 */
class UploadMetadataUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tagRepository: TagRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val customFieldRepository: CustomFieldRepository,
    private val analyticsService: AnalyticsService,
    private val dispatchers: CoroutineDispatchers,
) {
    fun observeTags(): Flow<List<Tag>> = tagRepository.observeTags()
    fun observeDocumentTypes(): Flow<List<DocumentType>> = documentTypeRepository.observeDocumentTypes()
    fun observeCorrespondents(): Flow<List<Correspondent>> = correspondentRepository.observeCorrespondents()
    fun observeCustomFields(): Flow<List<CustomField>> = customFieldRepository.observeCustomFields()

    /** Initial (non-reactive) custom-field load; feature-detected, silent on unsupported servers. */
    suspend fun loadCustomFields() {
        withContext(dispatchers.io) {
            customFieldRepository.getCustomFields()
        }
    }

    /**
     * Creates a tag. On a uniqueness violation, refreshes and resolves the existing tag so the
     * caller still gets a usable [Tag] instead of an error.
     */
    suspend fun createTag(name: String, color: String? = null): CreateTagResult = withContext(dispatchers.io) {
        tagRepository.createTag(name = name, color = color).fold(
            onSuccess = { newTag ->
                analyticsService.trackEvent(AnalyticsEvent.TagCreated)
                CreateTagResult.Success(newTag)
            },
            onFailure = { e ->
                Log.e(TAG, "Failed to create tag", e)
                // Handle duplicate tag error - try to find existing tag
                val existingTag = if (e.message?.contains("unique constraint") == true ||
                    e.message?.contains("already exists") == true
                ) {
                    // Refresh tags from server and try to find the existing tag
                    tagRepository.getTags(forceRefresh = true)
                    tagRepository.observeTags().first()
                        .find { it.name.equals(name, ignoreCase = true) }
                } else {
                    null
                }

                if (existingTag != null) {
                    CreateTagResult.Success(existingTag)
                } else {
                    CreateTagResult.Failure(e.message ?: context.getString(R.string.error_create_tag))
                }
            }
        )
    }

    companion object {
        private const val TAG = "UploadMetadataUseCase"
    }
}
