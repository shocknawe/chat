package com.example.chat.realtime

import com.example.chat.seed.SeedData
import com.example.chat.ws.ConnectionRegistry
import com.example.chat.ws.WebSocketSessionAttributes
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.socket.CloseStatus
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
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Slice 4 (tasks 5.6 / 5.7 / 5.8) -- end-to-end coverage (real HTTP upgrade,
 * real [com.example.chat.ws.ConnectionRegistry], real
 * [com.example.chat.ws.PresenceBroadcaster], real PostgreSQL via
 * Testcontainers) of the presence properties that only manifest across the
 * wire, mirroring [ConversationCreatedWebSocketIntegrationTest]'s harness.
 *
 * Seeded topology relevant here: Alice shares conversations with Bob and
 * Carol; Bob and Carol share none with each other.
 *
 * - Task 5.3/spec "First event is a presence snapshot": a newly established
 *   connection's first frame is its scoped `PRESENCE` snapshot.
 * - Task 5.6: two connections of one user close one at a time -- no offline
 *   announcement until the last one closes.
 * - Task 5.7: a connection removed by *delivery failure* (not a clean close)
 *   changes announced presence, and the eviction fired in the middle of a
 *   presence broadcast neither deadlocks nor throws.
 * - Task 5.8: every delivered set contains only users the recipient shares a
 *   conversation with.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PresenceWebSocketIntegrationTest {

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

    /** White-box seam used only to inject the delivery-failing session of task 5.7. */
    @Autowired
    lateinit var connectionRegistry: ConnectionRegistry

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

    private fun poll(queue: LinkedBlockingQueue<String>, timeoutMillis: Long = 5_000) =
        queue.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: fail<String>("no frame received within timeout")

    private fun pollOrNull(queue: LinkedBlockingQueue<String>, timeoutMillis: Long = 500): String? =
        queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    private fun assertPresence(frame: String, description: String): Set<String> {
        val node = objectMapper.readTree(frame)
        assertThat(node.get("type").asText())
            .describedAs(description)
            .isEqualTo("PRESENCE")
        assertThat(node.get("online")).isNotNull()
        return node.get("online").map { it.asText() }.toSet()
    }

    /**
     * Server-side processing of a close (unregister -> edge -> broadcast) is
     * asynchronous relative to the client's `close()` returning. Each test
     * closes its sockets in `finally` and settles, so a later test in this
     * class never inherits a stale transition aimed at its own fresh sockets.
     */
    private fun settleAfterClose() {
        Thread.sleep(400)
    }

    @Test
    fun `a newly connected socket's first event is its scoped presence snapshot and transitions keep it fresh`() {
        val (aliceSession, aliceQueue) = connect(SeedData.ALICE_ID)
        try {
            // Alice holds no conversations with anyone online yet (task 5.3).
            val first = assertPresence(poll(aliceQueue), "Alice's first event must be her presence snapshot")
            assertThat(first).describedAs("nobody Alice converses with is connected yet").isEmpty()

            val (bobSession, bobQueue) = connect(SeedData.BOB_ID)
            try {
                val bobFirst = assertPresence(poll(bobQueue), "Bob's first event must be his presence snapshot")
                assertThat(bobFirst).describedAs("Alice, Bob's only partner, is online").containsExactly(
                    SeedData.ALICE_ID.toString(),
                )

                val aliceSeesBob = assertPresence(poll(aliceQueue), "Alice must be told that Bob came online")
                assertThat(aliceSeesBob).containsExactly(SeedData.BOB_ID.toString())

                // Nothing else is coming: Bob's own sockets are not targets of
                // his own transition, and there is no other traffic.
                assertThat(pollOrNull(aliceQueue, 300))
                    .describedAs("nothing beyond the two presence frames")
                    .isNull()
            } finally {
                bobSession.close()
            }
        } finally {
            aliceSession.close()
            settleAfterClose()
        }
    }

    /**
     * Task 5.8 / spec "Presence is scoped to conversation partners": Bob and
     * Carol share no conversation, so neither may ever appear in the other's
     * delivered set -- while Alice, who shares a conversation with both, sees
     * both.
     */
    @Test
    fun `a delivered presence set contains no user the recipient shares no conversation with`() {
        val (aliceSession, aliceQueue) = connect(SeedData.ALICE_ID)
        val (bobSession, bobQueue) = connect(SeedData.BOB_ID)
        val (carolSession, carolQueue) = connect(SeedData.CAROL_ID)
        try {
            poll(bobQueue) // Bob's connect snapshot
            poll(carolQueue) // Carol's connect snapshot

            // Alice receives a frame per transition that targets her -- her
            // connect snapshot, then Bob's online edge, then Carol's. Her
            // snapshots are settled once one names both of her online partners.
            val both = setOf(SeedData.BOB_ID.toString(), SeedData.CAROL_ID.toString())
            var aliceSnapshot: Set<String> = emptySet()
            while (aliceSnapshot != both) {
                val frame = pollOrNull(aliceQueue, 3_000) ?: fail<String>("no frame received within timeout")
                aliceSnapshot = assertPresence(frame, "Alice's snapshot")
                    .also {
                        assertThat(it)
                            .describedAs("Alice shares a conversation with both Bob and Carol")
                            .isSubsetOf(both)
                    }
            }
            assertThat(aliceSnapshot).containsExactlyInAnyOrder(SeedData.BOB_ID.toString(), SeedData.CAROL_ID.toString())

            // Every frame Bob ever receives is scoped to him: Alice only.
            while (true) {
                val frame = pollOrNull(bobQueue, 400) ?: break
                assertThat(assertPresence(frame, "Bob's snapshot"))
                    .describedAs("Bob shares no conversation with Carol")
                    .doesNotContain(SeedData.CAROL_ID.toString())
            }
            while (true) {
                val frame = pollOrNull(carolQueue, 400) ?: break
                assertThat(assertPresence(frame, "Carol's snapshot"))
                    .describedAs("Carol shares no conversation with Bob")
                    .doesNotContain(SeedData.BOB_ID.toString())
            }
        } finally {
            aliceSession.close()
            bobSession.close()
            carolSession.close()
            settleAfterClose()
        }
    }

    /**
     * Task 5.6 / spec "Multiple connections suppress a false offline
     * announcement": Bob's first connection closing is invisible to Alice;
     * only the last close moves her snapshot.
     */
    @Test
    fun `a user holding two connections is announced offline only when the last one closes`() {
        val (aliceSession, aliceQueue) = connect(SeedData.ALICE_ID)
        try {
            poll(aliceQueue) // Alice's connect snapshot
            val (bobSession1, bobQueue1) = connect(SeedData.BOB_ID)
            val (bobSession2, bobQueue2) = connect(SeedData.BOB_ID)
            try {
                assertThat(assertPresence(poll(bobQueue1), "Bob's connect snapshot"))
                    .containsExactly(SeedData.ALICE_ID.toString())
                // Bob's second connection triggers no edge at all: he was
                // already online. It gets its own connect snapshot (containing
                // Alice), and then nothing -- his own sockets are not
                // transition targets (a user is not their own partner).
                assertThat(assertPresence(poll(bobQueue2), "Bob's second connect snapshot"))
                    .containsExactly(SeedData.ALICE_ID.toString())
                assertThat(pollOrNull(bobQueue2, 400)).isNull()
                assertThat(pollOrNull(bobQueue1, 400)).isNull()

                assertThat(assertPresence(poll(aliceQueue), "Alice must learn Bob came online"))
                    .containsExactly(SeedData.BOB_ID.toString())
                assertThat(pollOrNull(aliceQueue, 400))
                    .describedAs("Bob's second connection must not announce anything")
                    .isNull()

                bobSession1.close()
                assertThat(pollOrNull(aliceQueue, 500))
                    .describedAs("one of two connections closing must not announce Bob offline")
                    .isNull()

                bobSession2.close()
                val last = assertPresence(poll(aliceQueue), "Alice must learn Bob went offline")
                assertThat(last)
                    .describedAs("Alice's partners are Bob and Carol; both are offline now")
                    .isEmpty()
            } finally {
                bobSession1.close()
                bobSession2.close()
            }
        } finally {
            aliceSession.close()
            settleAfterClose()
        }
    }

    /**
     * Task 5.7 / spec "Eviction updates presence": Alice's session here cannot
     * deliver -- it is a registered-but-broken session (a half-open connection
     * the server cannot otherwise tell from a live one, which is exactly why
     * eviction exists). Bob coming online forces a presence broadcast aimed at
     * it; the failing delivery evicts it *in the middle of that broadcast*,
     * which publishes another edge, which enqueues another broadcast.
     *
     * What must hold: the eviction is observable as a presence change to Bob
     * (`[alice]` then `[]`), the broken session is closed by the eviction even
     * though its `attributes` carry no usable identity (task 5.1b), no
     * deadlock or exception escapes (this test completing is the assertion --
     * the whole chain runs on the broadcaster's single-threaded executor), and
     * the broadcaster keeps working afterwards.
     */
    @Test
    fun `a delivery failure inside a presence broadcast evicts the session and announces the presence change`() {
        val broken = mock(WebSocketSession::class.java)
        `when`(broken.id).thenReturn("broken-alice")
        `when`(broken.isOpen).thenReturn(true)
        `when`(broken.attributes).thenReturn(mutableMapOf()) // no usable identity on purpose
        `when`(broken.sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java)))
            .thenThrow(IOException("broken pipe"))
        connectionRegistry.register(SeedData.ALICE_ID, broken)

        val (bobSession, bobQueue) = connect(SeedData.BOB_ID)
        try {
            // Bob's connect snapshot: Alice counted as online (her broken session held her there).
            assertThat(assertPresence(poll(bobQueue), "Bob's connect snapshot"))
                .containsExactly(SeedData.ALICE_ID.toString())

            // Bob's transition broadcast reaches Alice's broken session, evicts
            // it, and the resulting edge re-broadcasts to Bob: Alice is gone.
            assertThat(assertPresence(poll(bobQueue), "Alice's eviction must change announced presence"))
                .describedAs("Bob's only partner is no longer online")
                .isEmpty()

            // The eviction closed the broken session and removed it from every index.
            verify(broken, timeout(5_000)).close(org.mockito.ArgumentMatchers.any(CloseStatus::class.java))
            assertThat(connectionRegistry.isOnline(SeedData.ALICE_ID)).isFalse()

            // The broadcaster survived the eviction-during-broadcast: a real
            // Alice reconnects and immediately gets a fresh snapshot.
            val (aliceSession, aliceQueue) = connect(SeedData.ALICE_ID)
            try {
                assertThat(assertPresence(poll(aliceQueue), "Alice's reconnect snapshot"))
                    .containsExactly(SeedData.BOB_ID.toString())
                assertThat(assertPresence(poll(bobQueue), "Bob must learn Alice is back"))
                    .containsExactly(SeedData.ALICE_ID.toString())
            } finally {
                aliceSession.close()
            }
        } finally {
            bobSession.close()
            settleAfterClose()
        }
    }
}