package com.example.chat.api

import com.example.chat.domain.Conversation
import com.example.chat.repository.ConversationRepository
import com.example.chat.seed.SeedData
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
import java.util.UUID

/**
 * End-to-end coverage of `POST /api/conversations` (slice 2, task 3.2) against
 * a fully booted application: real security filter chain (`X-User-Id`), real
 * controller/service/writer split, and real PostgreSQL via Testcontainers —
 * never H2 or a mocked service layer, per the repo testing standard.
 *
 * Statuses follow `docs/openapi.yaml` exactly, including the documented
 * distinction that this endpoint's `404` means "unknown participant" and never
 * "unknown conversation". Each test uses its own participant pair, because the
 * Testcontainers database is shared across this class's test methods and
 * creation is idempotent (a reused pair would answer 200, not 201).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CreateConversationApiIntegrationTest {

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
    lateinit var conversationRepository: ConversationRepository

    private fun url(path: String) = "http://localhost:$port$path"

    private fun headersFor(userId: UUID?): HttpHeaders =
        HttpHeaders().apply {
            userId?.let { set("X-User-Id", it.toString()) }
            contentType = MediaType.APPLICATION_JSON
        }

    private fun postJson(body: String, asUser: UUID?) =
        restTemplate.exchange(url("/api/conversations"), HttpMethod.POST, HttpEntity(body, headersFor(asUser)), String::class.java)

    private fun createConversation(participantId: UUID, asUser: UUID) =
        postJson("""{"participantId":"$participantId"}""", asUser)

    @Test
    fun `creating a conversation with a directory user returns 201 with exactly the caller and that user`() {
        val response = createConversation(SeedData.DAN_ID, SeedData.ALICE_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = objectMapper.readTree(response.body)
        val participantIds = body.get("participants").map { it.get("id").asText() }
        assertThat(participantIds).containsExactlyInAnyOrder(SeedData.ALICE_ID.toString(), SeedData.DAN_ID.toString())

        // The row is keyed with the canonical sorted pair key, and is exactly
        // the row this response announced.
        val persisted = conversationRepository.findByPairKey(Conversation.pairKeyFor(SeedData.ALICE_ID, SeedData.DAN_ID))
        assertThat(persisted).isNotNull
        assertThat(persisted!!.id.toString()).isEqualTo(body.get("id").asText())
    }

    @Test
    fun `creating the same pair again returns the existing conversation with 200 and creates no second row`() {
        val first = createConversation(SeedData.ERIN_ID, SeedData.ALICE_ID)
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        val firstId = objectMapper.readTree(first.body).get("id").asText()

        val second = createConversation(SeedData.ERIN_ID, SeedData.ALICE_ID)

        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(second.body).get("id").asText()).isEqualTo(firstId)
        assertThat(conversationRepository.findByPairKey(Conversation.pairKeyFor(SeedData.ALICE_ID, SeedData.ERIN_ID)))
            .isNotNull
    }

    @Test
    fun `creating from the other side of the pair resolves to the same conversation and returns 200`() {
        // Bob asks Carol first (a pair the seeder does not create); Carol's own
        // reverse request must be a 200 onto Bob's row, not a second
        // conversation (design.md decision 2: the pair key is canonical, so the
        // argument order cannot fork the pair).
        val bobsRequest = createConversation(SeedData.CAROL_ID, SeedData.BOB_ID)
        assertThat(bobsRequest.statusCode).isEqualTo(HttpStatus.CREATED)
        val conversationId = objectMapper.readTree(bobsRequest.body).get("id").asText()

        val carolsRequest = createConversation(SeedData.BOB_ID, SeedData.CAROL_ID)

        assertThat(carolsRequest.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(carolsRequest.body).get("id").asText()).isEqualTo(conversationId)
    }

    @Test
    fun `naming the caller as the participant is rejected with 400 and creates nothing`() {
        val response = createConversation(SeedData.ALICE_ID, SeedData.ALICE_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        // A self conversation would have carried the (alice, alice) pair key;
        // nothing was created under it.
        assertThat(conversationRepository.findByPairKey(Conversation.pairKeyFor(SeedData.ALICE_ID, SeedData.ALICE_ID)))
            .isNull()
    }

    @Test
    fun `a missing participantId is rejected with 400`() {
        val response = postJson("{}", SeedData.ALICE_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `an explicitly null participantId is rejected with 400`() {
        val response = postJson("""{"participantId":null}""", SeedData.ALICE_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a malformed participantId is rejected with 400`() {
        val response = postJson("""{"participantId":"not-a-uuid"}""", SeedData.ALICE_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("Bad Request")
    }

    @Test
    fun `an unknown participant is rejected with 404 and creates nothing`() {
        val unknownId = UUID.randomUUID()

        val response = createConversation(unknownId, SeedData.ALICE_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body).contains("participantId does not name a directory user")
        assertThat(conversationRepository.findByPairKey(Conversation.pairKeyFor(SeedData.ALICE_ID, unknownId)))
            .isNull()
    }

    @Test
    fun `a missing X-User-Id is rejected with 401`() {
        val response = postJson("""{"participantId":"${SeedData.DAN_ID}"}""", null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `an unknown caller identity is rejected with 401, not 403 or 404`() {
        val response = createConversation(SeedData.DAN_ID, UUID.randomUUID())

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}