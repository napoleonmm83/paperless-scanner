package com.paperless.scanner.data.api

import com.paperless.scanner.data.api.models.CreateTagRequest
import com.paperless.scanner.data.api.models.DocumentTypesResponse
import com.paperless.scanner.data.api.models.Tag
import com.paperless.scanner.data.api.models.TagsResponse
import com.paperless.scanner.data.api.models.TokenResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

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

    @Multipart
    @POST("api/documents/post_document/")
    suspend fun uploadDocument(
        @Part document: MultipartBody.Part,
        @Part("title") title: RequestBody? = null,
        @Part("tags") tags: RequestBody? = null,
        @Part("document_type") documentType: RequestBody? = null
    ): ResponseBody
}
