package com.paperless.scanner.ui.screens.upload.usecase

import android.content.Context
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.CustomFieldRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.CustomField
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.domain.error.getLocalizedMessage
import com.paperless.scanner.util.CoroutineDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.paperless.scanner.util.AppLogger

/** Result of a tag-creation attempt (incl. duplicate recovery). UI-agnostic. */
sealed interface CreateTagResult {
    data class Success(val tag: Tag) : CreateTagResult
    data class Failure(val message: String) : CreateTagResult
}

/** Result of a correspondent-creation attempt; [Failure.message] is already user-facing. */
sealed interface CreateCorrespondentResult {
    data class Success(val correspondent: Correspondent) : CreateCorrespondentResult
    data class Failure(val message: String) : CreateCorrespondentResult
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
                AppLogger.e(TAG, "Failed to create tag", e)
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

    /**
     * Creates a correspondent by name (matching rule left to the server default).
     * A [PaperlessException.ClientError] may mean the name already exists server-side but not yet
     * in the local cache: refresh and resolve it by name so the caller selects it instead of
     * seeing an error. If no such name exists, the localized client error is shown.
     * Any other failure carries the localized [PaperlessException] message (offline, timeout, 403…).
     */
    suspend fun createCorrespondent(name: String): CreateCorrespondentResult = withContext(dispatchers.io) {
        val trimmed = name.trim()
        correspondentRepository.createCorrespondent(trimmed).fold(
            onSuccess = { CreateCorrespondentResult.Success(it) },
            onFailure = { e ->
                AppLogger.e(TAG, "Failed to create correspondent", e)
                val existing = if (e is PaperlessException.ClientError) {
                    correspondentRepository.getCorrespondents(forceRefresh = true).getOrNull()
                        ?.find { it.name.equals(trimmed, ignoreCase = true) }
                } else {
                    null
                }
                existing?.let { CreateCorrespondentResult.Success(it) }
                    ?: CreateCorrespondentResult.Failure(
                        (e as? PaperlessException)?.getLocalizedMessage(context)
                            ?: context.getString(R.string.error_create_correspondent)
                    )
            }
        )
    }

    companion object {
        private const val TAG = "UploadMetadataUseCase"
    }
}
