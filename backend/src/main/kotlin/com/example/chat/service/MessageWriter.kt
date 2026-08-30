package com.example.chat.service

import com.example.chat.api.dto.MessageDto
import com.example.chat.domain.Message
import com.example.chat.repository.ConversationRepository
import com.example.chat.repository.MessageRepository
import com.example.chat.ws.protocol.SendMessageCommand
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The outcome of attempting to persist a new message inside one transaction. */
sealed interface WriteResult {
    data class Created(val message: MessageDto, val participantIds: Set<UUID>) : WriteResult
    data object ConversationNotFound : WriteResult
    data object NotParticipant : WriteResult
}

/**
 * The sole transactional write path for message creation, deliberately kept
 * in its own Spring bean rather than a method on [MessageService].
 *
 * Spring's `@Transactional` is proxy-based: a call from another method
 * *within the same class* bypasses the proxy entirely and runs with no
 * transaction demarcation at all (the classic Kotlin/Java self-invocation
 * pitfall). Splitting the transactional write into a separate bean forces
 * every call to go through the Spring-managed proxy, so [createAndCommit]
 * genuinely opens, and -- on normal return -- commits, its own transaction
 * before control returns to [MessageService.send]. That is what makes
 * "commit before acknowledgement or fan-out" (design.md) a property of the
 * call structure, not just a comment: [MessageService.send] only emits
 * `MESSAGE_ACK`/`NEW_MESSAGE` after this method has already returned, i.e.
 * after the transaction has already committed.
 *
 * [createAndCommit] uses `saveAndFlush` (not `save`) so that a concurrent
 * duplicate `(sender_id, client_message_id)` unique-constraint violation
 * surfaces synchronously, inside this method, as a
 * [DataIntegrityViolationException] -- rather than being silently deferred
 * to a later flush the caller might not observe as tied to this call.
 */
@Component
class MessageWriter(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
) {

    @Transactional
    fun createAndCommit(senderId: UUID, command: SendMessageCommand): WriteResult {
        val conversation = conversationRepository.findById(command.conversationId).orElse(null)
            ?: return WriteResult.ConversationNotFound

        val sender = conversation.participants.find { it.id == senderId }
            ?: return WriteResult.NotParticipant

        val message = Message(
            id = UUID.randomUUID(),
            conversation = conversation,
            sender = sender,
            clientMessageId = command.clientMessageId,
            content = command.content,
            // Truncated to microseconds: Postgres `timestamp` columns store
            // microsecond precision, so an untruncated `Instant.now()`
            // (nanosecond precision on most JVMs) would round-trip through
            // the database as a *different* value than the one echoed here
            // in the immediate MESSAGE_ACK -- a prior reviewer flagged this
            // exact nanos-vs-micros mismatch between the ack and later REST
            // history.
            createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
        )

        // saveAndFlush, not save: forces the INSERT (and any unique
        // constraint check) to happen now, inside this transaction, so a
        // losing concurrent duplicate throws here rather than at some later,
        // harder-to-attribute flush point.
        val saved = messageRepository.saveAndFlush(message)

        val participantIds = conversation.participants.map { it.id }.toSet()
        return WriteResult.Created(saved.toMessageDto(), participantIds)
    }
}

internal fun Message.toMessageDto(): MessageDto = MessageDto(
    id = id,
    conversationId = conversation.id,
    senderId = sender.id,
    content = content,
    createdAt = createdAt,
)
