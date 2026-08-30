package com.example.chat.persistence

import com.example.chat.ChatApplication
import com.example.chat.domain.AppUser
import com.example.chat.domain.Conversation
import com.example.chat.domain.Message
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
import java.util.UUID

/**
 * WP2.6 — restart-retention.
 *
 * Boots two independent, full `ChatApplication` Spring contexts
 * sequentially against the *same* Testcontainers PostgreSQL instance,
 * simulating a backend restart while the database volume persists (the
 * real docker-compose topology: `db` has a named volume, `backend` does
 * not — design.md "Docker Compose topology"). A message written and
 * committed during the first boot, after that context is fully closed
 * (connection pool torn down, all in-memory state discarded), must still
 * be retrievable via [MessageRepository] from the second, independent boot.
 *
 * This exercises the actual persistence guarantee end-to-end (schema
 * creation via `ddl-auto=update`, seeding, and query) rather than just
 * within-context repository behavior, which
 * [MessagePersistenceIntegrationTest] already covers.
 */
@Testcontainers
class RestartRetentionIntegrationTest {

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
            // Full servlet web context (not WebApplicationType.NONE): the
            // scaffold SecurityConfig's `securityFilterChain` bean requires
            // an autowired HttpSecurity, which Spring Security only
            // registers for a servlet web application context. A random
            // free port keeps sequential boots from colliding.
            .run(
                // Command-line args are the highest-precedence Spring property
                // source, so they reliably override application.yml's
                // already-resolved `${SPRING_DATASOURCE_URL:jdbc:...}`
                // placeholder default — a plain `.properties(Map)` (lowest
                // precedence "defaultProperties") does not.
                "--server.port=0",
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--spring.jpa.hibernate.ddl-auto=update",
            )

    @Test
    fun `a message persisted on one boot is retrievable after a second, independent boot`() {
        val messageId: UUID
        val conversationId: UUID

        val firstBoot = boot()
        try {
            val appUserRepository = firstBoot.getBean(AppUserRepository::class.java)
            val conversationRepository = firstBoot.getBean(ConversationRepository::class.java)
            val messageRepository = firstBoot.getBean(MessageRepository::class.java)

            val alice = appUserRepository.save(AppUser(id = UUID.randomUUID(), displayName = "Alice"))
            val bob = appUserRepository.save(AppUser(id = UUID.randomUUID(), displayName = "Bob"))
            val conversation = conversationRepository.save(
                Conversation(id = UUID.randomUUID()).apply {
                    participants += alice
                    participants += bob
                },
            )
            conversationId = conversation.id

            val saved = messageRepository.saveAndFlush(
                Message(
                    conversation = conversation,
                    sender = alice,
                    clientMessageId = UUID.randomUUID(),
                    content = "still here after restart?",
                ),
            )
            messageId = saved.id
        } finally {
            // Fully tears down the connection pool and all in-memory state,
            // simulating a backend process restart.
            firstBoot.close()
        }

        val secondBoot = boot()
        try {
            val messageRepository = secondBoot.getBean(MessageRepository::class.java)
            val history = messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(conversationId)

            assertThat(history.map { it.id }).contains(messageId)
        } finally {
            secondBoot.close()
        }
    }
}
