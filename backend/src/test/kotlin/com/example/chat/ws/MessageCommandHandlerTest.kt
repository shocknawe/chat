package com.example.chat.ws

import com.example.chat.api.dto.MessageDto
import com.example.chat.security.AuthenticatedUser
import com.example.chat.service.MessageService
import com.example.chat.service.SendResult
import com.example.chat.ws.protocol.ErrorCodes
import com.example.chat.ws.protocol.ErrorEvent
import com.example.chat.ws.protocol.MessageAck
import com.example.chat.ws.protocol.NewMessage
import com.example.chat.ws.protocol.SendMessageCommand
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.web.socket.WebSocketSession
import java.time.Instant
import java.util.UUID

/**
 * WP4.7 / 4.8 -- unit coverage of [MessageCommandHandler]'s translation of a
 * [SendResult] into outbound events, with [MessageService] and
 * [ConnectionRegistry] mocked so this test isolates the fan-out *rule*
 * itself: origin gets ack-only (never also a broadcast for the same
 * command), other participant sessions get `NEW_MESSAGE`, and a rejection
 * never also emits a success event.
 */
class MessageCommandHandlerTest {

    private val messageService = mock(MessageService::class.java)
    private val connectionRegistry = mock(ConnectionRegistry::class.java)
    private val handler = MessageCommandHandler(messageService, connectionRegistry)

    private val sender = AuthenticatedUser(UUID.randomUUID(), "Alice")
    private val recipientId = UUID.randomUUID()
    private val originSession = mock(WebSocketSession::class.java)
    private val command = SendMessageCommand(
        clientMessageId = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        content = "hello",
    )
    private val message = MessageDto(
        id = UUID.randomUUID(),
        conversationId = command.conversationId,
        senderId = sender.id,
        clientMessageId = command.clientMessageId,
        content = command.content,
        createdAt = Instant.now(),
    )

    @Test
    fun `on Created, origin gets exactly one ack and other participants get NEW_MESSAGE excluding the origin`() {
        `when`(messageService.send(sender.id, command))
            .thenReturn(SendResult.Created(message, participantIds = setOf(sender.id, recipientId)))

        handler.handle(command, sender, originSession)

        verify(connectionRegistry).send(originSession, MessageAck(command.clientMessageId, message))
        verify(connectionRegistry).sendToUser(sender.id, NewMessage(message), excluding = originSession)
        verify(connectionRegistry).sendToUser(recipientId, NewMessage(message), excluding = originSession)
        // Origin must never also receive a NEW_MESSAGE broadcast call directly.
        verify(connectionRegistry, never()).send(originSession, NewMessage(message))
    }

    @Test
    fun `on DuplicateMatch, origin is re-acked and nothing is broadcast`() {
        `when`(messageService.send(sender.id, command)).thenReturn(SendResult.DuplicateMatch(message))

        handler.handle(command, sender, originSession)

        verify(connectionRegistry).send(originSession, MessageAck(command.clientMessageId, message))
        // Nothing else was ever sent -- no re-broadcast alongside the re-ack.
        verifyNoMoreInteractions(connectionRegistry)
    }

    @Test
    fun `on Rejected, origin gets a correlated ERROR and no ack or broadcast is ever sent`() {
        `when`(messageService.send(sender.id, command))
            .thenReturn(SendResult.Rejected(ErrorCodes.INVALID_CONTENT, "too long"))

        handler.handle(command, sender, originSession)

        verify(connectionRegistry).send(
            originSession,
            ErrorEvent(clientMessageId = command.clientMessageId, code = ErrorCodes.INVALID_CONTENT, reason = "too long"),
        )
        // Nothing else was ever sent -- no ack, no broadcast alongside a rejection.
        verifyNoMoreInteractions(connectionRegistry)
    }
}
