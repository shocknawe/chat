package com.example.chat.api.dto

import java.util.UUID

/**
 * REST shape for `GET /api/conversations` and for the `conversation` payload of
 * a WebSocket `CONVERSATION_CREATED` event (conversations spec) — the same
 * entity the listing returns is the one the event announces.
 *
 * `lastMessage` is the conversation's latest message under the documented
 * history ordering (`createdAt ASC, id ASC`), i.e. exactly the last element a
 * `GET /api/conversations/{id}/messages` response would return, so that rail
 * preview and thread history are two views of one server-ordered fact that
 * cannot disagree (add-conversation-creation-presence-inspector design.md
 * decision 5). It is computed, not denormalized: one indexed lookup per
 * conversation at assembly time.
 *
 * Wire form of the empty preview (task 4.0 decision): **absent from the JSON,
 * by omission — never a literal `null`.** Rationale:
 * - `spring.jackson.default-property-inclusion: non_null` is set globally, and
 *   under it a `null` [lastMessage] is simply dropped from the serialised
 *   object. This DTO deliberately does **not** opt out of the global rule (no
 *   `@JsonInclude(ALWAYS)` on the property): absent is the cheaper and just as
 *   unambiguous of the two wire forms, and accepting the global rule means the
 *   empty-history shape can never drift from whatever the configured
 *   `ObjectMapper` actually does — there is no per-field annotation quietly
 *   disagreeing with the app-wide setting.
 * - A property with nothing to say should not be spoken: `"lastMessage": null`
 *   would only add bytes and a second, indistinguishable-at-the-type-level
 *   representation of the same "no messages yet" fact.
 * - Consumers MUST treat an absent property and a literal `null` identically
 *   (the frontend does, in both this DTO and `CONVERSATION_CREATED`), so a
 *   future serializer change in either direction remains compatible without a
 *   contract bump.
 * Consequently `docs/openapi.yaml` declares `lastMessage` as an *optional*
 * property (not listed under `required`) whose value is a [MessageDto] when
 * present — it does not declare the value as `null`-able, because a literal
 * `null` is never produced on the wire.
 */
data class ConversationDto(
    val id: UUID,
    val participants: List<UserDto>,
    val lastMessage: MessageDto? = null,
)