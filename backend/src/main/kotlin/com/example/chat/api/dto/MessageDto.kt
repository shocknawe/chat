package com.example.chat.api.dto

import java.time.Instant
import java.util.UUID

/**
 * The authoritative REST-facing message shape: `{ id, conversationId,
 * senderId, clientMessageId, content, createdAt }`.
 *
 * `clientMessageId` was originally deliberately NOT exposed here — design.md
 * described it as remaining "an internal persistence/deduplication field",
 * returned instead only in a WebSocket `MESSAGE_ACK` event, never in this DTO
 * or in REST history. **That decision is reversed**
 * (`add-conversation-creation-presence-inspector` design.md decision 6,
 * `docs/openapi.yaml`'s `Message` schema): the inspector needs received
 * messages and post-reload history to show their honest correlation token
 * instead of a fabricated one, so the token is exposed everywhere a message
 * appears.
 *
 * Exposure changes nothing about the token's authority: [clientMessageId] is
 * scoped to its sender (uniqueness is `(senderId, clientMessageId)` under
 * `uq_message_sender_client_message_id`, so the same token legitimately names
 * different messages for different senders) and remains **non-authoritative**
 * — a per-sender idempotency key only, usable by no one but its own sender to
 * retry/deduplicate. [id] stays the authoritative identifier.
 */
data class MessageDto(
    val id: UUID,
    val conversationId: UUID,
    val senderId: UUID,
    val clientMessageId: UUID,
    val content: String,
    val createdAt: Instant,
)
