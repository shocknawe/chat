package com.example.chat.api.dto

import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * Request body for `POST /api/conversations`
 * (`add-conversation-creation-presence-inspector` slice 2).
 *
 * There is deliberately **no field for the caller**: the caller is implicit —
 * resolved from the `X-User-Id` demo header by the security filter chain — so
 * no request can name a third pair member, and the endpoint has no `403`
 * (there is no "someone else's conversation" to be forbidden from creating;
 * the intent "open a conversation with this person" is satisfied regardless of
 * who asks, design.md decision 3).
 *
 * [participantId] is a non-null Kotlin property, so a body that omits it (or
 * carries an explicitly `null` value) fails Jackson deserialization through
 * jackson-module-kotlin as an `HttpMessageNotReadableException`, and a
 * non-UUID *string* fails the same way while binding. Both surface as `400`
 * (see `com.example.chat.api.exception.ApiExceptionHandler`); the Bean
 * Validation guard is kept for defense in depth and as the documented
 * validator if the type ever loosens, matching the repo standard of Bean
 * Validation on every request payload.
 */
data class CreateConversationRequest(
    @field:NotNull(message = "participantId is required")
    val participantId: UUID,
)