package com.example.chat.api.exception

import java.util.UUID

/** No conversation exists with the requested id — mapped to `404 Not Found`. */
class ConversationNotFoundException(val conversationId: UUID) :
    RuntimeException("Conversation $conversationId not found")
