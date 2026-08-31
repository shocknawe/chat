package com.example.chat.seed

import com.example.chat.domain.AppUser
import com.example.chat.domain.Conversation
import com.example.chat.domain.Message
import com.example.chat.repository.AppUserRepository
import com.example.chat.repository.ConversationRepository
import com.example.chat.repository.MessageRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Idempotently seeds the fixed-id MVP demo directory (users, conversations,
 * and the Alice&harr;Carol message history) on every application boot.
 *
 * "Idempotent" here means upsert-by-fixed-id: each entity is looked up by
 * its known id (or, for messages, its known `(sender, clientMessageId)`
 * pair) first and only created if absent, so repeated boots against the
 * same database never produce duplicate rows (design.md, "Docker Compose
 * topology").
 *
 * Every seeded entity is evaluated **independently** — there is no early
 * return once some earlier entity is found to already exist. This matters
 * because the seed data has grown across changes: a database seeded by an
 * earlier version of this class (e.g. only Alice, Bob, and
 * [SeedData.CONVERSATION_ID]) must still receive every later addition
 * (Carol/Dan/Erin, the Alice&harr;Carol conversation, its messages) on the
 * next boot, rather than short-circuiting on the pre-existing conversation
 * (demo-seed-data spec, "Seeding is step-wise idempotent").
 *
 * The run ends with a repair pass for rows that predate later schema additions
 * ([backfillPairKeys], `add-conversation-creation-presence-inspector` task
 * 1.8): a retained database is caught up on every boot rather than by hand.
 *
 * Note that this class runs as an *unordered* `ApplicationRunner`, i.e. after
 * [com.example.chat.startup.PairKeyUniqueConstraintValidator] — deliberate:
 * the backfill must not write `pairKey` values into a table whose
 * pair-uniqueness constraint has not been asserted to exist.
 */
@Component
class DataSeeder(
    private val appUserRepository: AppUserRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(DataSeeder::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        val alice = findOrCreateUser(SeedData.ALICE_ID, SeedData.ALICE_DISPLAY_NAME)
        val bob = findOrCreateUser(SeedData.BOB_ID, SeedData.BOB_DISPLAY_NAME)
        val carol = findOrCreateUser(SeedData.CAROL_ID, SeedData.CAROL_DISPLAY_NAME)
        findOrCreateUser(SeedData.DAN_ID, SeedData.DAN_DISPLAY_NAME)
        findOrCreateUser(SeedData.ERIN_ID, SeedData.ERIN_DISPLAY_NAME)

        // Alice <-> Bob: the original MVP conversation. Deliberately left
        // with no messages so the empty-history preview and empty-thread
        // states remain reachable in the seeded demo (design.md decision 1).
        findOrCreateConversation(SeedData.CONVERSATION_ID, alice, bob)

        // Alice <-> Carol: seeded with a non-empty history so a rail preview
        // and a rendered date divider are both reachable.
        val aliceCarolConversation = findOrCreateConversation(SeedData.ALICE_CAROL_CONVERSATION_ID, alice, carol)
        findOrCreateMessage(
            conversation = aliceCarolConversation,
            sender = alice,
            clientMessageId = SeedData.ALICE_CAROL_MESSAGE_1_CLIENT_ID,
            content = "Hey Carol, are we still on for tomorrow?",
            createdAt = SeedData.ALICE_CAROL_MESSAGE_1_CREATED_AT,
        )
        findOrCreateMessage(
            conversation = aliceCarolConversation,
            sender = carol,
            clientMessageId = SeedData.ALICE_CAROL_MESSAGE_2_CLIENT_ID,
            content = "Yes! Looking forward to it.",
            createdAt = SeedData.ALICE_CAROL_MESSAGE_2_CREATED_AT,
        )
        findOrCreateMessage(
            conversation = aliceCarolConversation,
            sender = alice,
            clientMessageId = SeedData.ALICE_CAROL_MESSAGE_3_CLIENT_ID,
            content = "Great, see you then.",
            createdAt = SeedData.ALICE_CAROL_MESSAGE_3_CREATED_AT,
        )

        // Dan and Erin are seeded with no conversations at all, guaranteeing
        // at least one directory pair shares no conversation (demo-seed-data
        // spec, "Directory contains reachable new-conversation candidates").

        backfillPairKeys()
    }

    private fun findOrCreateUser(id: UUID, displayName: String): AppUser {
        return appUserRepository.findById(id).orElseGet {
            log.info("Seeding user {} ('{}')", id, displayName)
            appUserRepository.save(AppUser(id = id, displayName = displayName))
        }
    }

    private fun findOrCreateConversation(id: UUID, vararg participants: AppUser): Conversation {
        return conversationRepository.findById(id).orElseGet {
            // A 1:1 conversation is created with its pair key immediately, so
            // a freshly seeded database never relies on the backfill below;
            // group-shaped seeds (none today) would have no key to set.
            val conversation = Conversation(
                id = id,
                pairKey = participants
                    .takeIf { it.size == 2 }
                    ?.let { Conversation.pairKeyFor(it[0].id, it[1].id) },
            )
            conversation.participants += participants
            val saved = conversationRepository.save(conversation)
            log.info(
                "Seeded conversation {} between {}",
                id,
                participants.joinToString(", ") { it.displayName },
            )
            saved
        }
    }

    /**
     * `add-conversation-creation-presence-inspector` task 1.8 (deferred from
     * slice 0): fills in [Conversation.pairKey] on 1:1 conversations that were
     * persisted *before* that column existed, e.g. a database kept across the
     * upgrade (the retained docker-compose volume).
     *
     * Non-destructive and idempotent by construction: only rows whose
     * `pair_key` is still `NULL` are selected, and a row is only ever given
     * the canonical key of the participants it already has
     * ([Conversation.pairKeyFor] — the same computation the REST creation path
     * uses, so an inserted conversation and its backfilled twin cannot disagree).
     * Nothing is deleted, no id or participant changes, and on every boot after
     * the first this is a no-op query.
     *
     * Only exactly-two-participant conversations are keyed: `pair_key` models
     * *pair* uniqueness (a nullable unique column is forward-compatible with
     * group conversations precisely because they have no pair to be unique
     * about), so any other shape is left for its own change to decide.
     */
    private fun backfillPairKeys() {
        val backfilled = conversationRepository.findByPairKeyIsNull().count { conversation ->
            val participantIds = conversation.participants.map { it.id }
            if (participantIds.size != 2) return@count false
            conversation.pairKey = Conversation.pairKeyFor(participantIds[0], participantIds[1])
            conversationRepository.save(conversation)
            log.info(
                "Backfilled pairKey '{}' onto pre-existing conversation {}",
                conversation.pairKey,
                conversation.id,
            )
            true
        }
        if (backfilled > 0) {
            log.info("Backfilled pairKey onto {} pre-existing 1:1 conversation(s)", backfilled)
        }
    }

    private fun findOrCreateMessage(
        conversation: Conversation,
        sender: AppUser,
        clientMessageId: UUID,
        content: String,
        createdAt: Instant,
    ): Message {
        return messageRepository.findBySender_IdAndClientMessageId(sender.id, clientMessageId) ?: run {
            val message = Message(
                conversation = conversation,
                sender = sender,
                clientMessageId = clientMessageId,
                content = content,
                createdAt = createdAt,
            )
            val saved = messageRepository.save(message)
            log.info(
                "Seeded message {} ({} -> conversation {})",
                saved.id,
                sender.displayName,
                conversation.id,
            )
            saved
        }
    }
}
