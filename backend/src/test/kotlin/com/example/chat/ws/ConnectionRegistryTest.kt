package com.example.chat.ws

import com.example.chat.api.dto.MessageDto
import com.example.chat.security.AuthenticatedUser
import com.example.chat.ws.protocol.NewMessage
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors

/**
 * WP4.4 / 4.7 -- unit coverage (mock [WebSocketSession]s, no real network or
 * Spring context) of [ConnectionRegistry]'s fan-out, failure-isolation, and
 * presence-edge behavior: origin exclusion, delivery to every other session of
 * a user (including a second session of the same user), evicting exactly the
 * session whose send fails without affecting delivery to sibling sessions, the
 * empty<->non-empty edges detected *inside* the `sessionsByUser.compute*`
 * lambda (task 5.1), and eviction that resolves a session's owner from the
 * registry's own index rather than `session.attributes` (task 5.1b).
 */
class ConnectionRegistryTest {

    private val objectMapper = JsonMapper.builder().addModule(kotlinModule()).findAndAddModules().build()
    private lateinit var registry: ConnectionRegistry
    private lateinit var transitions: LinkedBlockingQueue<PresenceTransition>

    private val userId = UUID.randomUUID()
    private val message = MessageDto(
        id = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        senderId = userId,
        content = "hi",
        createdAt = Instant.now(),
    )

    /**
     * Captures published [PresenceTransition]s without consuming them
     * asynchronously. In production the listener
     * ([com.example.chat.ws.PresenceBroadcaster]) only enqueues work on its own
     * executor; tests that emulate that wiring do the same.
     */
    private fun capturingPublisher() = ApplicationEventPublisher { event ->
        transitions.add(event as PresenceTransition)
    }

    @BeforeEach
    fun setUp() {
        transitions = LinkedBlockingQueue()
        registry = ConnectionRegistry(objectMapper, capturingPublisher())
    }

    private fun mockSession(id: String, open: Boolean = true): WebSocketSession {
        val session = mock(WebSocketSession::class.java)
        `when`(session.id).thenReturn(id)
        `when`(session.isOpen).thenReturn(open)
        `when`(session.attributes).thenReturn(
            mutableMapOf<String, Any>(WebSocketSessionAttributes.USER to AuthenticatedUser(userId, "Alice")),
        )
        return session
    }

