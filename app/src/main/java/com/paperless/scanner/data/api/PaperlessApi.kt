package com.paperless.scanner.data.api

import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest
import com.paperless.scanner.data.api.models.AuditLogEntry
import com.paperless.scanner.data.api.models.Correspondent
import com.paperless.scanner.data.api.models.CorrespondentsResponse
import com.paperless.scanner.data.api.models.CreateCorrespondentRequest
import com.paperless.scanner.data.api.models.CreateCustomFieldRequest
import com.paperless.scanner.data.api.models.CreateDocumentTypeRequest
import com.paperless.scanner.data.api.models.CreateNoteRequest
import com.paperless.scanner.data.api.models.CreateTagRequest
import com.paperless.scanner.data.api.models.CustomField
import com.paperless.scanner.data.api.models.CustomFieldsResponse
import com.paperless.scanner.data.api.models.Document
import com.paperless.scanner.data.api.models.DocumentType
import com.paperless.scanner.data.api.models.DocumentTypesResponse
import com.paperless.scanner.data.api.models.DocumentsResponse
import com.paperless.scanner.data.api.models.GroupsResponse
import com.paperless.scanner.data.api.models.PaperlessTask
import com.paperless.scanner.data.api.models.ServerStatusResponse
import com.paperless.scanner.data.api.models.SuggestionsResponse
import com.paperless.scanner.data.api.models.Tag
import com.paperless.scanner.data.api.models.TagsResponse
import com.paperless.scanner.data.api.models.TokenResponse
import com.paperless.scanner.data.api.models.TrashBulkActionRequest
import com.paperless.scanner.data.api.models.UpdateCorrespondentRequest
import com.paperless.scanner.data.api.models.UpdateDocumentRequest
import com.paperless.scanner.data.api.models.UpdateDocumentTypeRequest
import com.paperless.scanner.data.api.models.UpdateDocumentWithPermissionsRequest
import com.paperless.scanner.data.api.models.UpdateTagRequest
import com.paperless.scanner.data.api.models.UsersResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * PaperlessApi - Retrofit interface for Paperless-ngx REST API.
 *
 * **API OVERVIEW:**
 * This interface defines all HTTP endpoints for communicating with a Paperless-ngx server.
 * Endpoints are organized by resource type: authentication, documents, tags, correspondents,
 * document types, custom fields, tasks, users/groups, and trash management.
 *
 * **AUTHENTICATION:**
 * Most endpoints require token-based authentication. Use [getToken] to obtain an auth token,
 * which is then added to requests via [AuthInterceptor].
 *
 * **IMPORTANT API QUIRKS:**
 * - Upload endpoint returns plain string task ID, NOT JSON object
 * - Task status polling required after upload to confirm completion
 * - Pagination uses 1-indexed pages (not 0-indexed)
 * - Some endpoints require admin permissions (e.g., [getServerStatus])
 *
 * **ERROR HANDLING:**
 * - 401 Unauthorized: Token invalid or expired
 * - 403 Forbidden: Insufficient permissions
 * - 404 Not Found: Resource doesn't exist
 * - 500+ Server Error: Paperless-ngx internal error
 *
 * @see AuthRepository For authentication flow implementation
 * @see DocumentRepository For document operations
 * @see com.paperless.scanner.di.NetworkModule For Retrofit configuration
 */
interface PaperlessApi {

    /**
     * Authenticate user and obtain API token.
     *
     * **ENDPOINT:** `POST /api/token/`
     *
     * Authenticates with username/password credentials and returns a persistent
     * API token for subsequent requests. Token does not expire unless revoked.
     *
     * **USAGE:**
     * ```kotlin
     * val response = api.getToken("user", "password")
     * tokenManager.saveToken(response.token)
     * ```
     *
     * @param username Paperless-ngx username
     * @param password Paperless-ngx password
     * @return [TokenResponse] containing the authentication token
     * @throws retrofit2.HttpException 400 Bad Request if credentials invalid
     * @see AuthRepository.login For complete login flow with error handling
     * @see TokenManager For token persistence
     */
    @POST("api/token/")
    @FormUrlEncoded
    suspend fun getToken(
        @Field("username") username: String,
        @Field("password") password: String
    ): TokenResponse

