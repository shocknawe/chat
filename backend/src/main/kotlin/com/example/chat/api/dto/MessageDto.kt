package com.example.chat.api.dto

import java.time.Instant
import java.util.UUID

/**
 * The authoritative REST-facing message shape (design.md, the DTO shape
 * note): `{ id, conversationId, senderId, content, createdAt }`.
 *
 * `clientMessageId` is deliberately NOT exposed here — design.md: it
 * "remains an internal persistence/deduplication field", returned instead in
 * a WebSocket `MESSAGE_ACK` event (a later work package), never in this DTO
 * or in REST history.
 */
data class MessageDto(
    val id: UUID,
    val conversationId: UUID,
    val senderId: UUID,
    val content: String,
    val createdAt: Instant,
)
