package com.example.chat.ws

import com.example.chat.security.AuthenticatedUser
import com.example.chat.testsupport.TestObjectMappers
import com.example.chat.ws.protocol.ErrorCodes
import com.example.chat.ws.protocol.ProtocolParser
import com.example.chat.ws.protocol.SendMessageCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.UUID

/**
 * Backend unit coverage (mock [WebSocketSession]s, no network, no Spring
 * context) of the connection-facing half of the protocol: [handleTextMessage]
 * must map *every* parse failure to a stable-code `ERROR{INVALID_COMMAND}`
 * event on the offending connection alone -- degrading, never throwing, and
 * never letting one bad frame terminate the connection's ability to process
 * the next good frame (spec: "Protocol errors are isolated to the offending
 * command").
 *
 * Uses a REAL [ProtocolParser] and REAL [ConnectionRegistry] (with
 * [TestObjectMappers.create], the same shared mapper used by
 * [ProtocolParserTest] and [MessageCommandHandlerFanOutUnitTest]) so the
 * frame -> parse -> protocol-error path is exercised end to end against the
 * same wire shape production produces -- e.g. `default-property-inclusion:
 * non_null` in application.yml, so ConnectionRegistry.send really does omit
 * a correlation-free ErrorEvent.clientMessageId; only [MessageCommandHandler]
 * (the business side, covered by [MessageCommandHandlerTest] and
 * [MessageCommandHandlerFanOutUnitTest]) is mocked.
 */
class WebSocketConnectionHandlerTest {

    private val objectMapper = TestObjectMappers.create()

    private val connectionRegistry = ConnectionRegistry(objectMapper)
    private val protocolParser = ProtocolParser(objectMapper)
    private val messageCommandHandler = mock(MessageCommandHandler::class.java)

    private val handler = WebSocketConnectionHandler(connectionRegistry, protocolParser, messageCommandHandler)

    private val user = AuthenticatedUser(UUID.randomUUID(), "Alice")

    private fun mockSession(id: String, boundUser: AuthenticatedUser? = user): WebSocketSession {
        val session = mock(WebSocketSession::class.java)
        `when`(session.id).thenReturn(id)
        `when`(session.isOpen).thenReturn(true)
        val attributes = mutableMapOf<String, Any>()
        if (boundUser != null) attributes[WebSocketSessionAttributes.USER] = boundUser
        `when`(session.attributes).thenReturn(attributes)
        return session
    }

    private fun sentPayloads(session: WebSocketSession): List<String> {
        val captor = ArgumentCaptor.forClass(TextMessage::class.java)
        verify(session, org.mockito.Mockito.atLeast(0)).sendMessage(captor.capture())
        return captor.allValues.map { it.payload }
    }

    @Test
    fun `malformed JSON produces a correlation-free INVALID_COMMAND error and never throws`() {
        val session = mockSession("s1")

        handler.handleMessage(session, TextMessage("{not valid json"))

        val payloads = sentPayloads(session)
        assertThat(payloads).hasSize(1)
        val error = objectMapper.readTree(payloads.single())
        assertThat(error.get("type").asText()).isEqualTo("ERROR")
        assertThat(error.get("code").asText()).isEqualTo(ErrorCodes.INVALID_COMMAND)
        assertThat(error.has("clientMessageId")).isFalse()
        // The offending frame was never dispatched as a command.
        verifyNoInteractions(messageCommandHandler)
    }

    @Test
    fun `an unknown type discriminator maps to the same stable INVALID_COMMAND code`() {
        val session = mockSession("s2")

        handler.handleMessage(session, TextMessage("""{"type":"TELEPORT","clientMessageId":"${UUID.randomUUID()}"}"""))

        val error = objectMapper.readTree(sentPayloads(session).single())
        assertThat(error.get("type").asText()).isEqualTo("ERROR")
        assertThat(error.get("code").asText()).isEqualTo(ErrorCodes.INVALID_COMMAND)
        verifyNoInteractions(messageCommandHandler)
    }

    @Test
    fun `one invalid frame does not stop the connection from processing the next valid frame`() {
        val session = mockSession("s3")
        val command = SendMessageCommand(
            clientMessageId = UUID.randomUUID(),
            conversationId = UUID.randomUUID(),
            content = "still alive",
        )

        handler.handleMessage(session, TextMessage("{garbage"))
        handler.handleMessage(session, TextMessage(objectMapper.writeValueAsString(command)))

        // Exactly one protocol ERROR for the bad frame...
        val payloads = sentPayloads(session)
        assertThat(payloads).hasSize(1)
        assertThat(objectMapper.readTree(payloads.single()).get("code").asText())
            .isEqualTo(ErrorCodes.INVALID_COMMAND)
        // ...and the good frame on the same connection was still dispatched
        // with the connection-bound identity.
        verify(messageCommandHandler).handle(command, user, session)
    }

    @Test
    fun `a valid SEND_MESSAGE is dispatched with the sender taken from the connection, not the payload`() {
        val session = mockSession("s4")
        val command = SendMessageCommand(
            clientMessageId = UUID.randomUUID(),
            conversationId = UUID.randomUUID(),
            content = "hello",
        )
        // A spoofed senderId in the payload must be irrelevant: the handler
        // passes the handshake-bound AuthenticatedUser through unchanged.
        val payload = """
            {"type":"SEND_MESSAGE","clientMessageId":"${command.clientMessageId}","conversationId":"${command.conversationId}","content":"hello","senderId":"${UUID.randomUUID()}"}
        """.trimIndent()

        handler.handleMessage(session, TextMessage(payload))

        verify(messageCommandHandler).handle(command, user, session)
    }

    @Test
    fun `a frame on a session with no bound identity is dropped silently`() {
        val session = mockSession("s5", boundUser = null)
        val command = SendMessageCommand(
            clientMessageId = UUID.randomUUID(),
            conversationId = UUID.randomUUID(),
            content = "hello",
        )

        handler.handleMessage(session, TextMessage(objectMapper.writeValueAsString(command)))

        verify(session, never()).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage::class.java))
        verifyNoInteractions(messageCommandHandler)
    }
}
