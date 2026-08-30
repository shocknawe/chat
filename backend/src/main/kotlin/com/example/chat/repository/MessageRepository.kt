package com.example.chat.repository

import com.example.chat.domain.Message
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Simple Spring Data JPA repository — no complex repository abstractions
 * (design.md, Non-Goals), just derived queries with parameter binding.
 */
interface MessageRepository : JpaRepository<Message, UUID> {

    /**
     * A conversation's message history in deterministic chronological
     * order: `(createdAt ASC, id ASC)`. The id tiebreak matters because the
     * UUID is not assumed chronological — it is only a stable secondary key
     * when timestamps collide (design.md, "Data model and ordering").
     */
    fun findByConversation_IdOrderByCreatedAtAscIdAsc(conversationId: UUID): List<Message>

    /**
     * Lookup by the database-enforced idempotency key
     * `(sender_id, client_message_id)` (design.md, "Idempotency for
     * duplicate SEND_MESSAGE"). Used to detect duplicate/replayed
     * `SEND_MESSAGE` commands before — and, ultimately, relying on the
     * unique constraint rather than this check alone — after a concurrent
     * insert attempt.
     */
    fun findBySender_IdAndClientMessageId(senderId: UUID, clientMessageId: UUID): Message?
}