    /**
     * Get server status and version information.
     * Requires admin permissions. Returns 403 for non-admin users.
     *
     * @return Response with ServerStatusResponse body and x-version header
     */
    @GET("api/status/")
    suspend fun getServerStatus(): Response<ServerStatusResponse>

    // ==================== TAG ENDPOINTS ====================

    /**
     * Get paginated list of tags.
     *
     * **ENDPOINT:** `GET /api/tags/`
     *
     * @param page Page number (1-indexed, default: 1)
     * @param pageSize Number of results per page (default: 25)
     * @return [TagsResponse] with paginated tag list
     * @see TagRepository.observeTags For reactive tag list with caching
     */
    @GET("api/tags/")
    suspend fun getTags(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 25
    ): TagsResponse

    /**
     * Create a new tag.
     *
     * **ENDPOINT:** `POST /api/tags/`
     *
     * @param tag Tag creation request with name, color, and optional matching rules
     * @return Created [Tag] object with assigned ID
     * @see TagRepository.createTag For tag creation with cache update
     */
    @POST("api/tags/")
    suspend fun createTag(
        @Body tag: CreateTagRequest
    ): Tag

    // ==================== DOCUMENT TYPE ENDPOINTS ====================

    /**
     * Get paginated list of document types.
     *
     * **ENDPOINT:** `GET /api/document_types/`
     *
     * @param page Page number (1-indexed, default: 1)
     * @param pageSize Number of results per page (default: 25)
     * @return [DocumentTypesResponse] with paginated document type list
     * @see DocumentTypeRepository.observeDocumentTypes For reactive list with caching
     */
    @GET("api/document_types/")
    suspend fun getDocumentTypes(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 25
    ): DocumentTypesResponse

    /**
     * Create a new document type.
     *
     * **ENDPOINT:** `POST /api/document_types/`
     *
     * @param documentType Document type creation request with name and optional matching rules
     * @return Created [DocumentType] object with assigned ID
     */
    @POST("api/document_types/")
    suspend fun createDocumentType(
        @Body documentType: CreateDocumentTypeRequest
    ): DocumentType

    /**
     * Update an existing document type.
     *
     * **ENDPOINT:** `PUT /api/document_types/{id}/`
     *
     * @param id Document type ID to update
     * @param documentType Updated document type data
     * @return Updated [DocumentType] object
     * @throws retrofit2.HttpException 404 if document type doesn't exist
     */
    @PUT("api/document_types/{id}/")
    suspend fun updateDocumentType(
        @Path("id") id: Int,
        @Body documentType: UpdateDocumentTypeRequest
    ): DocumentType

    // ==================== CORRESPONDENT ENDPOINTS ====================

    /**
     * Get paginated list of correspondents.
     *
     * **ENDPOINT:** `GET /api/correspondents/`
     *
     * @param page Page number (1-indexed, default: 1)
     * @param pageSize Number of results per page (default: 25)
     * @return [CorrespondentsResponse] with paginated correspondent list
     * @see CorrespondentRepository.observeCorrespondents For reactive list with caching
     */
    @GET("api/correspondents/")
    suspend fun getCorrespondents(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 25
    ): CorrespondentsResponse

    /**
     * Create a new correspondent.
     *
     * **ENDPOINT:** `POST /api/correspondents/`
     *
     * @param correspondent Correspondent creation request with name and optional matching rules
     * @return Created [Correspondent] object with assigned ID
     */
    @POST("api/correspondents/")
    suspend fun createCorrespondent(
        @Body correspondent: CreateCorrespondentRequest
    ): Correspondent

