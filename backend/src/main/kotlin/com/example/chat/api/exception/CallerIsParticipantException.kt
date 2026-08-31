package com.example.chat.api.exception

import java.util.UUID

/**
 * `POST /api/conversations` named the caller as the participant — mapped to
 * `400 Bad Request` (conversations-creation spec, "Participant is the caller":
 * a caller-named request is a client error, not a forbidden or missing
 * resource, and a conversation with oneself is not a supported shape).
 *
 * Not `403`: `403` is reserved for "a valid identity that is not a
 * participant" ([NotConversationParticipantException], history endpoint). Here
 * the caller *is* a participant of the would-be conversation — the request is
 * simply malformed as far as the pair semantics go, hence a client error.
 */
class CallerIsParticipantException(val callerId: UUID) :
    RuntimeException("participantId must not be the caller's own id.")