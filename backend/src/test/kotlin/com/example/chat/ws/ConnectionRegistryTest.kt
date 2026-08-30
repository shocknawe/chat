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
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.time.Instant
import java.util.UUID

/**
 * WP4.4 / 4.7 -- unit coverage (mock [WebSocketSession]s, no real network or
 * Spring context) of [ConnectionRegistry]'s fan-out and failure-isolation
 * behavior: origin exclusion, delivery to every other session of a user
 * (including a second session of the same user), and evicting exactly the
 * session whose send fails without affecting delivery to sibling sessions.
 */
class ConnectionRegistryTest {

    private val objectMapper = JsonMapper.builder().addModule(kotlinModule()).findAndAddModules().build()
    private lateinit var registry: ConnectionRegistry

    private val userId = UUID.randomUUID()
    private val message = MessageDto(
        id = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        senderId = userId,
        content = "hi",
        createdAt = Instant.now(),
    )

    @BeforeEach
    fun setUp() {
        registry = ConnectionRegistry(objectMapper)
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
}
