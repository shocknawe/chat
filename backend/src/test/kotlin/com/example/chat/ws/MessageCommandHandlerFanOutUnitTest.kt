package com.example.chat.ws

import com.example.chat.api.dto.MessageDto
import com.example.chat.security.AuthenticatedUser
import com.example.chat.service.MessageService
import com.example.chat.service.SendResult
import com.example.chat.testsupport.TestObjectMappers
import com.example.chat.ws.protocol.SendMessageCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * Backend unit coverage (mock [WebSocketSession]s, no network, no DB, no
 * Spring context) of the *observed wire outcome* of the fan-out rule, wiring
 * a real [ConnectionRegistry] behind [MessageCommandHandler] so the whole
 * "ack the origin only, broadcast to every other participant session" path
 * is exercised against real registry bookkeeping rather than verified only
 * as mock interactions ([MessageCommandHandlerTest] covers that layer).
 *
 * Spec: "Participants receive the new-message event", "Each active session
 * of a participant is delivered to", "Origin receives one authoritative
 * delivery path", "One failed session send is isolated".
 */
class MessageCommandHandlerFanOutUnitTest {

    private val objectMapper = TestObjectMappers.create()
    private val registry = ConnectionRegistry(objectMapper)
    private val messageService = mock(MessageService::class.java)
    private val handler = MessageCommandHandler(messageService, registry)

    private val sender = AuthenticatedUser(UUID.randomUUID(), "Alice")
    private val recipient = AuthenticatedUser(UUID.randomUUID(), "Bob")

    private val command = SendMessageCommand(
        clientMessageId = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        content = "hello",
    )
    private val message = MessageDto(
        id = UUID.randomUUID(),
        conversationId = command.conversationId,
        senderId = sender.id,
        content = command.content,
        createdAt = Instant.now(),
    )

    private fun mockSession(id: String, user: AuthenticatedUser): WebSocketSession {
        val session = mock(WebSocketSession::class.java)
        `when`(session.id).thenReturn(id)
        `when`(session.isOpen).thenReturn(true)
        `when`(session.attributes).thenReturn(mutableMapOf<String, Any>(WebSocketSessionAttributes.USER to user))
        return session
    }

    private fun sentPayloads(session: WebSocketSession): List<String> {
        val captor = ArgumentCaptor.forClass(TextMessage::class.java)
        org.mockito.Mockito.verify(session, atLeast(0)).sendMessage(captor.capture())
        return captor.allValues.map { it.payload }
    }

    @Test
    fun `sender's other session and the recipient receive NEW_MESSAGE while the origin receives only MESSAGE_ACK`() {
        val origin = mockSession("origin", sender)
        val senderOtherSession = mockSession("sender-other", sender)
        val recipientSession = mockSession("recipient", recipient)
        registry.register(sender.id, origin)
        registry.register(sender.id, senderOtherSession)
        registry.register(recipient.id, recipientSession)
        `when`(messageService.send(sender.id, command))
            .thenReturn(SendResult.Created(message, participantIds = linkedSetOf(sender.id, recipient.id)))

        handler.handle(command, sender, origin)

        // Origin: exactly one frame, and it is the correlated ACK -- never a
        // NEW_MESSAGE for its own command (one authoritative delivery path).
        val originPayloads = sentPayloads(origin)
        assertThat(originPayloads).hasSize(1)
        val ack = objectMapper.readTree(originPayloads.single())
        assertThat(ack.get("type").asText()).isEqualTo("MESSAGE_ACK")
        assertThat(ack.get("clientMessageId").asText()).isEqualTo(command.clientMessageId.toString())
        assertThat(ack.get("message").get("id").asText()).isEqualTo(message.id.toString())

        // The sender's second session and the recipient's session both get
        // the same NEW_MESSAGE event.
        listOf(senderOtherSession, recipientSession).forEach { session ->
            val payloads = sentPayloads(session)
            assertThat(payloads).hasSize(1)
            val event = objectMapper.readTree(payloads.single())
            assertThat(event.get("type").asText()).isEqualTo("NEW_MESSAGE")
            assertThat(event.get("message").get("id").asText()).isEqualTo(message.id.toString())
            assertThat(event.has("clientMessageId")).isFalse()
        }
    }

    @Test
    fun `a session that throws on send does not break the ack or delivery to the remaining sessions`() {
        val origin = mockSession("origin", sender)
        val senderOtherSession = mockSession("sender-other", sender)
        val brokenRecipientSession = mockSession("broken-recipient", recipient)
        `when`(brokenRecipientSession.sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java)))
            .thenThrow(IOException("broken pipe"))
        registry.register(sender.id, origin)
        registry.register(sender.id, senderOtherSession)
        registry.register(recipient.id, brokenRecipientSession)
        `when`(messageService.send(sender.id, command))
            .thenReturn(SendResult.Created(message, participantIds = linkedSetOf(sender.id, recipient.id)))

        handler.handle(command, sender, origin)

        // Delivery was unaffected for every session except the broken one.
        val ack = objectMapper.readTree(sentPayloads(origin).single())
        assertThat(ack.get("type").asText()).isEqualTo("MESSAGE_ACK")
        val broadcast = objectMapper.readTree(sentPayloads(senderOtherSession).single())
        assertThat(broadcast.get("type").asText()).isEqualTo("NEW_MESSAGE")
        // ...and the failing session was evicted from the registry so future
        // fan-outs skip it instead of repeatedly failing.
        assertThat(registry.sessionById("broken-recipient")).isNull()
    }

    @Test
    fun `a broken session does not prevent delivery to a second session for the same recipient`() {
        // Both sessions below are registered under the SAME recipient (unlike
        // the previous test, where the broken session was the only session
        // for its user), so this exercises ConnectionRegistry#sendToUser's
        // per-user snapshot-and-forEach path directly: one entry in the same
        // user's session set throwing must not stop the sibling entry in
        // that same set from being reached.
        val origin = mockSession("origin", sender)
        val brokenRecipientSession = mockSession("recipient-broken", recipient)
        val healthyRecipientSession = mockSession("recipient-healthy", recipient)
        `when`(brokenRecipientSession.sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java)))
            .thenThrow(IOException("broken pipe"))
        registry.register(sender.id, origin)
        registry.register(recipient.id, brokenRecipientSession)
        registry.register(recipient.id, healthyRecipientSession)
        `when`(messageService.send(sender.id, command))
            .thenReturn(SendResult.Created(message, participantIds = linkedSetOf(sender.id, recipient.id)))

        handler.handle(command, sender, origin)

        // The healthy sibling session still received the NEW_MESSAGE despite
        // the other session in the same recipient's set throwing on send.
        val payloads = sentPayloads(healthyRecipientSession)
        assertThat(payloads).hasSize(1)
        val event = objectMapper.readTree(payloads.single())
        assertThat(event.get("type").asText()).isEqualTo("NEW_MESSAGE")
        assertThat(event.get("message").get("id").asText()).isEqualTo(message.id.toString())
        // Only the session that actually threw was evicted...
        assertThat(registry.sessionById("recipient-broken")).isNull()
        // ...the healthy sibling remains registered for future fan-outs.
        assertThat(registry.sessionById("recipient-healthy")).isNotNull()
    }
}
