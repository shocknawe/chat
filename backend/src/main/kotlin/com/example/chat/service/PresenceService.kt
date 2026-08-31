package com.example.chat.service

import com.example.chat.repository.ConversationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Resolves the conversation-partner sets behind presence scoping
 * (add-conversation-creation-presence-inspector task 5.2, design.md decision
 * 4).
 *
 * Presence *state* lives in the live [com.example.chat.ws.ConnectionRegistry];
 * this service deliberately knows nothing about connections. Its only job is
 * the database half of the privacy boundary: for a user, the ids of everyone
 * they share at least one conversation with. The caller
 * ([com.example.chat.ws.PresenceBroadcaster]) intersects that set with the
 * registry's live online set.
 *
 * Every method is `@Transactional(readOnly = true)` for a concrete reason,
 * not decoration: transitions fire on a WebSocket thread with **no**
 * transaction, `spring.jpa.open-in-view` is `false`, and the lazy
 * `participants` association would throw `LazyInitializationException` the
 * moment it was touched outside one. The query therefore projects partner
 * **ids** directly -- a scalar projection, no entities and no collections to
 * initialize -- so one round trip answers the whole question and there is no
 * lazy association left to touch.
 *
 * Called on the presence broadcaster's single-threaded executor, never inside
 * a connection-registry lock (spec: "Presence evaluation never blocks
 * connection bookkeeping").
 */
@Service
class PresenceService(
    private val conversationRepository: ConversationRepository,
) {

    /**
     * The distinct ids of every user who shares at least one conversation with
     * [userId], excluding [userId] themself -- a user is never their own
     * conversation partner, so they never appear in their own presence
     * snapshot, and their own online state is never announced back to them.
     *
     * Conversations of which [userId] is *not* a participant contribute
     * nothing (the `exists` subquery filters on the same [Conversation]); ids
     * of offline users are returned too -- liveness is the registry's half of
     * the answer.
     */
    @Transactional(readOnly = true)
    fun partnerIdsOf(userId: UUID): Set<UUID> = conversationRepository.findPartnerIds(userId).toSet()
}