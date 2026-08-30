package com.example.chat.service

import com.example.chat.api.dto.ConversationDto
import com.example.chat.api.dto.UserDto
import com.example.chat.domain.Conversation
import com.example.chat.repository.AppUserRepository
import com.example.chat.repository.ConversationRepository
import com.example.chat.api.exception.ParticipantNotFoundException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private fun Conversation.toDto(): ConversationDto = ConversationDto(
    id = id,
    participants = participants
        .map { UserDto(id = it.id, displayName = it.displayName) }
        .sortedBy { it.displayName },
)

/**
 * The sole transactional write path for conversation creation, deliberately
 * kept in its own Spring bean rather than a method on [ConversationService]
 * (add-conversation-creation-presence-inspector design.md decision 2, task 3.3
 * — mirroring the proven [MessageWriter] split).
 *
 * Spring's `@Transactional` is proxy-based: a call from another method *within
 * the same class* bypasses the proxy entirely and runs with no transaction
 * demarcation at all (the classic self-invocation pitfall). Splitting the
 * transactional write into a separate bean forces every call through the
 * Spring-managed proxy, so [createAndCommit] genuinely opens, and — on normal
 * return — commits, its own transaction before control returns to
 * [ConversationService.createConversation]. That is what makes both post-commit
 * properties of the call structure rather than of a comment:
 *
 * - "After commit" emission (task 3.4): the `CONVERSATION_CREATED` event for
 *   the other participant is only ever sent after this method has already
 *   returned, i.e. after the conversation row is durable.
 * - Race recovery (task 3.3): when a concurrent create of the same
 *   [Conversation.pairKey] wins, the unique constraint
 *   `uq_conversation_pair_key` rejects the insert here, and the unchecked
 *   exception crossing this proxy boundary rolls *this* transaction back —
 *   so the catch/re-read in [ConversationService.createConversation] runs in
 *   a genuinely fresh transaction, never inside the doomed one (re-reading
 *   inside it would throw `TransactionRequiredException` /
 *   `UnexpectedRollbackException`, not a fix).
 *
 * [createAndCommit] uses `saveAndFlush` (not `save`) so the INSERT — and with
 * it the `uq_conversation_pair_key` check — happens now, inside this
 * transaction: the loser of the race gets its
 * `DataIntegrityViolationException` *here*, synchronously, instead of at a
 * later flush it could not tie to this call.
 */
@Component
class ConversationWriter(
    private val conversationRepository: ConversationRepository,
    private val appUserRepository: AppUserRepository,
) {

    /**
     * Inserts one 1:1 conversation for [callerId] + [participantId] and
     * commits. Both users are re-resolved inside the transaction: the caller
     * was authenticated moments before (a missing caller therefore means it
     * disappeared mid-request, in this MVP's five-user directory an
     * untestable deletion race) and the participant's directory membership is
     * the `404` this exception maps to.
     */
    @Transactional
    fun createAndCommit(callerId: UUID, participantId: UUID, pairKey: String): ConversationDto {
        val caller = appUserRepository.findById(callerId).orElse(null)
            ?: throw ParticipantNotFoundException(callerId)
        val participant = appUserRepository.findById(participantId).orElse(null)
            ?: throw ParticipantNotFoundException(participantId)

        val conversation = Conversation(id = UUID.randomUUID(), pairKey = pairKey).apply {
            participants += caller
            participants += participant
        }

        // saveAndFlush, not save: forces the INSERT (and hence the
        // uq_conversation_pair_key check) to happen now, inside this
        // transaction, so a losing concurrent duplicate throws here rather
        // than at some later, harder-to-attribute flush point.
        val saved = conversationRepository.saveAndFlush(conversation)
        return saved.toDto()
    }
}