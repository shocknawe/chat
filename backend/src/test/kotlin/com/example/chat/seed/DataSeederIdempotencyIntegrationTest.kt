package com.example.chat.seed

import com.example.chat.ChatApplication
import com.example.chat.repository.AppUserRepository
import com.example.chat.repository.ConversationRepository
import com.example.chat.repository.MessageRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * `add-conversation-creation-presence-inspector` slice 0, task 1.9 —
 * demo-seed-data spec, "Seeding is step-wise idempotent" and "Boot is
 * non-destructive against previously seeded databases".
 *
 * Boots three independent, full [ChatApplication] Spring contexts
 * sequentially against the *same* Testcontainers PostgreSQL instance,
 * mirroring [com.example.chat.persistence.RestartRetentionIntegrationTest]'s
 * restart simulation:
 *
 * 1. **First boot**: a normal boot, letting the current [DataSeeder] create
 *    the full seed set (Alice, Bob, Carol, Dan, Erin; the Alice&harr;Bob and
 *    Alice&harr;Carol conversations; the latter's three messages).
 * 2. Between boots, raw JDBC deletes remove exactly the rows this change
 *    *added* (Carol, Dan, Erin; the Alice&harr;Carol conversation and its
 *    messages), reconstructing the database shape an *earlier* version of
 *    the seeder would have left behind (only Alice, Bob, and the original
 *    conversation) — the closest reachable proxy, in this test environment,
 *    to "boot against a database seeded by an earlier version" without a
 *    real second Docker volume across two different code versions.
 * 3. **Second boot**: the current seeder must independently add back every
 *    missing entity without touching Alice, Bob, or the original
 *    conversation (which it never deleted) — proving no entity's seeding is
 *    gated on some other entity being absent.
 * 4. **Third boot**: running the (now fully caught-up) seeder again must not
 *    duplicate anything.
 */
@Testcontainers
class DataSeederIdempotencyIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("chat")
                .withUsername("chat")
                .withPassword("chat")
    }

    private fun boot(): ConfigurableApplicationContext =
        SpringApplicationBuilder(ChatApplication::class.java)
            .run(
                "--server.port=0",
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--spring.jpa.hibernate.ddl-auto=update",
            )

    /**
     * Deletes exactly the rows added by [DataSeeder] beyond the original
     * Alice/Bob/[SeedData.CONVERSATION_ID] seed, via a raw JDBC connection
     * (outside any Spring context), reconstructing the "earlier version"
     * database shape described in the class doc.
     */
    private fun deleteRowsAddedByThisChange() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "DELETE FROM message WHERE conversation_id = '${SeedData.ALICE_CAROL_CONVERSATION_ID}'",
                )
                statement.execute(
                    "DELETE FROM conversation_participant WHERE conversation_id = '${SeedData.ALICE_CAROL_CONVERSATION_ID}'",
                )
                statement.execute(
                    "DELETE FROM conversation WHERE id = '${SeedData.ALICE_CAROL_CONVERSATION_ID}'",
                )
                statement.execute(
                    "DELETE FROM app_user WHERE id IN " +
                        "('${SeedData.CAROL_ID}', '${SeedData.DAN_ID}', '${SeedData.ERIN_ID}')",
                )
            }
        }
    }

    @Test
    fun `later-added seed data reaches a database seeded by an earlier version, idempotently`() {
        // 1. First boot: full current seed set.
        boot().close()

        // 2. Reconstruct the "earlier version" shape.
        deleteRowsAddedByThisChange()

        // 3. Second boot: independent context, current seeder, against the
        // reconstructed "earlier" database.
        val secondBoot = boot()
        try {
            val appUserRepository = secondBoot.getBean(AppUserRepository::class.java)
            val conversationRepository = secondBoot.getBean(ConversationRepository::class.java)
            val messageRepository = secondBoot.getBean(MessageRepository::class.java)

            // Pre-existing ids and display names are unchanged.
            val alice = appUserRepository.findById(SeedData.ALICE_ID).orElseThrow()
            assertThat(alice.displayName).isEqualTo(SeedData.ALICE_DISPLAY_NAME)
            val bob = appUserRepository.findById(SeedData.BOB_ID).orElseThrow()
            assertThat(bob.displayName).isEqualTo(SeedData.BOB_DISPLAY_NAME)
            assertThat(conversationRepository.findById(SeedData.CONVERSATION_ID)).isPresent()

            // The Alice<->Bob conversation remains empty.
            assertThat(messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(SeedData.CONVERSATION_ID))
                .isEmpty()

            // Later-added seed data was added back.
            assertThat(appUserRepository.findById(SeedData.CAROL_ID)).isPresent()
            assertThat(appUserRepository.findById(SeedData.DAN_ID)).isPresent()
            assertThat(appUserRepository.findById(SeedData.ERIN_ID)).isPresent()

            assertThat(conversationRepository.findById(SeedData.ALICE_CAROL_CONVERSATION_ID)).isPresent()
            // `open-in-view` is `false` and this boot holds no transaction, so the
            // lazy `participants` collection is checked via a query projection
            // (`findByParticipants_Id`) rather than by touching the association
            // directly, which would throw `LazyInitializationException`.
            assertThat(conversationRepository.findByParticipants_Id(SeedData.ALICE_ID).map { it.id })
                .contains(SeedData.ALICE_CAROL_CONVERSATION_ID)
            assertThat(conversationRepository.findByParticipants_Id(SeedData.CAROL_ID).map { it.id })
                .contains(SeedData.ALICE_CAROL_CONVERSATION_ID)

            val aliceCarolHistory =
                messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(SeedData.ALICE_CAROL_CONVERSATION_ID)
            assertThat(aliceCarolHistory).hasSize(3)
            assertThat(aliceCarolHistory.map { it.clientMessageId }).containsExactly(
                SeedData.ALICE_CAROL_MESSAGE_1_CLIENT_ID,
                SeedData.ALICE_CAROL_MESSAGE_2_CLIENT_ID,
                SeedData.ALICE_CAROL_MESSAGE_3_CLIENT_ID,
            )
            assertThat(aliceCarolHistory.map { it.createdAt }).containsExactly(
                SeedData.ALICE_CAROL_MESSAGE_1_CREATED_AT,
                SeedData.ALICE_CAROL_MESSAGE_2_CREATED_AT,
                SeedData.ALICE_CAROL_MESSAGE_3_CREATED_AT,
            )
        } finally {
            secondBoot.close()
        }

        // 4. Third boot: the seeder is now fully caught up; running it again
        // must not duplicate anything.
        val thirdBoot = boot()
        try {
            val appUserRepository = thirdBoot.getBean(AppUserRepository::class.java)
            val messageRepository = thirdBoot.getBean(MessageRepository::class.java)

            assertThat(appUserRepository.count()).isEqualTo(5)
            assertThat(messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(SeedData.ALICE_CAROL_CONVERSATION_ID))
                .hasSize(3)
            assertThat(messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(SeedData.CONVERSATION_ID))
                .isEmpty()
        } finally {
            thirdBoot.close()
        }
    }
}
