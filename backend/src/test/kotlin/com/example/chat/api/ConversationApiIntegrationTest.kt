package com.example.chat.api

import com.example.chat.api.dto.MessageDto
import com.example.chat.domain.AppUser
import com.example.chat.domain.Conversation
import com.example.chat.domain.Message
import com.example.chat.repository.AppUserRepository
import com.example.chat.repository.ConversationRepository
import com.example.chat.repository.MessageRepository
import com.example.chat.seed.SeedData
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * End-to-end coverage of WP3 (3.1-3.4) against a real, fully booted
 * application (Spring Security filter chain, real controllers/services, and
 * a real PostgreSQL instance via Testcontainers — never H2 or a mocked
 * service layer, per the repo testing standard).
 *
 * [com.example.chat.seed.DataSeeder] runs on every context boot, so the
 * fixed-id Alice/Bob/conversation fixture already exists; this test adds a
 * third user and a second conversation that Alice/Bob do NOT participate in,
 * to prove conversation listing and history are correctly scoped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConversationApiIntegrationTest {

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

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var appUserRepository: AppUserRepository

    @Autowired
    lateinit var conversationRepository: ConversationRepository

    @Autowired
    lateinit var messageRepository: MessageRepository

    private lateinit var charlie: AppUser
    private lateinit var outsideConversation: Conversation

    private fun url(path: String) = "http://localhost:$port$path"

    private fun headersFor(userId: UUID?): HttpHeaders =
        HttpHeaders().apply { userId?.let { set("X-User-Id", it.toString()) } }

    @BeforeEach
    fun setUp() {
        // A third user and a conversation Alice/Bob are NOT part of, so
        // "only the user's conversations" is a real assertion, not
        // vacuously true from a single-conversation fixture.
        charlie = appUserRepository.save(AppUser(id = UUID.randomUUID(), displayName = "Charlie"))
        val bob = appUserRepository.findById(SeedData.BOB_ID).orElseThrow()
        outsideConversation = conversationRepository.save(
            Conversation(id = UUID.randomUUID()).apply {
                participants += bob
                participants += charlie
            },
        )
    }

    @Test
    fun `GET api-users is public and returns the seeded users without any identity header`() {
        val response = restTemplate.exchange(
            url("/api/users"),
            HttpMethod.GET,
            HttpEntity<Void>(headersFor(null)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Alice").contains("Bob")
    }

    @Test
    fun `GET api-conversations without X-User-Id is rejected with 401`() {
        val response = restTemplate.exchange(
            url("/api/conversations"),
            HttpMethod.GET,
            HttpEntity<Void>(headersFor(null)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `GET api-conversations with an unknown X-User-Id is rejected with 401`() {
        val response = restTemplate.exchange(
            url("/api/conversations"),
            HttpMethod.GET,
            HttpEntity<Void>(headersFor(UUID.randomUUID())),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `GET api-conversations returns only conversations the authenticated user participates in`() {
        val response = restTemplate.exchange(
            url("/api/conversations"),
            HttpMethod.GET,
            HttpEntity<Void>(headersFor(SeedData.ALICE_ID)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val conversationIds = objectMapper.readTree(response.body)
            .map { it.get("id").asText() }
        assertThat(conversationIds)
            .contains(SeedData.CONVERSATION_ID.toString())
            .doesNotContain(outsideConversation.id.toString())
    }

    @Test
    fun `GET api-conversations for Bob includes both his conversations`() {
        val response = restTemplate.exchange(
            url("/api/conversations"),
            HttpMethod.GET,
            HttpEntity<Void>(headersFor(SeedData.BOB_ID)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val conversationIds = objectMapper.readTree(response.body)
            .map { it.get("id").asText() }
        // `contains` (not an exact/exhaustive match): each @BeforeEach adds a
        // fresh outside-conversation fixture against the same Testcontainers
        // database shared across this class's test methods, so other tests'
        // fixtures may also legitimately include Bob by the time this runs.
        // The behavior under test is "Bob's own conversations are present",
        // not "these are the only two conversations in the database".
        assertThat(conversationIds)
            .contains(SeedData.CONVERSATION_ID.toString(), outsideConversation.id.toString())
    }

    @Test
    fun `GET history for a participant returns messages in deterministic chronological order with clientMessageId`() {
        val base = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val firstToken = UUID.randomUUID()
        val secondToken = UUID.randomUUID()
        val first = messageRepository.saveAndFlush(
            Message(
                conversation = conversationRepository.findById(SeedData.CONVERSATION_ID).orElseThrow(),
                sender = appUserRepository.findById(SeedData.ALICE_ID).orElseThrow(),
                clientMessageId = firstToken,
                content = "hello bob",
                createdAt = base,
            ),
        )
        val second = messageRepository.saveAndFlush(
            Message(
                conversation = conversationRepository.findById(SeedData.CONVERSATION_ID).orElseThrow(),
                sender = appUserRepository.findById(SeedData.BOB_ID).orElseThrow(),
                clientMessageId = secondToken,
                content = "hi alice",
                createdAt = base.plusSeconds(1),
            ),
        )

        val response = restTemplate.exchange(
            url("/api/conversations/${SeedData.CONVERSATION_ID}/messages"),
            HttpMethod.GET,
            HttpEntity<Void>(headersFor(SeedData.ALICE_ID)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val messages: List<MessageDto> = objectMapper.readValue(response.body!!)
        assertThat(messages.map { it.id }).containsExactly(first.id, second.id)
        assertThat(messages.map { it.senderId }).containsExactly(SeedData.ALICE_ID, SeedData.BOB_ID)
        assertThat(messages.map { it.conversationId }).allMatch { it == SeedData.CONVERSATION_ID }
        // The correlation token round-trips through history for every message,
        // including one submitted by the other participant (message-persistence
        // spec: "Correlation token is exposed in history and realtime events").
        // It stays per-sender-scoped and non-authoritative — id is what
        // identifies the message.
        assertThat(messages.map { it.clientMessageId }).containsExactly(firstToken, secondToken)
    }

    @Test
    fun `GET history for a non-participant is rejected with 403`() {
        val response = restTemplate.exchange(
            url("/api/conversations/${outsideConversation.id}/messages"),
            HttpMethod.GET,
            HttpEntity<Void>(headersFor(SeedData.ALICE_ID)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `GET history for a nonexistent conversation is rejected with 404`() {
        val response = restTemplate.exchange(
            url("/api/conversations/${UUID.randomUUID()}/messages"),
            HttpMethod.GET,
            HttpEntity<Void>(headersFor(SeedData.ALICE_ID)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `GET history without X-User-Id is rejected with 401, not 403 or 404`() {
        val response = restTemplate.exchange(
            url("/api/conversations/${SeedData.CONVERSATION_ID}/messages"),
            HttpMethod.GET,
            HttpEntity<Void>(headersFor(null)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
