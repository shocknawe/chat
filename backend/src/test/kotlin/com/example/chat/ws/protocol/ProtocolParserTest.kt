package com.example.chat.ws.protocol

import com.example.chat.api.dto.MessageDto
import com.example.chat.testsupport.TestObjectMappers
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP4.1 / 4.5 -- pure unit coverage (no Spring context, no DB) of the
 * protocol's Jackson (de)serialization and [ProtocolParser]'s degrade-to-
 * `Invalid`-rather-than-throw behavior for malformed/unsupported commands.
 *
 * Uses [TestObjectMappers.create], a hand-built mapper mirroring every
 * `spring.jackson.*` setting in application.yml, so this test has no Spring
 * dependency at all.
 */
class ProtocolParserTest {

    private val objectMapper = TestObjectMappers.create()

    private val parser = ProtocolParser(objectMapper)

    @Test
    fun `parses a well-formed SEND_MESSAGE command`() {
        val clientMessageId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val json = """
            {"type":"SEND_MESSAGE","clientMessageId":"$clientMessageId","conversationId":"$conversationId","content":"hello"}
        """.trimIndent()

        val result = parser.parse(json)

        assertThat(result).isInstanceOf(ParsedCommand.Valid::class.java)
        val command = (result as ParsedCommand.Valid).command
        assertThat(command).isEqualTo(
            SendMessageCommand(clientMessageId = clientMessageId, conversationId = conversationId, content = "hello"),
        )
    }

    @Test
    fun `ignores an extra client-supplied senderId field rather than trusting or rejecting it`() {
        val clientMessageId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val spoofedSenderId = UUID.randomUUID()
        val json = """
            {"type":"SEND_MESSAGE","clientMessageId":"$clientMessageId","conversationId":"$conversationId","content":"hi","senderId":"$spoofedSenderId"}
        """.trimIndent()

        val result = parser.parse(json)

        assertThat(result).isInstanceOf(ParsedCommand.Valid::class.java)
        val command = (result as ParsedCommand.Valid).command as SendMessageCommand
        // SendMessageCommand has no senderId property at all -- the spoofed
        // value was never bound anywhere, only silently ignored.
        assertThat(command.clientMessageId).isEqualTo(clientMessageId)
        assertThat(command.conversationId).isEqualTo(conversationId)
        assertThat(command.content).isEqualTo("hi")
    }

    @Test
    fun `malformed JSON is reported as Invalid, not thrown`() {
        val result = parser.parse("{not valid json")

        assertThat(result).isInstanceOf(ParsedCommand.Invalid::class.java)
    }

    @Test
    fun `unknown type discriminator is reported as Invalid`() {
        val result = parser.parse("""{"type":"FOO","clientMessageId":"${UUID.randomUUID()}"}""")

        assertThat(result).isInstanceOf(ParsedCommand.Invalid::class.java)
    }

    @Test
    fun `missing type discriminator is reported as Invalid`() {
        val result = parser.parse("""{"clientMessageId":"${UUID.randomUUID()}","conversationId":"${UUID.randomUUID()}","content":"hi"}""")

        assertThat(result).isInstanceOf(ParsedCommand.Invalid::class.java)
    }

    @Test
    fun `SEND_MESSAGE missing a required field is reported as Invalid`() {
        val result = parser.parse("""{"type":"SEND_MESSAGE","clientMessageId":"${UUID.randomUUID()}"}""")

        assertThat(result).isInstanceOf(ParsedCommand.Invalid::class.java)
    }

    @Test
    fun `completely empty payload is reported as Invalid`() {
        val result = parser.parse("")

        assertThat(result).isInstanceOf(ParsedCommand.Invalid::class.java)
    }

    // --- Outbound event serialization: the `type` discriminator round-trips ---

    @Test
    fun `MESSAGE_ACK serializes with a MESSAGE_ACK type discriminator`() {
        val message = MessageDto(
            id = UUID.randomUUID(),
            conversationId = UUID.randomUUID(),
            senderId = UUID.randomUUID(),
            content = "hi",
            createdAt = Instant.now(),
        )
        val event: OutboundEvent = MessageAck(clientMessageId = UUID.randomUUID(), message = message)

        val json = objectMapper.writeValueAsString(event)
        val tree = objectMapper.readTree(json)

        assertThat(tree.get("type").asText()).isEqualTo("MESSAGE_ACK")
        assertThat(tree.get("message").get("id").asText()).isEqualTo(message.id.toString())
    }

    @Test
    fun `NEW_MESSAGE serializes with a NEW_MESSAGE type discriminator`() {
        val message = MessageDto(
            id = UUID.randomUUID(),
            conversationId = UUID.randomUUID(),
            senderId = UUID.randomUUID(),
            content = "hi",
            createdAt = Instant.now(),
        )
        val event: OutboundEvent = NewMessage(message = message)

        val tree = objectMapper.readTree(objectMapper.writeValueAsString(event))

        assertThat(tree.get("type").asText()).isEqualTo("NEW_MESSAGE")
        assertThat(tree.has("clientMessageId")).isFalse()
    }

    @Test
    fun `ERROR serializes with an ERROR type discriminator and omits a null clientMessageId`() {
        val event: OutboundEvent = ErrorEvent(clientMessageId = null, code = ErrorCodes.INVALID_COMMAND, reason = "bad json")

        val tree = objectMapper.readTree(objectMapper.writeValueAsString(event))

        assertThat(tree.get("type").asText()).isEqualTo("ERROR")
        assertThat(tree.get("code").asText()).isEqualTo(ErrorCodes.INVALID_COMMAND)
        assertThat(tree.has("clientMessageId")).isFalse()
    }

    // --- Every stable error code must serialize verbatim -- the frontend
    //     branches on these strings, so renaming one is a protocol break. ---

    @Test
    fun `every stable error code serializes verbatim on the wire`() {
        val stableCodes = listOf(
            ErrorCodes.INVALID_COMMAND,
            ErrorCodes.INVALID_CONTENT,
            ErrorCodes.CONVERSATION_NOT_FOUND,
            ErrorCodes.FORBIDDEN,
            ErrorCodes.CLIENT_MESSAGE_ID_CONFLICT,
            ErrorCodes.PERSISTENCE_ERROR,
        )

        stableCodes.forEach { code ->
            val json = objectMapper.writeValueAsString(
                ErrorEvent(clientMessageId = UUID.randomUUID(), code = code, reason = "test"),
            )
            assertThat(objectMapper.readTree(json).get("code").asText()).isEqualTo(code)
        }
    }

    @Test
    fun `an ERROR event for a rejected command carries the correlating clientMessageId`() {
        val clientMessageId = UUID.randomUUID()
        val event: OutboundEvent =
            ErrorEvent(clientMessageId = clientMessageId, code = ErrorCodes.INVALID_CONTENT, reason = "too long")

        val tree = objectMapper.readTree(objectMapper.writeValueAsString(event))

        assertThat(tree.get("type").asText()).isEqualTo("ERROR")
        assertThat(tree.get("clientMessageId").asText()).isEqualTo(clientMessageId.toString())
        assertThat(tree.get("code").asText()).isEqualTo(ErrorCodes.INVALID_CONTENT)
    }
}
