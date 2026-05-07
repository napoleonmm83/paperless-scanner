package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.Group as ApiGroup
import com.paperless.scanner.data.api.models.User as ApiUser
import com.paperless.scanner.domain.model.Group as DomainGroup
import com.paperless.scanner.domain.model.User as DomainUser

/**
 * Maps API User DTO to domain User model.
 */
fun ApiUser.toDomain(): DomainUser = DomainUser(
    id = id,
    username = username,
    email = email,
    firstName = firstName,
    lastName = lastName,
    isStaff = isStaff,
    isSuperuser = isSuperuser,
    isActive = isActive
)

/**
 * Maps API Group DTO to domain Group model.
 */
fun ApiGroup.toDomain(): DomainGroup = DomainGroup(
    id = id,
    name = name
)
