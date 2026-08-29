package com.example.chat.api.dto

import java.util.UUID

/** REST shape for `GET /api/users` (user-directory spec). */
data class UserDto(
    val id: UUID,
    val displayName: String,
)