    @Test
    fun `sendToUser delivers to every registered session except the excluded origin`() {
        val origin = mockSession("origin")
        val other = mockSession("other")
        registry.register(userId, origin)
        registry.register(userId, other)

        registry.sendToUser(userId, NewMessage(message), excluding = origin)

        verify(origin, never()).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java))
        verify(other).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java))
    }

    @Test
    fun `a second session of the same user also receives the event`() {
        val sessionA = mockSession("a")
        val sessionB = mockSession("b")
        registry.register(userId, sessionA)
        registry.register(userId, sessionB)

        registry.sendToUser(userId, NewMessage(message))

        verify(sessionA).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java))
        verify(sessionB).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java))
    }

    @Test
    fun `a failing session is evicted without affecting delivery to sibling sessions`() {
        val healthy = mockSession("healthy")
        val broken = mockSession("broken")
        `when`(broken.sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java))).thenThrow(java.io.IOException("broken pipe"))
        registry.register(userId, healthy)
        registry.register(userId, broken)

        registry.sendToUser(userId, NewMessage(message))

        verify(healthy).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java))
        // The broken session must be evicted; a subsequent send to the user
        // only reaches the still-registered healthy session.
        registry.sendToUser(userId, NewMessage(message))
        verify(healthy, org.mockito.Mockito.times(2)).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java))
        assertThat(registry.sessionById("broken")).isNull()
    }

    @Test
    fun `a closed session is evicted rather than sent to`() {
        val closed = mockSession("closed", open = false)
        registry.register(userId, closed)

        registry.sendToUser(userId, NewMessage(message))

        verify(closed, never()).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java))
        assertThat(registry.sessionById("closed")).isNull()
    }

    @Test
    fun `unregister removes the session from both indexes`() {
        val session = mockSession("s1")
        registry.register(userId, session)

        registry.unregister(userId, session)

        assertThat(registry.sessionById("s1")).isNull()
        registry.sendToUser(userId, NewMessage(message))
        verify(session, never()).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java))
    }

    @Test
    fun `sessionById resolves a registered session`() {
        val session = mockSession("resolvable")
        registry.register(userId, session)

        assertThat(registry.sessionById("resolvable")).isSameAs(session)
    }

    // ---- task 5.1: the empty<->non-empty edge is detected for every mutation path

    @Test
    fun `the first connection publishes an online edge and a second one publishes nothing`() {
        registry.register(userId, mockSession("a1"))

        assertThat(transitions.poll())
            .isEqualTo(PresenceTransition(userId = userId, online = true))

        registry.register(userId, mockSession("a2"))

        assertThat(transitions.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            .describedAs("a user already online does not change state by gaining another connection")
            .isNull()
        assertThat(registry.isOnline(userId)).isTrue()
    }

    @Test
    fun `closing one of two connections is not an offline edge and closing the last one is`() {
        val first = mockSession("first")
        val second = mockSession("second")
        registry.register(userId, first)
        registry.register(userId, second)
        while (transitions.poll() != null) {
            // discard the connect edges; this test is about closing
        }

        registry.unregister(userId, first)

        assertThat(transitions.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            .describedAs("a user with a surviving connection is not announced offline")
            .isNull()
        assertThat(registry.isOnline(userId)).isTrue()

        registry.unregister(userId, second)

        assertThat(transitions.poll()).isEqualTo(PresenceTransition(userId = userId, online = false))
        assertThat(registry.isOnline(userId)).isFalse()
    }

    @Test
    fun `a delivery failure evicts the session and publishes the offline edge`() {
        val broken = mockSession("broken")
        `when`(broken.sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java)))
            .thenThrow(IOException("broken pipe"))
        registry.register(userId, broken)
        transitions.clear()

        registry.send(broken, NewMessage(message))

        assertThat(registry.isOnline(userId))
            .describedAs("eviction is the delivery-failure path, so it must clear the user's online state")
            .isFalse()
        assertThat(transitions.poll()).isEqualTo(PresenceTransition(userId = userId, online = false))
    }

    /**
     * Task 5.1b / spec "A connection is never half-removed from the registry":
     * the owning user is resolved from the registry's own index, so a session
     * whose attributes are unusable (here: the getter itself throws -- the
     * worst case the old `runCatching`-attributes resolution defended against)
     * is still removed from the per-user set. Under the old resolution this
     * session stayed in `sessionsByUser` forever and held the user "online".
     */
    @Test
    fun `eviction resolves the owner from the registry index even when session attributes are unusable`() {
        val broken = mock(WebSocketSession::class.java)
        `when`(broken.id).thenReturn("index-owned")
        `when`(broken.isOpen).thenReturn(true)
        `when`(broken.attributes).thenThrow(IllegalStateException("attributes gone with the session"))
        `when`(broken.sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java)))
            .thenThrow(IOException("broken pipe"))
        registry.register(userId, broken)
        transitions.clear()

        registry.send(broken, NewMessage(message))

        verify(broken).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java))
        assertThat(registry.isOnline(userId))
            .describedAs("the user must not be held online by a session that is gone")
            .isFalse()
        assertThat(registry.sessionById("index-owned")).isNull()
        assertThat(transitions.poll()).isEqualTo(PresenceTransition(userId = userId, online = false))
    }

    /**
     * Task 5.7: the transition published by an eviction triggered *inside* a
     * presence broadcast (a failing `send` is exactly the broadcast path) must
     * see the registry's post-mutation state and must not re-enter any
     * `compute` lambda. The listener here mimics the production wiring
     * ([com.example.chat.ws.PresenceBroadcaster]): an edge is only ever
     * enqueued on a single-threaded executor, whose task then calls back into
     * `sendToUser`. With the pre-task design -- publishing from inside the
     * `compute` lambda -- this callback re-entered the same
     * `ConcurrentHashMap` and threw `IllegalStateException: Recursive update`
     * (same key) or deadlocked on a bin lock (across keys).
     */
    @Test
    fun `an eviction triggered while broadcasting enqueues its follow-up work without deadlocking or throwing`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            /** What the broadcaster's task observed when it eventually ran. */
            data class Observed(val transition: PresenceTransition, val registryStateOnline: Boolean)

            val duringBroadcast = LinkedBlockingQueue<Observed>()
            val broadcasting = registryWithListener { transition ->
                // Production shape: enqueue only -- never touch the registry on
                // the publishing thread.
                executor.execute {
                    duringBroadcast.add(
                        Observed(transition, registry.isOnline(transition.userId)),
                    )
                    // The broadcaster's callback: fan out to the affected
                    // user's remaining sessions -- exactly what
                    // PresenceBroadcaster does with an edge.
                    registry.sendToUser(transition.userId, NewMessage(message))
                }
            }

            val evictedUser = UUID.randomUUID()
            val broken = mockSessionFor(evictedUser, "recursing")
            `when`(broken.sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java)))
                .thenThrow(IOException("broken pipe"))
            broadcasting.register(evictedUser, broken)
            broadcasting.register(userId, mockSessionFor(userId, "bystander"))
            // Drain the two connect edges' follow-up tasks before the real run.
            while (duringBroadcast.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
                // drain
            }

            // "A presence broadcast": fan out to a user whose only session
            // then fails to deliver, evicting it mid-broadcast.
            broadcasting.sendToUser(evictedUser, NewMessage(message))

            // The follow-up task ran to completion on the other thread, against
            // settled registry state -- not the mid-mutation view a publish
            // inside the compute lambda would have seen.
            val observed = duringBroadcast.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            assertThat(observed?.transition?.userId).isEqualTo(evictedUser)
            assertThat(observed?.transition?.online).isFalse()
            assertThat(observed?.registryStateOnline)
                .describedAs("the edge is published after the registry mutation completed")
                .isFalse()
            assertThat(broadcasting.isOnline(evictedUser)).isFalse()
            assertThat(broadcasting.sessionById("recursing")).isNull()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun registryWithListener(listener: (PresenceTransition) -> Unit): ConnectionRegistry =
        ConnectionRegistry(objectMapper, ApplicationEventPublisher { listener(it as PresenceTransition) })

    private fun mockSessionFor(ownerId: UUID, id: String, open: Boolean = true): WebSocketSession {
        val session = mock(WebSocketSession::class.java)
        `when`(session.id).thenReturn(id)
        `when`(session.isOpen).thenReturn(open)
        `when`(session.attributes).thenReturn(
            mutableMapOf<String, Any>(WebSocketSessionAttributes.USER to AuthenticatedUser(ownerId, "Alice")),
        )
        return session
    }
}
