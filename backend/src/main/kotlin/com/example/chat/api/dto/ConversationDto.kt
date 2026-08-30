package com.example.chat.api.dto

import java.util.UUID

/** REST shape for `GET /api/conversations` (conversations spec). */
data class ConversationDto(
    val id: UUID,
    val participants: List<UserDto>,
)
