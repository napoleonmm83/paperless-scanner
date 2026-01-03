package com.paperless.scanner.domain.model

data class PaperlessTask(
    val id: Int,
    val taskId: String,
    val taskFileName: String? = null,
    val dateCreated: String,
    val dateDone: String? = null,
    val type: String,
    val status: String,
    val result: String? = null,
    val acknowledged: Boolean = false,
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
