package com.example.chat.realtime

import com.example.chat.seed.SeedData
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
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
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * End-to-end WebSocket coverage (real HTTP upgrade, real handler pipeline,
 * real PostgreSQL via Testcontainers -- never mocked) of the properties that
 * only manifest across the wire:
 *
 * - WP4.6/4.7 "commit before acknowledgement": by the time a client observes
 *   `MESSAGE_ACK`, the row must be visible to a totally independent JDBC
 *   connection (not merely flushed within the still-open transaction that
 *   wrote it, and not merely visible to Spring's own connection pool, which
 *   would not distinguish a flush from a commit).
 * - WP4.2 "unknown user cannot establish a connection".
 * - WP4.7 fan-out: the other participant's connection receives
 *   `NEW_MESSAGE`; the origin never does.
 * - WP4.5 "invalid command does not affect other connections": a malformed
 *   frame gets `INVALID_COMMAND` and the same connection keeps working
 *   afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CommitBeforeAckWebSocketIntegrationTest {

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

    private fun sendMessageFrame(session: WebSocketSession, clientMessageId: UUID, conversationId: UUID, content: String) {
        val payload = """{"type":"SEND_MESSAGE","clientMessageId":"$clientMessageId","conversationId":"$conversationId","content":"$content"}"""
        session.sendMessage(TextMessage(payload))
    }

    private fun poll(queue: LinkedBlockingQueue<String>) =
        queue.poll(5, TimeUnit.SECONDS) ?: fail<String>("no frame received within timeout")

    /**
     * Presence (add-conversation-creation-presence-inspector task 5.3) makes a
     * `PRESENCE` snapshot every connection's first event, and a transition
     * broadcast follows each peer's connect. These tests are about the message
     * path, so they (a) block until the snapshot has actually arrived --
     * presence is delivered asynchronously on its own executor, so it can
     * race any frame the test sends -- and (b) skip presence frames whenever
     * one shows up before the frame these tests assert on.
     */
    private fun awaitInitialPresence(queue: LinkedBlockingQueue<String>) {
        val first = objectMapper.readTree(poll(queue))
        assertThat(first.get("type").asText())
            .describedAs("a connection's first event is its presence snapshot")
            .isEqualTo("PRESENCE")
    }

    private fun pollSkippingPresence(queue: LinkedBlockingQueue<String>): String {
        while (true) {
            val frame = poll(queue)
            if (objectMapper.readTree(frame).get("type").asText() != "PRESENCE") return frame
        }
    }

    @Test
    fun `MESSAGE_ACK arrives only after the row is visible to an independent database connection`() {
        val (session, queue) = connect(SeedData.ALICE_ID)
        try {
            awaitInitialPresence(queue)
            val clientMessageId = UUID.randomUUID()
            sendMessageFrame(session, clientMessageId, SeedData.CONVERSATION_ID, "commit before ack ${UUID.randomUUID()}")

            val ackJson = pollSkippingPresence(queue)
            val ackNode = objectMapper.readTree(ackJson)
            assertThat(ackNode.get("type").asText()).isEqualTo("MESSAGE_ACK")
            val messageId = UUID.fromString(ackNode.get("message").get("id").asText())

            // A brand-new JDBC connection -- deliberately NOT Spring's
            // HikariCP pool -- so this genuinely exercises cross-transaction
            // visibility under READ COMMITTED, not just "the same
            // connection can see its own writes".
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM message WHERE id = ?").use { stmt ->
                    stmt.setObject(1, messageId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        assertThat(rs.getInt(1)).isEqualTo(1)
                    }
                }
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `an unknown userId handshake is rejected and no session is established`() {
        val handler = object : TextWebSocketHandler() {}
        val future = StandardWebSocketClient().doHandshake(handler, null, wsUri(UUID.randomUUID()))

        assertThatHandshakeFails(future)
    }

    @Test
    fun `the other participant's connection receives NEW_MESSAGE while the origin does not`() {
        val (aliceSession, aliceQueue) = connect(SeedData.ALICE_ID)
        val (bobSession, bobQueue) = connect(SeedData.BOB_ID)
        try {
            awaitInitialPresence(aliceQueue)
            awaitInitialPresence(bobQueue)
            val clientMessageId = UUID.randomUUID()
            sendMessageFrame(aliceSession, clientMessageId, SeedData.CONVERSATION_ID, "fan-out check ${UUID.randomUUID()}")

            val aliceFrame = objectMapper.readTree(pollSkippingPresence(aliceQueue))
            assertThat(aliceFrame.get("type").asText()).isEqualTo("MESSAGE_ACK")
            // The origin gets its ack and nothing but (late) presence frames.
            assertThatNoMessageEventArrives(aliceQueue)

            val bobFrame = objectMapper.readTree(pollSkippingPresence(bobQueue))
            assertThat(bobFrame.get("type").asText()).isEqualTo("NEW_MESSAGE")
            assertThat(bobFrame.get("message").get("id").asText())
                .isEqualTo(aliceFrame.get("message").get("id").asText())
        } finally {
            aliceSession.close()
            bobSession.close()
        }
    }

    @Test
    fun `an invalid command frame gets INVALID_COMMAND and the connection keeps working afterwards`() {
        val (session, queue) = connect(SeedData.ALICE_ID)
        try {
            awaitInitialPresence(queue)
            session.sendMessage(TextMessage("{not valid json"))

            val errorFrame = objectMapper.readTree(pollSkippingPresence(queue))
            assertThat(errorFrame.get("type").asText()).isEqualTo("ERROR")
            assertThat(errorFrame.get("code").asText()).isEqualTo(com.example.chat.ws.protocol.ErrorCodes.INVALID_COMMAND)

            // Same connection, same session: a valid command afterwards
            // still works -- the bad frame did not terminate anything.
            val clientMessageId = UUID.randomUUID()
            sendMessageFrame(session, clientMessageId, SeedData.CONVERSATION_ID, "still alive ${UUID.randomUUID()}")
            val ackFrame = objectMapper.readTree(pollSkippingPresence(queue))
            assertThat(ackFrame.get("type").asText()).isEqualTo("MESSAGE_ACK")
        } finally {
            session.close()
        }
    }

    /**
     * Presence frames may still be trickling in from the (already-finished)
     * connection setup; nothing but presence may arrive: no `NEW_MESSAGE` for
     * the origin's own command, no `ERROR`.
     */
    private fun assertThatNoMessageEventArrives(queue: LinkedBlockingQueue<String>) {
        val deadline = System.currentTimeMillis() + 500
        while (true) {
            val frame = queue.poll((deadline - System.currentTimeMillis()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: return
            assertThat(objectMapper.readTree(frame).get("type").asText())
                .describedAs("the origin's own command must not fan out back to it")
                .isEqualTo("PRESENCE")
        }
    }

    private fun assertThatHandshakeFails(future: java.util.concurrent.Future<WebSocketSession>) {
        try {
            val session = future.get(5, TimeUnit.SECONDS)
            // Some client/container combinations report a rejected upgrade
            // as an immediately-closed session rather than a failed future.
            assertThat(session.isOpen).isFalse()
        } catch (ex: java.util.concurrent.ExecutionException) {
            // Expected: the handshake interceptor returned false / a non-101
            // status, which the client surfaces as a handshake failure.
            assertThat(ex.cause).isNotNull()
        }
    }
}
