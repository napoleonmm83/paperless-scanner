package com.paperless.scanner.data.api

import com.paperless.scanner.data.api.models.CorrespondentsResponse
import com.paperless.scanner.data.api.models.CreateTagRequest
import com.paperless.scanner.data.api.models.Document
import com.paperless.scanner.data.api.models.DocumentTypesResponse
import com.paperless.scanner.data.api.models.DocumentsResponse
import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest
import com.paperless.scanner.data.api.models.PaperlessTask
import com.paperless.scanner.data.api.models.Tag
import com.paperless.scanner.data.api.models.TagsResponse
import com.paperless.scanner.data.api.models.TokenResponse
import com.paperless.scanner.data.api.models.UpdateTagRequest
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
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface PaperlessApi {

    @POST("api/token/")
    @FormUrlEncoded
    suspend fun getToken(
        @Field("username") username: String,
        @Field("password") password: String
    ): TokenResponse

    @GET("api/tags/")
    suspend fun getTags(): TagsResponse

    @POST("api/tags/")
    suspend fun createTag(
        @Body tag: CreateTagRequest
    ): Tag

    @GET("api/document_types/")
    suspend fun getDocumentTypes(): DocumentTypesResponse

    @GET("api/correspondents/")
    suspend fun getCorrespondents(): CorrespondentsResponse

    @Multipart
    @POST("api/documents/post_document/")
    suspend fun uploadDocument(
        @Part document: MultipartBody.Part,
        @Part("title") title: RequestBody? = null,
        @Part("tags") tags: RequestBody? = null,
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
    suspend fun getDocument(@Path("id") id: Int): Document

    // Tag update/delete
    @PUT("api/tags/{id}/")
    suspend fun updateTag(
        @Path("id") id: Int,
        @Body tag: UpdateTagRequest
    ): Tag

    @DELETE("api/tags/{id}/")
    suspend fun deleteTag(@Path("id") id: Int): Response<Unit>

    // Task tracking endpoints
    @GET("api/tasks/")
    suspend fun getTasks(): List<PaperlessTask>

    @GET("api/tasks/")
    suspend fun getTask(@Query("task_id") taskId: String): List<PaperlessTask>

    @POST("api/tasks/acknowledge/")
    suspend fun acknowledgeTasks(@Body request: AcknowledgeTasksRequest): Response<Unit>
}
