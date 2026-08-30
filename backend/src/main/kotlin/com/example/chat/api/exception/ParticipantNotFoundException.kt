package com.example.chat.api.exception

import java.util.UUID

/**
 * `POST /api/conversations` named a `participantId` that is not a directory
 * user — mapped to `404 Not Found` (conversations-creation spec, "Unknown
 * participant").
 *
 * Deliberately a distinct exception from [ConversationNotFoundException]: both
 * render as `404`, but the contract (`docs/openapi.yaml`) calls out that the
 * two `404`s mean different things — this one means "the *person* named in the
 * payload does not exist", where [ConversationNotFoundException] means "the
 * *conversation* named in the path does not exist". Separate types keep the
 * distinction expressible (and testable) instead of one 404 with two
 * meanings.
 */
class ParticipantNotFoundException(val participantId: UUID) :
    RuntimeException("participantId does not name a directory user.")