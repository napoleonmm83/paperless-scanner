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
    val isInboxTag: Boolean = false,
    @SerializedName("document_count")
    val documentCount: Int? = null
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

data class Correspondent(
    @SerializedName("id")
    val id: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("match")
    val match: String? = null,
    @SerializedName("matching_algorithm")
    val matchingAlgorithm: Int? = null
)

data class CorrespondentsResponse(
    @SerializedName("count")
    val count: Int,
    @SerializedName("next")
    val next: String? = null,
    @SerializedName("previous")
    val previous: String? = null,
    @SerializedName("results")
    val results: List<Correspondent>
)

// Document Models
data class Document(
    @SerializedName("id")
    val id: Int,
    @SerializedName("title")
    val title: String,
    @SerializedName("content")
    val content: String? = null,
    @SerializedName("created")
    val created: String,
    @SerializedName("modified")
    val modified: String,
    @SerializedName("added")
    val added: String,
    @SerializedName("correspondent")
    val correspondentId: Int? = null,
    @SerializedName("document_type")
    val documentTypeId: Int? = null,
    @SerializedName("tags")
    val tags: List<Int> = emptyList(),
    @SerializedName("archive_serial_number")
    val archiveSerialNumber: Int? = null,
    @SerializedName("original_file_name")
    val originalFileName: String? = null
)

data class DocumentsResponse(
    @SerializedName("count")
    val count: Int,
    @SerializedName("next")
    val next: String? = null,
    @SerializedName("previous")
    val previous: String? = null,
    @SerializedName("results")
    val results: List<Document>
)

data class UpdateTagRequest(
    @SerializedName("name")
    val name: String,
    @SerializedName("color")
    val color: String? = null
)

// Task Models for tracking document processing
data class PaperlessTask(
    @SerializedName("id")
    val id: Int,
    @SerializedName("task_id")
    val taskId: String,
    @SerializedName("task_file_name")
    val taskFileName: String? = null,
    @SerializedName("date_created")
    val dateCreated: String,
    @SerializedName("date_done")
    val dateDone: String? = null,
    @SerializedName("type")
    val type: String,
    @SerializedName("status")
    val status: String,
    @SerializedName("result")
    val result: String? = null,
    @SerializedName("acknowledged")
    val acknowledged: Boolean = false,
    @SerializedName("related_document")
    val relatedDocument: String? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_STARTED = "STARTED"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILURE = "FAILURE"
    }

    val isCompleted: Boolean
        get() = status == STATUS_SUCCESS || status == STATUS_FAILURE

    val isSuccess: Boolean
        get() = status == STATUS_SUCCESS

    val isPending: Boolean
        get() = status == STATUS_PENDING || status == STATUS_STARTED
}

// Request body for acknowledging tasks
data class AcknowledgeTasksRequest(
    @SerializedName("tasks")
    val tasks: List<Int>
)
