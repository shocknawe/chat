package com.example.chat.api.dto

import java.time.Instant
import java.util.UUID

/**
 * The authoritative REST-facing message shape (design.md, the DTO shape
 * note): `{ id, conversationId, senderId, content, createdAt }`.
 *
 * `clientMessageId` was originally deliberately NOT exposed here — design.md
 * described it as remaining "an internal persistence/deduplication field",
 * returned instead only in a WebSocket `MESSAGE_ACK` event, never in this DTO
 * or in REST history. **That decision is reversed**
 * (`add-conversation-creation-presence-inspector` design.md decision 6,
 * `docs/openapi.yaml`'s `Message` schema): the inspector needs received
 * messages and post-reload history to show their honest correlation token
 * instead of a fabricated one, so the token is being exposed everywhere a
 * message appears. Exposure is **planned but not yet implemented on this
 * class** — this is a contract/documentation change only (Slice 0 of that
 * change); adding the field here is Slice 6.
 */
data class MessageDto(
    val id: UUID,
    val conversationId: UUID,
    val senderId: UUID,
    val content: String,
    val createdAt: Instant,
)
