package com.example.chat.ws.protocol

import com.example.chat.api.dto.ConversationDto
import com.example.chat.api.dto.MessageDto
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

/**
 * Server -> client WebSocket events, modelled as a Kotlin sealed interface
 * discriminated on a JSON `type` property, mirroring [InboundCommand]
 * (design.md: "Explicit protocol modelled as sealed Kotlin types").
 *
 * Three of the events reuse the authoritative REST [MessageDto] shape
 * (`{ id, conversationId, senderId, clientMessageId, content, createdAt }`);
 * `CONVERSATION_CREATED` instead carries the REST [ConversationDto], for the
 * same reason — the entity the event announces is the same one the listing
 * returns. `clientMessageId` was originally carried alongside the message
 * only in [MessageAck] and [ErrorEvent] as a correlation token, never inside
 * the message payload itself (design.md, the DTO shape note). **That omission
 * is reversed** (`add-conversation-creation-presence-inspector` design.md
 * decision 6): the token lives on [MessageDto] itself, so every event
 * carrying a message — including `NEW_MESSAGE` for a message sent by another
 * participant — exposes it honestly. It remains per-sender-scoped and
 * non-authoritative (see [MessageDto]). [MessageAck.clientMessageId] and
 * [ErrorEvent.clientMessageId] remain at the event level for existing
 * consumers that correlate on them — on `MESSAGE_ACK` this is redundant with
 * `message.clientMessageId`, retained for compatibility.
 *
 * [Presence] is the one event that reuses no REST shape at all: online
 * presence exists only in the live connection registry, and its payload is a
 * bare list of user ids scoped per recipient.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MessageAck::class, name = "MESSAGE_ACK"),
    JsonSubTypes.Type(value = NewMessage::class, name = "NEW_MESSAGE"),
    JsonSubTypes.Type(value = ConversationCreated::class, name = "CONVERSATION_CREATED"),
    JsonSubTypes.Type(value = Presence::class, name = "PRESENCE"),
    JsonSubTypes.Type(value = ErrorEvent::class, name = "ERROR"),
)
sealed interface OutboundEvent

/**
 * Sent to the originating connection only, once (and only once) the message
 * transaction has committed (design.md: "Commit before acknowledgement or
 * fan-out"). [message] is the authoritative message: server-generated [id]
 * and `createdAt`, never the client-supplied identifiers.
 *
 * [clientMessageId] at the event level is redundant with
 * `message.clientMessageId` — both equal the token the originating command
 * carried. It is retained (and stays required on the wire) because existing
 * consumers correlate on it (`docs/openapi.yaml`, the `serverToClient`
 * events description).
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
 * Sent to every active connection of the *other* participant only, after
 * `POST /api/conversations` commits a *new* conversation
 * (add-conversation-creation-presence-inspector task 3.4; design.md decision
 * 3). Never to the creator's connections — the creator already holds the REST
 * response, so a duplicate event would only invite a double-add of the same
 * conversation — and never for the `200` (already-existed) case, including the
 * race-lost case where the winning request emitted the event when it created
 * the row.
 *
 * [conversation] reuses the authoritative REST [ConversationDto] shape and
 * always represents an empty history for exactly the same reason a fresh
 * conversation's listing does: it has no messages yet, so its (slice-3)
 * `lastMessage` is absent, in exactly the way the listing represents an empty
 * history.
 */
data class ConversationCreated(
    val conversation: ConversationDto,
) : OutboundEvent

/**
 * A complete replacement set of the online user identifiers scoped to *this*
 * recipient -- only users who share at least one conversation with the
 * recipient, and never the recipient themself (a user is not their own
 * conversation partner). Scoping is a privacy boundary, not a payload
 * optimization: presence is the one signal that would otherwise leak across
 * conversations the recipient is not part of.
 *
 * Delivered twice, in exactly two situations (add-conversation-creation-
 * presence-inspector design.md decision 4):
 * - as the **first event on a newly established connection** (a connect
 *   snapshot), and
 * - on a **presence transition** to every connected user sharing a
 *   conversation with the user whose state changed.
 *
 * [online] is always a complete snapshot, never a delta: the client replaces
 * whatever online set it held wholesale. Wholesale replacement is only
 * self-healing if snapshots arrive in the order they were computed, which the
 * server guarantees by computing *and* enqueueing every presence broadcast on
 * one single-threaded executor ([com.example.chat.ws.PresenceBroadcaster]) --
 * that total order is why the payload needs no sequence number.
 *
 * Empty is meaningful and is always serialized (never omitted, despite the
 * global `non_null` inclusion rule -- an empty list is non-null): it means
 * "none of this recipient's conversation partners are online".
 */
data class Presence(
    val online: List<UUID>,
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
