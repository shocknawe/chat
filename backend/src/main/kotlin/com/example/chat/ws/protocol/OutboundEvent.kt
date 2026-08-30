package com.example.chat.ws.protocol

import com.example.chat.api.dto.MessageDto
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

/**
 * Server -> client WebSocket events, modelled as a Kotlin sealed interface
 * discriminated on a JSON `type` property, mirroring [InboundCommand]
 * (design.md: "Explicit protocol modelled as sealed Kotlin types").
 *
 * All three events reuse the authoritative REST [MessageDto] shape
 * (`{ id, conversationId, senderId, content, createdAt }`); `clientMessageId`
 * is carried alongside it only in [MessageAck] and [ErrorEvent] as a
 * correlation token, never inside the message payload itself (design.md,
 * the DTO shape note).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MessageAck::class, name = "MESSAGE_ACK"),
    JsonSubTypes.Type(value = NewMessage::class, name = "NEW_MESSAGE"),
    JsonSubTypes.Type(value = ErrorEvent::class, name = "ERROR"),
)
sealed interface OutboundEvent

/**
 * Sent to the originating connection only, once (and only once) the message
 * transaction has committed (design.md: "Commit before acknowledgement or
 * fan-out"). [message] is the authoritative message: server-generated [id]
 * and `createdAt`, never the client-supplied identifiers.
 */
data class MessageAck(
    val clientMessageId: UUID,
    val message: MessageDto,
) : OutboundEvent

/**
 * Sent to every other active connection belonging to either conversation
 * participant (including the sender's other sessions), but never to the
 * originating connection for the same command (design.md: "Origin receives
 * one authoritative delivery path").
 */
data class NewMessage(
    val message: MessageDto,
) : OutboundEvent

/**
 * A correlated protocol/validation/persistence error. [clientMessageId] is
 * included when the failing command carried one (e.g. a rejected
 * `SEND_MESSAGE`); it is absent for errors that occur before a command could
 * be parsed at all (e.g. malformed JSON). [code] is a stable machine-readable
 * identifier the frontend can branch on; [reason] is a human-readable detail
 * for logging/debugging only.
 */
data class ErrorEvent(
    val clientMessageId: UUID? = null,
    val code: String,
    val reason: String,
) : OutboundEvent
