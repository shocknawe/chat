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
     * A conversation's latest message — the `lastMessage` rail preview carried
     * by [com.example.chat.api.dto.ConversationDto] in listing and creation
     * responses (add-conversation-creation-presence-inspector design.md
     * decision 5). Null exactly when the conversation's history is empty.
     *
     * Ordering: `CreatedAtDescIdDesc` is the documented history ordering
     * (`findByConversation_IdOrderByCreatedAtAscIdAsc`'s `(createdAt ASC,
     * id ASC)`) reversed on *both* keys. Because the reversal of a total order
     * is again that total order, the first row of `DESC, DESC` is the same
     * element as the last row of `ASC, ASC` — including under a `createdAt`
     * tie, where the tiebreak flips together with the primary key, so "latest"
     * is consistently the greater id in both formulations. The preview can
     * therefore never disagree with the tail of the history response.
     *
     * Served by the existing `idx_message_conversation_created_at_id`
     * composite index as a backward index scan (`ORDER BY … DESC LIMIT 1` on
     * the exact column sequence the index declares) — no sort, no full
     * history read, and no denormalized `last_message_id` (design.md decision
     * 5 deliberately defers that write-ordering complexity).
     */
    fun findFirstByConversation_IdOrderByCreatedAtDescIdDesc(conversationId: UUID): Message?

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
