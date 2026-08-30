package com.example.chat.seed

import com.example.chat.domain.AppUser
import com.example.chat.domain.Conversation
import com.example.chat.repository.AppUserRepository
import com.example.chat.repository.ConversationRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Idempotently seeds the two fixed-id MVP demo users ([SeedData.ALICE_ID],
 * [SeedData.BOB_ID]) and their pre-created conversation
 * ([SeedData.CONVERSATION_ID]) on every application boot.
 *
 * "Idempotent" here means upsert-by-fixed-id: each entity is looked up by
 * its known id first and only created if absent, so repeated boots against
 * the same database never produce duplicate rows (design.md, "Docker
 * Compose topology").
 */
@Component
class DataSeeder(
    private val appUserRepository: AppUserRepository,
    private val conversationRepository: ConversationRepository,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(DataSeeder::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        val alice = findOrCreateUser(SeedData.ALICE_ID, SeedData.ALICE_DISPLAY_NAME)
        val bob = findOrCreateUser(SeedData.BOB_ID, SeedData.BOB_DISPLAY_NAME)

        if (conversationRepository.existsById(SeedData.CONVERSATION_ID)) {
            log.debug("Seed conversation {} already present; skipping", SeedData.CONVERSATION_ID)
            return
        }

        val conversation = Conversation(id = SeedData.CONVERSATION_ID)
        conversation.participants += alice
        conversation.participants += bob
        conversationRepository.save(conversation)
        log.info(
            "Seeded MVP conversation {} between '{}' and '{}'",
            SeedData.CONVERSATION_ID,
            alice.displayName,
            bob.displayName,
        )
    }

    private fun findOrCreateUser(id: java.util.UUID, displayName: String): AppUser {
        return appUserRepository.findById(id).orElseGet {
            log.info("Seeding MVP user {} ('{}')", id, displayName)
            appUserRepository.save(AppUser(id = id, displayName = displayName))
        }
    }
}
