package com.example.chat.service

import com.example.chat.api.dto.MessageDto
import com.example.chat.config.MessagingProperties
import com.example.chat.domain.Message
import com.example.chat.repository.MessageRepository
import com.example.chat.ws.protocol.ErrorCodes
import com.example.chat.ws.protocol.SendMessageCommand
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

/** The outcome of a `SEND_MESSAGE` command, ready to be translated into an outbound event by the caller. */
sealed interface SendResult {
    /** A brand-new message was created and committed; fan out `NEW_MESSAGE` to [participantIds] other than the origin. */
    data class Created(val message: MessageDto, val participantIds: Set<UUID>) : SendResult

    /** A matching retry of an already-committed message (same conversation + content); re-ack the origin only, never re-broadcast. */
    data class DuplicateMatch(val message: MessageDto) : SendResult

    /** The command was rejected; [code] is one of [ErrorCodes]. */
    data class Rejected(val code: String, val reason: String) : SendResult
}

/**
 * Orchestrates `SEND_MESSAGE` handling end to end (design.md: "Implement
 * transactional `MessageService.send`"): sender identity is always the
 * caller-supplied [senderId] -- resolved by the connection layer from the
 * validated WebSocket identity, never from the command payload -- content is
 * validated before any persistence attempt, and idempotency is enforced
 * race-safely against the database, which is the final arbiter (design.md:
 * "Idempotency for duplicate SEND_MESSAGE").
 *
 * Deliberately NOT `@Transactional` itself: the actual write happens in the
 * separate [MessageWriter] bean so that a concurrent unique-constraint
 * violation is a normal exception thrown *back across a real Spring proxy
 * boundary* (see [MessageWriter]'s doc), and so that this method's own
 * lookups ([MessageRepository.findBySender_IdAndClientMessageId]) run in
 * their own fresh, independent transactions rather than joining (and being
 * rolled back with) the writer's failed transaction.
 */
@Service
class MessageService(
    private val messageWriter: MessageWriter,
    private val messageRepository: MessageRepository,
    private val messagingProperties: MessagingProperties,
) {

    private val log = LoggerFactory.getLogger(MessageService::class.java)

    fun send(senderId: UUID, command: SendMessageCommand): SendResult {
        validateContent(command.content)?.let { return it }

        // Best-effort pre-check: a fast path for the common case (genuine
        // retries of an already-committed command) that avoids a doomed
        // insert attempt. This is NOT the source of truth for correctness --
        // it can race with a concurrent identical command and miss -- the
        // database's unique constraint on (sender_id, client_message_id) is
        // the final arbiter (design.md), handled below.
        messageRepository.findBySender_IdAndClientMessageId(senderId, command.clientMessageId)
            ?.let { existing -> return resolveDuplicate(existing, command) }

        return try {
            when (val result = messageWriter.createAndCommit(senderId, command)) {
                is WriteResult.Created -> SendResult.Created(result.message, result.participantIds)
                WriteResult.ConversationNotFound ->
                    SendResult.Rejected(ErrorCodes.CONVERSATION_NOT_FOUND, "Conversation ${command.conversationId} not found")
                WriteResult.NotParticipant ->
                    SendResult.Rejected(ErrorCodes.FORBIDDEN, "Sender does not participate in conversation ${command.conversationId}")
            }
        } catch (ex: DataIntegrityViolationException) {
            // Lost the race: another thread's insert for this same
            // (senderId, clientMessageId) committed first, and the unique
            // constraint rejected ours. MessageWriter.createAndCommit's
            // transaction is now rolled back (unchecked exceptions crossing
            // a @Transactional proxy boundary trigger rollback), so this
            // re-read below must -- and, per SimpleJpaRepository's own
            // `@Transactional(readOnly = true)` on derived query methods,
            // does -- happen in a brand-new transaction, never the doomed
            // one. Postgres unique-constraint enforcement blocks a
            // conflicting insert until the first inserter's transaction
            // resolves, so by the time our insert fails, the winner's row is
            // already committed and visible here.
            log.debug(
                "Lost concurrent-duplicate race for sender={} clientMessageId={}; re-reading committed row",
                senderId,
                command.clientMessageId,
            )
            val existing = messageRepository.findBySender_IdAndClientMessageId(senderId, command.clientMessageId)
            if (existing == null) {
                log.error(
                    "Unique constraint violated for sender={} clientMessageId={} but no row found on re-read",
                    senderId,
                    command.clientMessageId,
                    ex,
                )
                SendResult.Rejected(ErrorCodes.PERSISTENCE_ERROR, "Failed to persist message due to an unexpected error")
            } else {
                resolveDuplicate(existing, command)
            }
        } catch (ex: Exception) {
            log.error(
                "Unexpected failure persisting message for sender={} clientMessageId={}",
                senderId,
                command.clientMessageId,
                ex,
            )
            SendResult.Rejected(ErrorCodes.PERSISTENCE_ERROR, "Failed to persist message due to an unexpected error")
        }
    }

    private fun validateContent(content: String): SendResult.Rejected? = when {
        content.isBlank() ->
            SendResult.Rejected(ErrorCodes.INVALID_CONTENT, "Content must not be empty or whitespace-only")
        // Count code points, not UTF-16 code units (`String.length`), so the
        // limit matches the unit Postgres `varchar(n)` enforces on the
        // Message.content column -- emoji outside the BMP count as one here,
        // exactly as the database counts them.
        content.codePointCount(0, content.length) > messagingProperties.maxContentLength ->
            SendResult.Rejected(
                ErrorCodes.INVALID_CONTENT,
                "Content exceeds maximum length of ${messagingProperties.maxContentLength} characters",
            )
        else -> null
    }

    /**
     * A row already exists for `(senderId, clientMessageId)`. If it matches
     * this command's conversation and content, it's a legitimate retry: ack
     * the existing authoritative message without creating a new row or
     * re-broadcasting (design.md: idempotency). Otherwise the token has been
     * reused for a genuinely different message -- never ack the wrong
     * message.
     */
    private fun resolveDuplicate(existing: Message, command: SendMessageCommand): SendResult {
        val sameConversation = existing.conversation.id == command.conversationId
        val sameContent = existing.content == command.content
        return if (sameConversation && sameContent) {
            SendResult.DuplicateMatch(existing.toMessageDto())
        } else {
            SendResult.Rejected(
                ErrorCodes.CLIENT_MESSAGE_ID_CONFLICT,
                "clientMessageId ${command.clientMessageId} was already used by this sender with different content or conversation",
            )
        }
    }
}
