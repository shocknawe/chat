package com.example.chat.repository

import com.example.chat.domain.Conversation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConversationRepository : JpaRepository<Conversation, UUID> {

    /**
     * Conversations in which [userId] participates (conversations spec:
     * "Conversations are scoped to the participating user"). Derived from
     * the `participants` many-to-many collection on [Conversation].
     */
    fun findByParticipants_Id(userId: UUID): List<Conversation>
}
