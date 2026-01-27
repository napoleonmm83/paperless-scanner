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

interface PaperlessApi {

    @POST("api/token/")
    @FormUrlEncoded
    suspend fun getToken(
        @Field("username") username: String,
        @Field("password") password: String
    ): TokenResponse

    @GET("api/tags/")
    suspend fun getTags(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 25
    ): TagsResponse

    @POST("api/tags/")
    suspend fun createTag(
        @Body tag: CreateTagRequest
    ): Tag

    @GET("api/document_types/")
    suspend fun getDocumentTypes(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 25
    ): DocumentTypesResponse

    @POST("api/document_types/")
    suspend fun createDocumentType(
        @Body documentType: CreateDocumentTypeRequest
    ): DocumentType

    @PUT("api/document_types/{id}/")
    suspend fun updateDocumentType(
        @Path("id") id: Int,
        @Body documentType: UpdateDocumentTypeRequest
    ): DocumentType

    @GET("api/correspondents/")
    suspend fun getCorrespondents(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 25
    ): CorrespondentsResponse

    @POST("api/correspondents/")
    suspend fun createCorrespondent(
        @Body correspondent: CreateCorrespondentRequest
    ): Correspondent

    @PUT("api/correspondents/{id}/")
    suspend fun updateCorrespondent(
        @Path("id") id: Int,
        @Body correspondent: UpdateCorrespondentRequest
    ): Correspondent

    @Multipart
    @POST("api/documents/post_document/")
    suspend fun uploadDocument(
        @Part document: MultipartBody.Part,
        @Part("title") title: RequestBody? = null,
        @Part tags: List<MultipartBody.Part> = emptyList(),
        @Part("document_type") documentType: RequestBody? = null,
        @Part("correspondent") correspondent: RequestBody? = null
    ): ResponseBody

    // Document endpoints
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

    @GET("api/documents/{id}/")
    suspend fun getDocument(
        @Path("id") id: Int,
        @Query("full_perms") fullPerms: Boolean = true
    ): Document

    /**
     * Get ML-based suggestions for a document.
     * Returns suggested tags, correspondents, document types based on content analysis.
     */
    @GET("api/documents/{id}/suggestions/")
    suspend fun getDocumentSuggestions(
        @Path("id") documentId: Int
    ): SuggestionsResponse

    @GET("api/documents/{id}/download/")
    @Streaming
    suspend fun downloadDocument(@Path("id") id: Int): ResponseBody

    @DELETE("api/documents/{id}/")
    suspend fun deleteDocument(@Path("id") id: Int): Response<Unit>

    @PATCH("api/documents/{id}/")
    suspend fun updateDocument(
        @Path("id") id: Int,
        @Body document: UpdateDocumentRequest
    ): Document

    @GET("api/documents/{id}/history/")
    suspend fun getDocumentHistory(@Path("id") id: Int): List<AuditLogEntry>

    @POST("api/documents/{id}/notes/")
    suspend fun addNote(
        @Path("id") documentId: Int,
        @Body request: CreateNoteRequest
    ): List<com.paperless.scanner.data.api.models.Note>

    @DELETE("api/documents/{id}/notes/")
    suspend fun deleteNote(
        @Path("id") documentId: Int,
        @Query("id") noteId: Int
    ): List<com.paperless.scanner.data.api.models.Note>

    // Tag update/delete
    @PUT("api/tags/{id}/")
    suspend fun updateTag(
        @Path("id") id: Int,
        @Body tag: UpdateTagRequest
    ): Tag

    @DELETE("api/tags/{id}/")
    suspend fun deleteTag(@Path("id") id: Int): Response<Unit>

    // Correspondent delete
    @DELETE("api/correspondents/{id}/")
    suspend fun deleteCorrespondent(@Path("id") id: Int): Response<Unit>

    // DocumentType delete
    @DELETE("api/document_types/{id}/")
    suspend fun deleteDocumentType(@Path("id") id: Int): Response<Unit>

    // Custom Fields endpoints
    @GET("api/custom_fields/")
    suspend fun getCustomFields(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100
    ): CustomFieldsResponse

    @POST("api/custom_fields/")
    suspend fun createCustomField(
        @Body customField: CreateCustomFieldRequest
    ): CustomField

    @DELETE("api/custom_fields/{id}/")
    suspend fun deleteCustomField(@Path("id") id: Int): Response<Unit>

    // Task tracking endpoints
    @GET("api/tasks/")
    suspend fun getTasks(): List<PaperlessTask>

    @GET("api/tasks/")
    suspend fun getTask(@Query("task_id") taskId: String): List<PaperlessTask>

    @POST("api/tasks/acknowledge/")
    suspend fun acknowledgeTasks(@Body request: AcknowledgeTasksRequest): Response<Unit>

    // User endpoints
    @GET("api/users/")
    suspend fun getUsers(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100
    ): UsersResponse

    // Group endpoints
    @GET("api/groups/")
    suspend fun getGroups(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100
    ): GroupsResponse

    // Update document with permissions
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
