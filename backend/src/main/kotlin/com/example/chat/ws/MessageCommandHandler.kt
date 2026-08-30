package com.example.chat.ws

import com.example.chat.security.AuthenticatedUser
import com.example.chat.service.MessageService
import com.example.chat.service.SendResult
import com.example.chat.ws.protocol.ErrorEvent
import com.example.chat.ws.protocol.InboundCommand
import com.example.chat.ws.protocol.MessageAck
import com.example.chat.ws.protocol.NewMessage
import com.example.chat.ws.protocol.SendMessageCommand
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketSession

/**
 * Bridges a parsed [SendMessageCommand] to [MessageService] and translates
 * the result into outbound events, applying the fan-out rule (design.md /
 * spec: "Confirmation and delivery follow successful persistence"):
 *
 * - [SendResult.Created]: the origin gets exactly one `MESSAGE_ACK`; every
 *   *other* active session of either participant -- including the sender's
 *   own other sessions -- gets `NEW_MESSAGE`. The origin never also receives
 *   a `NEW_MESSAGE` for the same command.
 * - [SendResult.DuplicateMatch]: the retrying origin is re-acked with the
 *   existing authoritative message; nothing is broadcast (the message was
 *   already broadcast once, when it was first created).
 * - [SendResult.Rejected]: a correlated `ERROR` goes to the origin only; no
 *   success event is ever emitted alongside a rejection.
 *
 * [MessageService.send] only returns after its underlying write has already
 * committed (or definitively failed) -- see `MessageWriter`'s doc -- so every
 * event sent from this method already reflects durable state.
 */
@Component
class MessageCommandHandler(
    private val messageService: MessageService,
    private val connectionRegistry: ConnectionRegistry,
) {

    /**
     * [command] is typed as the sealed [InboundCommand] (not
     * [SendMessageCommand] directly) so this entry point stays exhaustive as
     * future command types are added; today [SendMessageCommand] is the only
     * variant.
     */
    fun handle(command: InboundCommand, sender: AuthenticatedUser, originSession: WebSocketSession) {
        when (command) {
            is SendMessageCommand -> handleSendMessage(command, sender, originSession)
        }
    }

    private fun handleSendMessage(command: SendMessageCommand, sender: AuthenticatedUser, originSession: WebSocketSession) {
        when (val result = messageService.send(senderId = sender.id, command = command)) {
            is SendResult.Created -> {
                connectionRegistry.send(originSession, MessageAck(command.clientMessageId, result.message))
                result.participantIds.forEach { participantId ->
                    connectionRegistry.sendToUser(participantId, NewMessage(result.message), excluding = originSession)
                }
            }

            is SendResult.DuplicateMatch ->
                connectionRegistry.send(originSession, MessageAck(command.clientMessageId, result.message))

            is SendResult.Rejected ->
                connectionRegistry.send(
                    originSession,
                    ErrorEvent(clientMessageId = command.clientMessageId, code = result.code, reason = result.reason),
                )
        }
    }
}
