package com.example.chat.api

import com.example.chat.domain.AppUser
import com.example.chat.domain.Conversation
import com.example.chat.domain.Message
import com.example.chat.repository.AppUserRepository
import com.example.chat.repository.ConversationRepository
import com.example.chat.repository.MessageRepository
import com.example.chat.seed.SeedData
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
 * End-to-end coverage of slice 3 (rail previews, tasks 4.0/4.1/4.5) against a
 * fully booted application — real `X-User-Id` filter chain, real
 * service/writer/repository split, real PostgreSQL via Testcontainers (never
 * H2, per the repo testing standard).
 *
 * What is pinned here is the contract, not the query plan: the preview a
 * listing or a creation response carries is exactly the tail of the
 * conversation's history under the documented `(createdAt ASC, id ASC)`
 * ordering (task 4.5), and — the task 4.0 wire decision — an empty history is
 * represented by the property being *omitted* from the JSON, never a literal
 * `null` (`spring.jackson.default-property-inclusion: non_null`), in exactly
 * the same shape in listing responses, `201` bodies, and `200`
 * already-existed bodies (conversation-previews spec, "Creation responses
 * carry the same preview shape as the listing").
 *
 * Every test mints its own partner user, so each runs against a conversation
 * pair nothing else (in this class, in `CreateConversationApiIntegrationTest`,
 * or in the seeder) can collide with: creation is idempotent per pair, and a
 * reused pair would answer `200` with a preview another test had already
 * mutated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConversationPreviewApiIntegrationTest {

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

    private fun url(path: String) = "http://localhost:$port$path"

    private fun headersFor(userId: UUID): HttpHeaders =
        HttpHeaders().apply {
            set("X-User-Id", userId.toString())
            contentType = MediaType.APPLICATION_JSON
        }

    /** A fresh directory user per call, so every test owns an unused pair. */
    private fun newPartner(name: String): AppUser =
        appUserRepository.save(AppUser(id = UUID.randomUUID(), displayName = name))

    private fun createConversationAs(alice: AppUser, partner: AppUser) =
        restTemplate.exchange(
            url("/api/conversations"),
            HttpMethod.POST,
            HttpEntity("""{"participantId":"${partner.id}"}""", headersFor(alice.id)),
            String::class.java,
        )

    private fun listConversationsAs(userId: UUID): Array<JsonNode> =
        restTemplate.exchange(
            url("/api/conversations"),
            HttpMethod.GET,
            HttpEntity<Void>(headersFor(userId)),
            Array<JsonNode>::class.java,
        ).let { response ->
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            response.body!!
        }

    private fun conversationNode(nodes: Array<JsonNode>, conversationId: UUID): JsonNode =
        nodes.single { it.get("id").asText() == conversationId.toString() }

    private fun historyFor(userId: UUID, conversationId: UUID): Array<JsonNode> =
        restTemplate.exchange(
            url("/api/conversations/$conversationId/messages"),
            HttpMethod.GET,
            HttpEntity<Void>(headersFor(userId)),
            Array<JsonNode>::class.java,
        ).let { response ->
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            response.body!!
        }

    private fun insertMessage(
        conversation: Conversation,
        sender: AppUser,
        content: String,
        createdAt: Instant,
        id: UUID = UUID.randomUUID(),
    ): Message =
        messageRepository.saveAndFlush(
            Message(
                id = id,
                conversation = conversation,
                sender = sender,
                clientMessageId = UUID.randomUUID(),
                content = content,
                createdAt = createdAt,
            ),
        )

    // --- Task 4.5: the preview matches the tail of the history ordering ---

    @Test
    fun `a listing's preview equals the last message of its history under the documented ordering`() {
        val alice = appUserRepository.findById(SeedData.ALICE_ID).orElseThrow()
        val dana = newPartner("Dana")

        val created = createConversationAs(alice, dana)
        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val conversation = conversationRepository.findByPairKey(
            Conversation.pairKeyFor(alice.id, dana.id),
        )!!

        val tieInstant = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val tieWinnerId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
        val tieLoserId = UUID.fromString("00000000-0000-0000-0000-000000000001")

        // Two messages sharing a createdAt: the documented ordering's tail is
        // the one with the greater id. The tie winner is inserted FIRST, so an
        // assembly accidentally keyed on insertion order — or on DESC applied
        // to only one of the two keys — would name the loser here and fail.
        insertMessage(conversation, alice, "tie winner, inserted first", tieInstant, tieWinnerId)
        insertMessage(conversation, alice, "tie loser, inserted second", tieInstant, tieLoserId)
        insertMessage(
            conversation,
            alice,
            "later, low id",
            tieInstant.plusSeconds(1),
            id = UUID.fromString("00000000-0000-0000-0000-000000000000"),
        )

        val history = historyFor(alice.id, conversation.id)
        val historyTail = history.last()

        val preview = conversationNode(listConversationsAs(alice.id), conversation.id).get("lastMessage")

        assertThat(history.map { it.get("id").asText() })
            .containsExactly(
                tieLoserId.toString(),
                tieWinnerId.toString(),
                "00000000-0000-0000-0000-000000000000",
            )
        assertThat(preview).isNotNull
        // Same element, field for field — the rail preview and the thread tail
        // are two views of one server-ordered fact (design.md decision 5).
        assertThat(historyTail.get("id").asText()).isEqualTo("00000000-0000-0000-0000-000000000000")
        // createdAt outranks the id tiebreak entirely: the latest createdAt wins
        // even though its id sorts below every other message's.
        assertThat(preview.get("id").asText()).isNotEqualTo(tieWinnerId.toString())
        assertThat(preview.get("id").asText()).isEqualTo(historyTail.get("id").asText())
        assertThat(preview.get("content").asText()).isEqualTo(historyTail.get("content").asText())
        assertThat(preview.get("createdAt").asText()).isEqualTo(historyTail.get("createdAt").asText())
        assertThat(preview.get("senderId").asText()).isEqualTo(historyTail.get("senderId").asText())
        assertThat(preview.get("conversationId").asText()).isEqualTo(conversation.id.toString())
    }

    // --- Task 4.0: the empty-history wire form, identical everywhere ---

    @Test
    fun `an empty history omits lastMessage identically in the creation response and the listing`() {
        val alice = appUserRepository.findById(SeedData.ALICE_ID).orElseThrow()
        val dan = newPartner("Dan")

        val created = createConversationAs(alice, dan)
        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val conversationId = UUID.fromString(objectMapper.readTree(created.body).get("id").asText())

        // Omitted, not null: the raw body contains no trace of the property at
        // all — the exact JSON shape `docs/openapi.yaml` documents for an empty
        // history (an optional property, never a literal `null`).
        assertThat(created.body!!).doesNotContain("lastMessage")

        val listed = conversationNode(listConversationsAs(alice.id), conversationId)
        assertThat(listed.has("lastMessage")).isFalse()

        // The partner's own listing of the same row agrees — the empty
        // representation is a property of the conversation, not of the caller.
        assertThat(conversationNode(listConversationsAs(dan.id), conversationId).has("lastMessage"))
            .isFalse()
    }

    // --- Creation responses carry the same preview shape as the listing ---

    @Test
    fun `an already-existed creation response carries the same preview as the listing for that row`() {
        val alice = appUserRepository.findById(SeedData.ALICE_ID).orElseThrow()
        val erin = newPartner("Erin")

        val first = createConversationAs(alice, erin)
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        val conversationId = UUID.fromString(objectMapper.readTree(first.body).get("id").asText())
        val conversation = conversationRepository.findById(conversationId).orElseThrow()
        assertThat(objectMapper.readTree(first.body).has("lastMessage")).isFalse()

        // A message arrives after creation, via the (WebSocket-only) write path.
        val message = insertMessage(conversation, erin, "preview me", Instant.now().truncatedTo(ChronoUnit.MICROS))

        // The 200 (already-existed) branch — same endpoint, same row, now with
        // history — must not disagree with the listing.
        val second = createConversationAs(alice, erin)
        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        val fromCreation = objectMapper.readTree(second.body).get("lastMessage")
        assertThat(fromCreation).isNotNull
        assertThat(fromCreation.get("id").asText()).isEqualTo(message.id.toString())

        val fromListing = conversationNode(listConversationsAs(alice.id), conversationId).get("lastMessage")
        assertThat(fromListing.get("id").asText()).isEqualTo(message.id.toString())
        assertThat(fromCreation).isEqualTo(fromListing)
    }
}