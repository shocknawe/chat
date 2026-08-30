package com.example.chat.ws.protocol

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

/**
 * Client -> server WebSocket commands, modelled as a Kotlin sealed interface
 * discriminated on a JSON `type` property (design.md: "Explicit protocol
 * modelled as sealed Kotlin types").
 *
 * Deliberately the ONLY inbound command for the MVP is [SendMessageCommand],
 * which has no `senderId` field: the sender is always derived from the
 * validated WebSocket connection identity (bound during the handshake, see
 * `com.example.chat.ws.UserIdHandshakeInterceptor`), never from command JSON
 * (design.md: "The handler derives the sender only from that validated
 * connection identity, never from command JSON"). Because the shared Jackson
 * `ObjectMapper` is configured with `fail-on-unknown-properties: false`
 * (see `application.yml`), a client that mistakenly includes an extra
 * `senderId` field in the payload has it silently ignored rather than
 * rejected or trusted.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = SendMessageCommand::class, name = "SEND_MESSAGE"),
)
sealed interface InboundCommand

/**
 * Request to create a new message. [clientMessageId] is a client-generated
 * correlation/idempotency token only (design.md: "Server-authoritative
 * messages, client id is a correlation and idempotency token only") -- it is
 * never the authoritative message id.
 */
data class SendMessageCommand(
    val clientMessageId: UUID,
    val conversationId: UUID,
    val content: String,
) : InboundCommand