    /**
     * Update an existing correspondent.
     *
     * **ENDPOINT:** `PUT /api/correspondents/{id}/`
     *
     * @param id Correspondent ID to update
     * @param correspondent Updated correspondent data
     * @return Updated [Correspondent] object
     * @throws retrofit2.HttpException 404 if correspondent doesn't exist
     */
    @PUT("api/correspondents/{id}/")
    suspend fun updateCorrespondent(
        @Path("id") id: Int,
        @Body correspondent: UpdateCorrespondentRequest
    ): Correspondent

    // ==================== DOCUMENT ENDPOINTS ====================

    /**
     * Upload a document to Paperless-ngx for processing.
     *
     * **ENDPOINT:** `POST /api/documents/post_document/`
     *
     * **CRITICAL:** Returns a plain string task ID, NOT a JSON object.
     * Use [getTask] to poll for upload completion status.
     *
     * **USAGE:**
     * ```kotlin
     * val response = api.uploadDocument(filePart, title, tagParts)
     * val taskId = response.string().trim().removeSurrounding("\"")
     * // Poll getTask(taskId) until status = "SUCCESS"
     * ```
     *
     * @param document Multipart file data (PDF, image, etc.)
     * @param title Optional document title (auto-generated from filename if null)
     * @param tags List of tag IDs as multipart parts
     * @param documentType Optional document type ID
     * @param correspondent Optional correspondent ID
     * @param customFields Optional JSON string of custom field values
     * @return [ResponseBody] containing plain string task UUID (NOT JSON)
     * @throws retrofit2.HttpException 400 if file format unsupported
     * @see DocumentRepository.uploadDocument For complete upload flow
     * @see getTask For polling upload status
     */
    @Multipart
    @POST("api/documents/post_document/")
    suspend fun uploadDocument(
        @Part document: MultipartBody.Part,
        @Part("title") title: RequestBody? = null,
        @Part tags: List<MultipartBody.Part> = emptyList(),
        @Part("document_type") documentType: RequestBody? = null,
        @Part("correspondent") correspondent: RequestBody? = null,
        @Part("custom_fields") customFields: RequestBody? = null
    ): ResponseBody

    /**
     * Get paginated list of documents with optional filtering.
     *
     * **ENDPOINT:** `GET /api/documents/`
     *
     * Supports full-text search, tag filtering, correspondent filtering,
     * and document type filtering. Results are paginated (1-indexed).
     *
     * @param page Page number (1-indexed, default: 1)
     * @param pageSize Number of results per page (default: 25)
     * @param query Full-text search query (searches content and metadata)
     * @param tagIds Comma-separated tag IDs to filter by (e.g., "1,2,3")
     * @param tagsIsNull If true, returns only documents without tags
     * @param correspondentId Filter by correspondent ID
     * @param documentTypeId Filter by document type ID
     * @param ordering Sort order (default: "-created" for newest first)
     * @return [DocumentsResponse] with paginated document list
     * @see DocumentRepository.observeDocuments For reactive document list
     */
    @GET("api/documents/")
    suspend fun getDocuments(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 25,
        @Query("query") query: String? = null,
        @Query("tags__id__in") tagIds: String? = null,
        @Query("tags__id__isnull") tagsIsNull: Boolean? = null,
        @Query("correspondent__id") correspondentId: Int? = null,
        @Query("document_type__id") documentTypeId: Int? = null,
        @Query("ordering") ordering: String = "-created"
    ): DocumentsResponse

    /**
     * Get a single document by ID.
     *
     * **ENDPOINT:** `GET /api/documents/{id}/`
     *
     * @param id Document ID
     * @param fullPerms If true, includes full permission details (default: true)
     * @return [Document] with all metadata
     * @throws retrofit2.HttpException 404 if document doesn't exist
     * @see DocumentRepository.getDocument For cached document retrieval
     */
    @GET("api/documents/{id}/")
    suspend fun getDocument(
        @Path("id") id: Int,
        @Query("full_perms") fullPerms: Boolean = true
    ): Document

