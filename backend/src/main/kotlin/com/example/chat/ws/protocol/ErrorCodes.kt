package com.example.chat.ws.protocol

/**
 * Stable, machine-readable [ErrorEvent.code] values (design.md: "Protocol
 * errors use stable codes ... so the frontend does not branch on
 * human-readable `reason` text").
 */
object ErrorCodes {
    /** Payload was not valid JSON, used an unsupported/missing `type`, or otherwise failed to parse into a known [InboundCommand]. */
    const val INVALID_COMMAND = "INVALID_COMMAND"

    /** `SEND_MESSAGE.content` was empty, whitespace-only, or exceeded the configured maximum length. */
    const val INVALID_CONTENT = "INVALID_CONTENT"

    /** `SEND_MESSAGE.conversationId` does not reference an existing conversation. */
    const val CONVERSATION_NOT_FOUND = "CONVERSATION_NOT_FOUND"

    /** The connection's authenticated sender does not participate in the referenced conversation. */
    const val FORBIDDEN = "FORBIDDEN"

    /** `clientMessageId` was already used by this sender with a different conversation or content. */
    const val CLIENT_MESSAGE_ID_CONFLICT = "CLIENT_MESSAGE_ID_CONFLICT"

    /** An unexpected persistence/commit failure occurred; the message was NOT created. */
    const val PERSISTENCE_ERROR = "PERSISTENCE_ERROR"
}
