package com.example.chat.api.exception

import java.util.UUID

/**
 * A validly authenticated user requested a conversation in which they do not
 * participate — mapped to `403 Forbidden`.
 *
 * Distinct from an authentication failure (`401`, see
 * [com.example.chat.security.RestAuthenticationEntryPoint]): this exception
 * is only ever thrown once an [com.example.chat.security.AuthenticatedUser]
 * has already been established.
 */
class NotConversationParticipantException(val conversationId: UUID, val userId: UUID) :
    RuntimeException("User $userId is not a participant of conversation $conversationId")