    /**
     * Get ML-based suggestions for a document.
     *
     * **ENDPOINT:** `GET /api/documents/{id}/suggestions/`
     *
     * Returns AI-suggested tags, correspondents, and document types
     * based on OCR content analysis. Requires ML features enabled on server.
     *
     * @param documentId Document ID to get suggestions for
     * @return [SuggestionsResponse] with suggested metadata
     * @see DocumentRepository.getDocumentSuggestions For suggestion retrieval
     */
    @GET("api/documents/{id}/suggestions/")
    suspend fun getDocumentSuggestions(
        @Path("id") documentId: Int
    ): SuggestionsResponse

    /**
     * Download the original document file.
     *
     * **ENDPOINT:** `GET /api/documents/{id}/download/`
     *
     * Uses @Streaming for memory-efficient large file downloads.
     * The response body must be consumed and closed properly.
     *
     * @param id Document ID to download
     * @return [ResponseBody] streaming the file content
     * @see DocumentRepository.downloadDocument For file download with caching
     */
    @GET("api/documents/{id}/download/")
    @Streaming
    suspend fun downloadDocument(@Path("id") id: Int): ResponseBody

    /**
     * Delete a document (moves to trash in Paperless-ngx v2.20+).
     *
     * **ENDPOINT:** `DELETE /api/documents/{id}/`
     *
     * In Paperless-ngx v2.20+, this performs a soft delete (trash).
     * Use [trashBulkAction] to permanently delete or restore.
     *
     * @param id Document ID to delete
     * @return Empty response with 204 No Content on success
     * @throws retrofit2.HttpException 404 if document doesn't exist
     * @see DocumentRepository.deleteDocument For delete with cache invalidation
     * @see trashBulkAction For permanent deletion
     */
    @DELETE("api/documents/{id}/")
    suspend fun deleteDocument(@Path("id") id: Int): Response<Unit>

    /**
     * Update document metadata.
     *
     * **ENDPOINT:** `PATCH /api/documents/{id}/`
     *
     * Partial update - only provided fields are modified.
     * Use for title, tags, correspondent, document type, notes, etc.
     *
     * @param id Document ID to update
     * @param document Request body with fields to update
     * @return Updated [Document] object
     * @throws retrofit2.HttpException 404 if document doesn't exist
     * @see DocumentRepository.updateDocument For update with cache sync
     */
    @PATCH("api/documents/{id}/")
    suspend fun updateDocument(
        @Path("id") id: Int,
        @Body document: UpdateDocumentRequest
    ): Document

    /**
     * Get document audit history.
     *
     * **ENDPOINT:** `GET /api/documents/{id}/history/`
     *
     * Returns chronological list of all changes made to the document,
     * including who made each change and what was modified.
     *
     * @param id Document ID
     * @return List of [AuditLogEntry] records
     */
    @GET("api/documents/{id}/history/")
    suspend fun getDocumentHistory(@Path("id") id: Int): List<AuditLogEntry>

    /**
     * Add a note to a document.
     *
     * **ENDPOINT:** `POST /api/documents/{id}/notes/`
     *
     * @param documentId Document ID to add note to
     * @param request Note content
     * @return Updated list of all notes on the document
     */
    @POST("api/documents/{id}/notes/")
    suspend fun addNote(
        @Path("id") documentId: Int,
        @Body request: CreateNoteRequest
    ): List<com.paperless.scanner.data.api.models.Note>

    /**
     * Delete a note from a document.
     *
     * **ENDPOINT:** `DELETE /api/documents/{id}/notes/?id={noteId}`
     *
     * @param documentId Document ID containing the note
     * @param noteId ID of the note to delete
     * @return Updated list of remaining notes
     */
    @DELETE("api/documents/{id}/notes/")
    suspend fun deleteNote(
        @Path("id") documentId: Int,
        @Query("id") noteId: Int
    ): List<com.paperless.scanner.data.api.models.Note>

    // ==================== TAG UPDATE/DELETE ====================

