package com.example.chat.realtime

import com.example.chat.repository.AppUserRepository
import com.example.chat.repository.ConversationRepository
import com.example.chat.repository.MessageRepository
import com.example.chat.seed.SeedData
import com.example.chat.service.MessageService
import com.example.chat.service.SendResult
import com.example.chat.ws.protocol.ErrorCodes
import com.example.chat.ws.protocol.SendMessageCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP4.6 / 4.9 -- covers [MessageService.send] against a real PostgreSQL
 * instance (Testcontainers, never H2/mocks -- repo testing standard): the
 * idempotency contract for a duplicate/replayed/conflicting/racing
 * `SEND_MESSAGE`, keyed by `(senderId, clientMessageId)` with the database
 * unique constraint as the final arbiter (design.md).
 *
 * Not `@DataJpaTest`: that annotation wraps each test method in one rolled-
 * back transaction, which would make `MessageWriter.createAndCommit`'s own
 * `@Transactional` method merely *join* the test's already-open transaction
 * (Spring's default `REQUIRES` propagation) instead of genuinely committing
 * independently -- exactly the distinction this work package's "commit
 * before ack" requirement depends on. A full `@SpringBootTest` with no
 * test-managed transaction is used instead, matching
 * [com.example.chat.api.ConversationApiIntegrationTest]'s established
 * pattern in this repo. `RANDOM_PORT` (not `NONE`): `SecurityConfig`'s
 * `securityFilterChain` bean requires an autowired `HttpSecurity`, which
 * Spring Security only registers for a servlet web application context (see
 * [com.example.chat.persistence.RestartRetentionIntegrationTest]'s boot
 * helper for the same constraint).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MessageServiceIdempotencyIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("chat")
                .withUsername("chat")
                .withPassword("chat")

        @DynamicPropertySource
        @JvmStatic
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var messageService: MessageService

    @Autowired
    lateinit var messageRepository: MessageRepository

    @Autowired
    lateinit var appUserRepository: AppUserRepository

    @Autowired
    lateinit var conversationRepository: ConversationRepository

    // SeedData's fixed users/conversation are seeded on every context boot
    // (DataSeeder), so they are available without additional fixture setup.
    private val aliceId = SeedData.ALICE_ID
    private val conversationId = SeedData.CONVERSATION_ID

    @Test
    fun `a genuinely new SEND_MESSAGE is created exactly once`() {
        val command = SendMessageCommand(UUID.randomUUID(), conversationId, "hello, unique message ${UUID.randomUUID()}")

        val result = messageService.send(aliceId, command)

        assertThat(result).isInstanceOf(SendResult.Created::class.java)
        val created = result as SendResult.Created
        assertThat(created.participantIds).containsExactlyInAnyOrder(SeedData.ALICE_ID, SeedData.BOB_ID)
        assertThat(messageRepository.findBySender_IdAndClientMessageId(aliceId, command.clientMessageId)?.id)
            .isEqualTo(created.message.id)
    }

    @Test
    fun `an identical retry re-acks the existing message without creating a second row or re-broadcasting`() {
        val clientMessageId = UUID.randomUUID()
        val content = "retry me ${UUID.randomUUID()}"
        val command = SendMessageCommand(clientMessageId, conversationId, content)

        val first = messageService.send(aliceId, command) as SendResult.Created
        val second = messageService.send(aliceId, command)

        assertThat(second).isInstanceOf(SendResult.DuplicateMatch::class.java)
        assertThat((second as SendResult.DuplicateMatch).message.id).isEqualTo(first.message.id)

        val history = messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(conversationId)
            .filter { it.clientMessageId == clientMessageId }
        assertThat(history).hasSize(1)
    }

    @Test
    fun `reusing a clientMessageId with different content is a conflict, never an ack of the wrong message`() {
        val clientMessageId = UUID.randomUUID()
        val command = SendMessageCommand(clientMessageId, conversationId, "original content")
        messageService.send(aliceId, command)

        val conflicting = messageService.send(aliceId, SendMessageCommand(clientMessageId, conversationId, "different content"))

        assertThat(conflicting).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((conflicting as SendResult.Rejected).code).isEqualTo(ErrorCodes.CLIENT_MESSAGE_ID_CONFLICT)
    }

    @Test
    fun `reusing a clientMessageId with a different conversation is a conflict`() {
        val clientMessageId = UUID.randomUUID()
        messageService.send(aliceId, SendMessageCommand(clientMessageId, conversationId, "same text"))

        val otherConversation = conversationRepository.save(
            com.example.chat.domain.Conversation(id = UUID.randomUUID()).apply {
                participants += appUserRepository.findById(aliceId).orElseThrow()
                participants += appUserRepository.findById(SeedData.BOB_ID).orElseThrow()
            },
        )

        val conflicting = messageService.send(aliceId, SendMessageCommand(clientMessageId, otherConversation.id, "same text"))

        assertThat(conflicting).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((conflicting as SendResult.Rejected).code).isEqualTo(ErrorCodes.CLIENT_MESSAGE_ID_CONFLICT)
    }

    @Test
    fun `a nonexistent conversation is rejected as CONVERSATION_NOT_FOUND`() {
        val result = messageService.send(aliceId, SendMessageCommand(UUID.randomUUID(), UUID.randomUUID(), "hi"))

        assertThat(result).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((result as SendResult.Rejected).code).isEqualTo(ErrorCodes.CONVERSATION_NOT_FOUND)
    }

    @Test
    fun `a non-participant sender is rejected as FORBIDDEN`() {
        val outsider = appUserRepository.save(com.example.chat.domain.AppUser(id = UUID.randomUUID(), displayName = "Outsider"))

        val result = messageService.send(outsider.id, SendMessageCommand(UUID.randomUUID(), conversationId, "hi"))

        assertThat(result).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((result as SendResult.Rejected).code).isEqualTo(ErrorCodes.FORBIDDEN)
    }

    @Test
    fun `concurrent identical SEND_MESSAGE commands create exactly one row`() {
        val clientMessageId = UUID.randomUUID()
        val content = "race me ${UUID.randomUUID()}"
        val threadCount = 8
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val results = java.util.concurrent.ConcurrentLinkedQueue<SendResult>()

        try {
            repeat(threadCount) {
                executor.submit {
                    try {
                        startLatch.await()
                        results += messageService.send(aliceId, SendMessageCommand(clientMessageId, conversationId, content))
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue()
        } finally {
            executor.shutdown()
        }

        assertThat(results).hasSize(threadCount)
        // Every racing thread must agree on the same authoritative message:
        // exactly one Created (or, in the rarer timing where all lose the
        // pre-check race window differently, N-1 DuplicateMatch outcomes),
        // never a Rejected -- and every non-Rejected result must reference
        // the same authoritative message id.
        val rejected = results.filterIsInstance<SendResult.Rejected>()
        assertThat(rejected).isEmpty()

        val messageIds = results.mapNotNull { result ->
            when (result) {
                is SendResult.Created -> result.message.id
                is SendResult.DuplicateMatch -> result.message.id
                is SendResult.Rejected -> null
            }
        }.toSet()
        assertThat(messageIds).hasSize(1)

        val history = messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(conversationId)
            .filter { it.clientMessageId == clientMessageId }
        assertThat(history).hasSize(1)
        assertThat(history.single().id).isEqualTo(messageIds.single())
    }
}
