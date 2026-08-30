package com.example.chat.service

import com.example.chat.api.dto.ConversationDto
import com.example.chat.api.dto.MessageDto
import com.example.chat.api.dto.UserDto
import com.example.chat.api.exception.CallerIsParticipantException
import com.example.chat.api.exception.ConversationNotFoundException
import com.example.chat.api.exception.NotConversationParticipantException
import com.example.chat.api.exception.ParticipantNotFoundException
import com.example.chat.domain.Conversation
import com.example.chat.domain.Message
import com.example.chat.repository.AppUserRepository
import com.example.chat.repository.ConversationRepository
import com.example.chat.repository.MessageRepository
import com.example.chat.ws.ConnectionRegistry
import com.example.chat.ws.protocol.ConversationCreated
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The outcome of a `POST /api/conversations` request, mapped by the controller
 * to the contract's two success statuses (design.md decision 3: "idempotent,
 * never 409" — the intent "open a conversation with this person" is satisfied
 * either way, and the frontend treats both identically).
 */
sealed interface CreateResult {
    /** A brand-new conversation row was created and committed → `201`. */
    data class Created(val conversation: ConversationDto) : CreateResult

    /** A conversation already existed for this pair (found up front, or discovered
     *  by re-read after losing the concurrent-create race) → `200`. The caller is
     *  never notified over the socket in this case: the request that created the
     *  conversation already emitted the event. */
    data class Existing(val conversation: ConversationDto) : CreateResult
}

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
 *
 * Creation (tasks 3.2/3.3/3.4) deliberately runs with **no** class-level or
 * method-level transaction on [createConversation]: the write lives in the
 * separate [ConversationWriter] bean (see its doc for why), the existence
 * pre-check runs in the repository's own short read-only transaction, and the
 * race-recovery re-read must run in a *fresh* transaction because the writer's
 * transaction is already rolled back when its
 * [DataIntegrityViolationException] reaches this method. A transactional
 * orchestration method would join the writer's doomed transaction instead of
 * standing outside it, which is precisely the failure the MessageWriter-style
 * split exists to avoid.
 */
@Service
class ConversationService(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val appUserRepository: AppUserRepository,
    private val conversationWriter: ConversationWriter,
    private val connectionRegistry: ConnectionRegistry,
) {

    private val log = LoggerFactory.getLogger(ConversationService::class.java)

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

    /**
     * Creates (or finds) the 1:1 conversation between [callerId] and
     * [participantId]. See class doc for the deliberate absence of a
     * transaction here, and [ConversationWriter] for the write half.
     *
     * Request validation before any write attempt (conversations-creation
     * spec, "Malformed creation requests are rejected"):
     * - [CallerIsParticipantException] → `400`: a conversation with oneself is
     *   not a supported shape. There is no `403` on this endpoint — the caller
     *   is implicit, so a request can never name a conversation the caller is
     *   not part of (design.md decision 3).
     * - [ParticipantNotFoundException] → `404`: `participantId` is a
     *   well-formed UUID but not a directory user. This is a *different* 404
     *   from the history endpoint's (missing conversation) — the distinction
     *   is documented explicitly in `docs/openapi.yaml`.
     *
     * Idempotency is database-backed, not check-then-insert only: the
     * `findByPairKey` pre-check is a best-effort fast path that can always
     * race and miss — `uq_conversation_pair_key` is the final arbiter, exactly
     * as the `(sender_id, client_message_id)` constraint is for messages. On
     * losing that race the insert's transaction is already rolled back (see
     * [ConversationWriter]), so the re-read below runs fresh and answers the
     * losing request `200` — both requests succeed, exactly one row exists
     * (task 3.3).
     *
     * `CONVERSATION_CREATED` (task 3.4) is emitted *after*
     * [ConversationWriter.createAndCommit] has returned, i.e. after the
     * creating transaction has committed — the same commit-before-ack ordering
     * the message path guarantees — and only to the *other* participant's
     * connections: the creator already holds this REST response, and the
     * 200-existing path (including the race-lost one) emits nothing, because
     * the request that created it already emitted its event.
     */
    fun createConversation(callerId: UUID, participantId: UUID): CreateResult {
        if (callerId == participantId) {
            throw CallerIsParticipantException(callerId)
        }

        if (!appUserRepository.existsById(participantId)) {
            throw ParticipantNotFoundException(participantId)
        }

        val pairKey = Conversation.pairKeyFor(callerId, participantId)

        // Best-effort fast path for the common case, NOT the source of truth
        // for correctness (see above).
        conversationRepository.findByPairKey(pairKey)?.let { return CreateResult.Existing(it.toDto()) }

        return try {
            val conversation = conversationWriter.createAndCommit(callerId, participantId, pairKey)
            // The writer's transaction committed when it returned (its own
            // bean, its own proxy — see ConversationWriter's doc), so this
            // fan-out already reflects durable state. Only the other
            // participant is notified: the creator holds the REST response of
            // this very request and must not also receive a socket event for
            // it (conversations-creation spec, "Creator receives no event").
            connectionRegistry.sendToUser(participantId, ConversationCreated(conversation))
            CreateResult.Created(conversation)
        } catch (ex: DataIntegrityViolationException) {
            // Lost the concurrent-create race: the winner's insert committed
            // first, the constraint rejected ours, and our transaction is
            // rolled back. Postgres unique-constraint enforcement blocks a
            // conflicting insert until the first inserter's transaction
            // resolves, so by the time our insert fails, the winner's row is
            // committed and visible to this brand-new read.
            log.debug(
                "Lost concurrent-create race for pairKey={}; re-reading the committed conversation",
                pairKey,
            )
            val existing = conversationRepository.findByPairKey(pairKey)
            if (existing == null) {
                // Only reachable if the constraint fired for something other
                // than the pair key (never observed) or the winner's row
                // vanished. Fail loudly rather than fabricate a conversation.
                log.error(
                    "uq_conversation_pair_key violated for pair {} but no conversation found on re-read",
                    pairKey,
                    ex,
                )
                throw ex
            }
            CreateResult.Existing(existing.toDto())
        }
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