    /**
     * Update an existing tag.
     *
     * **ENDPOINT:** `PUT /api/tags/{id}/`
     *
     * @param id Tag ID to update
     * @param tag Updated tag data (name, color, matching rules)
     * @return Updated [Tag] object
     * @throws retrofit2.HttpException 404 if tag doesn't exist
     * @see TagRepository.updateTag For update with cache sync
     */
    @PUT("api/tags/{id}/")
    suspend fun updateTag(
        @Path("id") id: Int,
        @Body tag: UpdateTagRequest
    ): Tag

    /**
     * Delete a tag.
     *
     * **ENDPOINT:** `DELETE /api/tags/{id}/`
     *
     * Removes the tag from the system. Documents with this tag
     * will have the tag reference removed.
     *
     * @param id Tag ID to delete
     * @return Empty response with 204 No Content on success
     * @throws retrofit2.HttpException 404 if tag doesn't exist
     * @see TagRepository.deleteTag For delete with cache invalidation
     */
    @DELETE("api/tags/{id}/")
    suspend fun deleteTag(@Path("id") id: Int): Response<Unit>

    // ==================== CORRESPONDENT DELETE ====================

    /**
     * Delete a correspondent.
     *
     * **ENDPOINT:** `DELETE /api/correspondents/{id}/`
     *
     * Removes the correspondent from the system. Documents with this
     * correspondent will have the correspondent reference set to null.
     *
     * @param id Correspondent ID to delete
     * @return Empty response with 204 No Content on success
     * @throws retrofit2.HttpException 404 if correspondent doesn't exist
     * @see CorrespondentRepository.deleteCorrespondent For delete with cache invalidation
     */
    @DELETE("api/correspondents/{id}/")
    suspend fun deleteCorrespondent(@Path("id") id: Int): Response<Unit>

    // ==================== DOCUMENT TYPE DELETE ====================

    /**
     * Delete a document type.
     *
     * **ENDPOINT:** `DELETE /api/document_types/{id}/`
     *
     * Removes the document type from the system. Documents with this
     * document type will have the type reference set to null.
     *
     * @param id Document type ID to delete
     * @return Empty response with 204 No Content on success
     * @throws retrofit2.HttpException 404 if document type doesn't exist
     */
    @DELETE("api/document_types/{id}/")
    suspend fun deleteDocumentType(@Path("id") id: Int): Response<Unit>

    // ==================== CUSTOM FIELDS ENDPOINTS ====================

    /**
     * Get paginated list of custom fields.
     *
     * **ENDPOINT:** `GET /api/custom_fields/`
     *
     * Custom fields allow adding arbitrary metadata to documents.
     * Field types include: text, number, date, boolean, URL, monetary.
     *
     * @param page Page number (1-indexed, default: 1)
     * @param pageSize Number of results per page (default: 100)
     * @return [CustomFieldsResponse] with available custom field definitions
     * @see CustomFieldRepository.observeCustomFields For reactive field list
     */
    @GET("api/custom_fields/")
    suspend fun getCustomFields(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100
    ): CustomFieldsResponse

    /**
     * Create a new custom field definition.
     *
     * **ENDPOINT:** `POST /api/custom_fields/`
     *
     * @param customField Field definition with name and data type
     * @return Created [CustomField] object with assigned ID
     */
    @POST("api/custom_fields/")
    suspend fun createCustomField(
        @Body customField: CreateCustomFieldRequest
    ): CustomField

    /**
     * Delete a custom field definition.
     *
     * **ENDPOINT:** `DELETE /api/custom_fields/{id}/`
     *
     * Removes the custom field definition. All document values
     * for this field will be deleted.
     *
     * @param id Custom field ID to delete
     * @return Empty response with 204 No Content on success
     * @throws retrofit2.HttpException 404 if field doesn't exist
     */
    @DELETE("api/custom_fields/{id}/")
    suspend fun deleteCustomField(@Path("id") id: Int): Response<Unit>

    // ==================== TASK TRACKING ENDPOINTS ====================

