package com.example.chat.service

import com.example.chat.api.dto.ConversationDto
import com.example.chat.api.dto.MessageDto
import com.example.chat.api.dto.UserDto
import com.example.chat.api.exception.ConversationNotFoundException
import com.example.chat.api.exception.NotConversationParticipantException
import com.example.chat.domain.Conversation
import com.example.chat.domain.Message
import com.example.chat.repository.ConversationRepository
import com.example.chat.repository.MessageRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Backing service for the `conversations` capability (design.md,
 * "Data model and ordering"; conversations spec). Entities are mapped to
 * DTOs here, inside the transaction boundary, so lazily-fetched
 * associations (e.g. [Conversation.participants]) are never touched by the
 * web layer after the persistence context has closed
 * (`spring.jpa.open-in-view: false`).
 *
 * Authorization split (design.md, Risks: "enforce participant checks
 * server-side on every REST request"):
 * - [ConversationNotFoundException] — no such conversation → `404`.
 * - [NotConversationParticipantException] — a real, authenticated user who
 *   is not a participant → `403`. Never conflated with authentication
 *   failure (`401`), which is handled entirely upstream by Spring Security.
 */
@Service
class ConversationService(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
) {

    /** Conversations spec: "Conversations are scoped to the participating user". */
    @Transactional(readOnly = true)
    fun findConversationsForUser(userId: UUID): List<ConversationDto> =
        conversationRepository.findByParticipants_Id(userId).map { it.toDto() }

    /**
     * Conversations spec: "Message history is authorized by participation"
     * and "History is returned in deterministic chronological order".
     */
    @Transactional(readOnly = true)
    fun getMessageHistory(conversationId: UUID, userId: UUID): List<MessageDto> {
        val conversation = conversationRepository.findById(conversationId)
            .orElseThrow { ConversationNotFoundException(conversationId) }

        val isParticipant = conversation.participants.any { it.id == userId }
        if (!isParticipant) {
            throw NotConversationParticipantException(conversationId, userId)
        }

        return messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(conversationId)
            .map { it.toDto() }
    }
}

private fun Conversation.toDto(): ConversationDto = ConversationDto(
    id = id,
    participants = participants
        .map { UserDto(id = it.id, displayName = it.displayName) }
        .sortedBy { it.displayName },
)

private fun Message.toDto(): MessageDto = MessageDto(
    id = id,
    conversationId = conversation.id,
    senderId = sender.id,
    content = content,
    createdAt = createdAt,
)
