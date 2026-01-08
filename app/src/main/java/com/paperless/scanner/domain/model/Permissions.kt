package com.paperless.scanner.domain.model

/**
 * Domain model for document Permissions
 */
data class PermissionSet(
    val users: List<Int> = emptyList(),
    val groups: List<Int> = emptyList()
)

data class Permissions(
    val view: PermissionSet = PermissionSet(),
    val change: PermissionSet = PermissionSet()
)