    /**
     * Get all pending background tasks.
     *
     * **ENDPOINT:** `GET /api/tasks/`
     *
     * Returns list of all background tasks (uploads, processing, etc.)
     * that are pending, in progress, or recently completed.
     *
     * @return List of [PaperlessTask] objects
     * @see UploadQueueRepository For upload task management
     */
    @GET("api/tasks/")
    suspend fun getTasks(): List<PaperlessTask>

    /**
     * Get a specific task by ID.
     *
     * **ENDPOINT:** `GET /api/tasks/?task_id={taskId}`
     *
     * Used to poll upload status after [uploadDocument].
     * Task status values: PENDING, STARTED, SUCCESS, FAILURE.
     *
     * @param taskId Task UUID returned from upload endpoint
     * @return List containing the matching task (or empty if not found)
     * @see uploadDocument For initiating uploads
     */
    @GET("api/tasks/")
    suspend fun getTask(@Query("task_id") taskId: String): List<PaperlessTask>

    /**
     * Acknowledge (dismiss) completed tasks.
     *
     * **ENDPOINT:** `POST /api/tasks/acknowledge/`
     *
     * Marks specified tasks as acknowledged, removing them from
     * the pending tasks list in the UI.
     *
     * @param request Request containing list of task IDs to acknowledge
     * @return Empty response with 200 OK on success
     */
    @POST("api/tasks/acknowledge/")
    suspend fun acknowledgeTasks(@Body request: AcknowledgeTasksRequest): Response<Unit>

    // ==================== USER/GROUP ENDPOINTS ====================

    /**
     * Get paginated list of users.
     *
     * **ENDPOINT:** `GET /api/users/`
     *
     * Used for permission management - assigning document ownership
     * and view/change permissions to specific users.
     *
     * @param page Page number (1-indexed, default: 1)
     * @param pageSize Number of results per page (default: 100)
     * @return [UsersResponse] with paginated user list
     * @see updateDocumentPermissions For assigning user permissions
     */
    @GET("api/users/")
    suspend fun getUsers(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100
    ): UsersResponse

    /**
     * Get paginated list of groups.
     *
     * **ENDPOINT:** `GET /api/groups/`
     *
     * Used for permission management - assigning document
     * view/change permissions to user groups.
     *
     * @param page Page number (1-indexed, default: 1)
     * @param pageSize Number of results per page (default: 100)
     * @return [GroupsResponse] with paginated group list
     * @see updateDocumentPermissions For assigning group permissions
     */
    @GET("api/groups/")
    suspend fun getGroups(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100
    ): GroupsResponse

    /**
     * Update document permissions (owner, view/change permissions).
     *
     * **ENDPOINT:** `PATCH /api/documents/{id}/`
     *
     * Updates document access permissions including owner assignment
     * and user/group level view/change permissions.
     *
     * @param id Document ID to update
     * @param document Request with permission fields (owner, set_permissions)
     * @return Updated [Document] with new permission settings
     * @throws retrofit2.HttpException 403 if user lacks permission to change permissions
     * @see getUsers For available users to assign permissions
     * @see getGroups For available groups to assign permissions
     */
    @PATCH("api/documents/{id}/")
    suspend fun updateDocumentPermissions(
        @Path("id") id: Int,
        @Body document: UpdateDocumentWithPermissionsRequest
    ): Document

    // Trash endpoints (Paperless-ngx v2.20+)

    /**
     * Get documents from trash (soft-deleted documents).
     * Returns paginated list of deleted documents.
     *
     * @param page Page number (1-indexed)
     * @param pageSize Number of results per page
     * @return DocumentsResponse with deleted documents
     */
    @GET("api/trash/")
    suspend fun getTrash(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 25
    ): DocumentsResponse

    /**
     * Perform bulk action on documents in trash.
     * Supports "restore" (move back to documents) and "delete" (permanent deletion).
     *
     * @param request TrashBulkActionRequest with document IDs and action
     * @return 200 OK on success
     */
    @POST("api/trash/")
    suspend fun trashBulkAction(
        @Body request: TrashBulkActionRequest
    ): Response<Unit>
}
