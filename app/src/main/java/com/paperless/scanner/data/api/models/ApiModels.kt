package com.paperless.scanner.data.api.models

import com.google.gson.annotations.SerializedName

data class TokenResponse(
    @SerializedName("token")
    val token: String
)

data class Tag(
    @SerializedName("id")
    val id: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("color")
    val color: String? = null,
    @SerializedName("match")
    val match: String? = null,
    @SerializedName("matching_algorithm")
    val matchingAlgorithm: Int? = null,
    @SerializedName("is_inbox_tag")
    val isInboxTag: Boolean = false
)

data class TagsResponse(
    @SerializedName("count")
    val count: Int,
    @SerializedName("next")
    val next: String? = null,
    @SerializedName("previous")
    val previous: String? = null,
    @SerializedName("results")
    val results: List<Tag>
)

data class CreateTagRequest(
    @SerializedName("name")
    val name: String,
    @SerializedName("color")
    val color: String? = null,
    @SerializedName("match")
    val match: String? = null,
    @SerializedName("matching_algorithm")
    val matchingAlgorithm: Int? = null
)

data class DocumentType(
    @SerializedName("id")
    val id: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("match")
    val match: String? = null,
    @SerializedName("matching_algorithm")
    val matchingAlgorithm: Int? = null
)

data class DocumentTypesResponse(
    @SerializedName("count")
    val count: Int,
    @SerializedName("next")
    val next: String? = null,
    @SerializedName("previous")
    val previous: String? = null,
    @SerializedName("results")
    val results: List<DocumentType>
)

data class UploadResponse(
    @SerializedName("task_id")
    val taskId: String
)
