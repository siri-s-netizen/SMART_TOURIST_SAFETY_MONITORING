package com.touristsafety.app.data.mapper

import com.touristsafety.app.data.database.entity.EmergencyContactEntity
import com.touristsafety.app.domain.model.EmergencyContact

fun EmergencyContactEntity.toDomain(): EmergencyContact = EmergencyContact(
    id = id,
    name = name,
    phone = phone,
    relationship = relationship,
    priority = priority,
)

fun EmergencyContact.toEntity(updatedAtMillis: Long): EmergencyContactEntity = EmergencyContactEntity(
    id = id,
    name = name,
    phone = phone,
    relationship = relationship,
    priority = priority,
    updatedAt = updatedAtMillis,
)
