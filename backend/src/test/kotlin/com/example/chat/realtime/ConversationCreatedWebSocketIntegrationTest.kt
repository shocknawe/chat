package com.example.chat.realtime

import com.example.chat.seed.SeedData
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
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
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Slice 2 (task 3.11) — end-to-end coverage (real HTTP upgrade, real handler
 * pipeline, real PostgreSQL via Testcontainers) of the `CONVERSATION_CREATED`
 * emission rules, mirroring [CommitBeforeAckWebSocketIntegrationTest]'s harness:
 *
 * - every *active connection* of the other participant receives the event —
 *   including when that participant holds more than one connection;
 * - the creator's connections receive none of them (the creator already holds
 *   the REST response; a duplicate event would invite a double-add);
 * - the event is never sent when the endpoint answered `200` (the
 *   already-existed case — the request that created the conversation emitted
 *   it once);
 * - the payload reuses the REST `Conversation` shape and represents the empty
 *   history exactly as a listing does (no `lastMessage` property at all —
 *   there is nothing to preview yet).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConversationCreatedWebSocketIntegrationTest {

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
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    private fun wsUri(userId: UUID) = URI.create("ws://localhost:$port/ws?userId=$userId")

    private fun connect(userId: UUID): Pair<WebSocketSession, LinkedBlockingQueue<String>> {
        val queue = LinkedBlockingQueue<String>()
        val handler = object : TextWebSocketHandler() {
            override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
                queue.add(message.payload)
            }
        }
        val session = StandardWebSocketClient().doHandshake(handler, null, wsUri(userId)).get(5, TimeUnit.SECONDS)
        return session to queue
    }

    private fun poll(queue: LinkedBlockingQueue<String>) =
        queue.poll(5, TimeUnit.SECONDS) ?: fail<String>("no frame received within timeout")

    private fun createConversation(callerId: UUID, participantId: UUID) =
        restTemplate.exchange(
            "http://localhost:$port/api/conversations",
            HttpMethod.POST,
            HttpEntity(
                """{"participantId":"$participantId"}""",
                HttpHeaders().apply {
                    set("X-User-Id", callerId.toString())
                    contentType = MediaType.APPLICATION_JSON
                },
            ),
            String::class.java,
        )

    @Test
    fun `CONVERSATION_CREATED reaches every of the other participant's connections and none of the creator's`() {
        val (aliceSession, aliceQueue) = connect(SeedData.ALICE_ID)
        val (danSession1, danQueue1) = connect(SeedData.DAN_ID)
        // Dan holds a *second* connection on purpose: the event is per
        // participant, not per connection (spec: "every active connection of
        // the other participant").
        val (danSession2, danQueue2) = connect(SeedData.DAN_ID)
        try {
            val response = createConversation(SeedData.ALICE_ID, SeedData.DAN_ID)
            assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
            val createdConversationId = objectMapper.readTree(response.body).get("id").asText()

            for (danQueue in listOf(danQueue1, danQueue2)) {
                val event = objectMapper.readTree(poll(danQueue))
                assertThat(event.get("type").asText()).isEqualTo("CONVERSATION_CREATED")
                assertThat(event.get("conversation").get("id").asText()).isEqualTo(createdConversationId)
                val participantIds = event.get("conversation").get("participants").map { it.get("id").asText() }
                assertThat(participantIds)
                    .containsExactlyInAnyOrder(SeedData.ALICE_ID.toString(), SeedData.DAN_ID.toString())

                // A new conversation has no history, so its preview is absent in
                // exactly the way a listing represents an empty history (the
                // global `non_null` Jackson inclusion omits the null).
                assertThat(event.get("conversation").has("lastMessage"))
                    .describedAs("a just-created conversation has no lastMessage to preview")
                    .isFalse()
            }

            // The creator already holds the REST response — no socket event.
            assertThat(aliceQueue.poll(500, TimeUnit.MILLISECONDS))
                .describedAs("the creator's connection must not receive CONVERSATION_CREATED")
                .isNull()
        } finally {
            aliceSession.close()
            danSession1.close()
            danSession2.close()
        }
    }

    @Test
    fun `no CONVERSATION_CREATED is emitted when the conversation already existed`() {
        val (aliceSession, aliceQueue) = connect(SeedData.ALICE_ID)
        // The seeded Alice<->Bob conversation has existed since the first-ever
        // seed, so this request is the 200 (already-existed) path by
        // construction — independent of what any other test in this class
        // created, and therefore order-independent.
        val (bobSession, bobQueue) = connect(SeedData.BOB_ID)
        try {
            val response = createConversation(SeedData.ALICE_ID, SeedData.BOB_ID)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

            assertThat(bobQueue.poll(500, TimeUnit.MILLISECONDS))
                .describedAs("the already-existed answer must not emit CONVERSATION_CREATED again")
                .isNull()
            assertThat(aliceQueue.poll(500, TimeUnit.MILLISECONDS))
                .describedAs("neither party receives an event for the existing conversation")
                .isNull()
        } finally {
            aliceSession.close()
            bobSession.close()
        }
    }
}