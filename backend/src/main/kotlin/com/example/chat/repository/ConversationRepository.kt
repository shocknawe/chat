package com.example.chat.repository

import com.example.chat.domain.Conversation
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConversationRepository : JpaRepository<Conversation, UUID> {

    /**
     * Conversations in which [userId] participates (conversations spec:
     * "Conversations are scoped to the participating user"). Derived from
     * the `participants` many-to-many collection on [Conversation].
     */
    fun findByParticipants_Id(userId: UUID): List<Conversation>

    /**
     * The idempotency lookup behind `POST /api/conversations`
     * (add-conversation-creation-presence-inspector design.md decision 2):
     * a conversation already exists for this participant pair exactly when
     * this returns non-null.
     *
     * `participants` is fetched via an `@EntityGraph` because the query runs
     * *outside* any transaction (ConversationService.createConversation must
     * not be transactional — a constraint-violation catch must happen outside
     * the rolled-back writer transaction, design.md decision 2) and
     * `spring.jpa.open-in-view` is `false`, so an uninitialized lazy
     * `participants` collection would throw `LazyInitializationException` the
     * moment the caller maps it to a DTO.
     */
    @EntityGraph(attributePaths = ["participants"])
    fun findByPairKey(pairKey: String): Conversation?

    /**
     * Conversations that predate the `pair_key` column and still hold no value
     * — the backfill target for [com.example.chat.seed.DataSeeder]. Finding
     * nothing here is the idempotent steady state of every boot after the
     * first.
     */
    fun findByPairKeyIsNull(): List<Conversation>
}