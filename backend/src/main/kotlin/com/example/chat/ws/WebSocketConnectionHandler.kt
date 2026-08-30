package com.example.chat.ws

import com.example.chat.security.AuthenticatedUser
import com.example.chat.ws.protocol.ErrorCodes
import com.example.chat.ws.protocol.ErrorEvent
import com.example.chat.ws.protocol.ParsedCommand
import com.example.chat.ws.protocol.ProtocolParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler

/**
 * The raw `TextWebSocketHandler` at the front of the realtime pipeline
 * (design.md: "`WebSocketConnectionHandler -> ProtocolParser ->
 * MessageCommandHandler -> MessageService -> PostgreSQL`").
 *
 * Registers/unregisters connections in the [ConnectionRegistry] and routes
 * every inbound text frame through [ProtocolParser]. A frame that fails to
 * parse gets a correlation-free `ERROR{code=INVALID_COMMAND}` back on that
 * connection alone (spec: "Protocol errors are isolated to the offending
 * command") -- the connection itself is never closed for a bad frame.
 */
@Component
class WebSocketConnectionHandler(
    private val connectionRegistry: ConnectionRegistry,
    private val protocolParser: ProtocolParser,
    private val messageCommandHandler: MessageCommandHandler,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(WebSocketConnectionHandler::class.java)

    companion object {
        /** Max time allowed to write a single outbound frame before the session is treated as stuck/slow. */
        private const val SEND_TIME_LIMIT_MS = 10_000

        /** Max bytes buffered for a session before it is treated as a slow consumer and evicted. */
        private const val SEND_BUFFER_SIZE_LIMIT = 512 * 1024
    }

    /**
     * [UserIdHandshakeInterceptor] has already validated the connection and
     * bound its [AuthenticatedUser] into the handshake attributes (copied
     * into `session.attributes` by Spring). Registration wraps the raw
     * session in a [ConcurrentWebSocketSessionDecorator] -- Spring WebSocket
     * sessions do not support concurrent `sendMessage` calls, and this
     * connection can be sent to concurrently from multiple threads (its own
     * command's ack, plus fan-out from other users' `SEND_MESSAGE`
     * commands) -- so all sends to this session, from any thread, go through
     * one buffered, time-limited, effectively-serialized decorator instance
     * (design.md: "each registered session is wrapped with
     * `ConcurrentWebSocketSessionDecorator`").
     */
    override fun afterConnectionEstablished(session: WebSocketSession) {
        val user = session.attributes[WebSocketSessionAttributes.USER] as? AuthenticatedUser
        if (user == null) {
            // Defensive only: the handshake interceptor should already have
            // rejected any connection without a validated identity.
            log.warn("WebSocket session {} established without a bound identity; closing", session.id)
            runCatching { session.close(CloseStatus.POLICY_VIOLATION) }
            return
        }

        val decorated = ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT)
        connectionRegistry.register(user.id, decorated)
        log.debug("Registered WebSocket session {} for user {}", session.id, user.id)
    }

    /**
     * The `session` argument here is the raw, undecorated session Spring
     * always passes to handler callbacks -- never the decorator wrapper
     * created in [afterConnectionEstablished]. Every outbound send must go
     * through that decorator, so the registered instance is looked up by
     * session id (falling back to the raw session only in the defensive case
     * where registration never happened).
     */
    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val user = session.attributes[WebSocketSessionAttributes.USER] as? AuthenticatedUser ?: return
        val target = connectionRegistry.sessionById(session.id) ?: session

        when (val parsed = protocolParser.parse(message.payload)) {
            is ParsedCommand.Invalid ->
                connectionRegistry.send(
                    target,
                    ErrorEvent(clientMessageId = null, code = ErrorCodes.INVALID_COMMAND, reason = parsed.reason),
                )

            is ParsedCommand.Valid ->
                messageCommandHandler.handle(parsed.command, sender = user, originSession = target)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        cleanup(session)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        log.debug("Transport error on WebSocket session {}; removing from registry", session.id, exception)
        cleanup(session)
    }

    private fun cleanup(session: WebSocketSession) {
        val user = session.attributes[WebSocketSessionAttributes.USER] as? AuthenticatedUser ?: return
        val registered = connectionRegistry.sessionById(session.id) ?: session
        connectionRegistry.unregister(user.id, registered)
        log.debug("Unregistered WebSocket session {} for user {}", session.id, user.id)
    }
}
