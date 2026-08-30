package com.example.chat.service

import com.example.chat.config.MessagingProperties
import com.example.chat.repository.MessageRepository
import com.example.chat.ws.protocol.ErrorCodes
import com.example.chat.ws.protocol.SendMessageCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.util.UUID

/**
 * WP4.6 -- pure unit coverage (no DB, no Spring context) of
 * [MessageService.send]'s content validation, proving it is rejected
 * *before* any repository lookup or write attempt (design.md: "reject
 * empty, whitespace-only, or over-length content" ... "before persisting").
 */
class MessageServiceUnitTest {

    private val messageWriter = mock(MessageWriter::class.java)
    private val messageRepository = mock(MessageRepository::class.java)
    private val senderId = UUID.randomUUID()

    private fun service(maxContentLength: Int = 4000) =
        MessageService(messageWriter, messageRepository, MessagingProperties(maxContentLength = maxContentLength))

    @Test
    fun `blank content is rejected as INVALID_CONTENT without touching the repository`() {
        val command = SendMessageCommand(UUID.randomUUID(), UUID.randomUUID(), "   ")

        val result = service().send(senderId, command)

        assertThat(result).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((result as SendResult.Rejected).code).isEqualTo(ErrorCodes.INVALID_CONTENT)
        verifyNoInteractions(messageRepository)
        verifyNoInteractions(messageWriter)
    }

    @Test
    fun `empty content is rejected as INVALID_CONTENT`() {
        val command = SendMessageCommand(UUID.randomUUID(), UUID.randomUUID(), "")

        val result = service().send(senderId, command)

        assertThat(result).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((result as SendResult.Rejected).code).isEqualTo(ErrorCodes.INVALID_CONTENT)
    }

    @Test
    fun `over-length content is rejected as INVALID_CONTENT before persisting`() {
        val command = SendMessageCommand(UUID.randomUUID(), UUID.randomUUID(), "x".repeat(10))

        val result = service(maxContentLength = 9).send(senderId, command)

        assertThat(result).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((result as SendResult.Rejected).code).isEqualTo(ErrorCodes.INVALID_CONTENT)
        verifyNoInteractions(messageWriter)
    }

    @Test
    fun `content at exactly the configured maximum length is accepted by validation`() {
        val command = SendMessageCommand(UUID.randomUUID(), UUID.randomUUID(), "x".repeat(9))
        org.mockito.Mockito.`when`(messageRepository.findBySender_IdAndClientMessageId(senderId, command.clientMessageId))
            .thenReturn(null)
        org.mockito.Mockito.`when`(messageWriter.createAndCommit(senderId, command))
            .thenReturn(WriteResult.ConversationNotFound)

        val result = service(maxContentLength = 9).send(senderId, command)

        // Reaches the writer at all (i.e. was not rejected for length) --
        // the CONVERSATION_NOT_FOUND outcome here just proves validation let
        // it through to the next stage.
        assertThat(result).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((result as SendResult.Rejected).code).isEqualTo(ErrorCodes.CONVERSATION_NOT_FOUND)
    }

    // --- Astral-plane (non-BMP) boundary: the limit counts *code points*,
    //     matching Postgres `varchar(n)`, never UTF-16 `String.length` ---

    @Test
    fun `astral content at exactly the maximum code points is accepted despite double the UTF-16 length`() {
        // U+1F600 is one code point but TWO UTF-16 code units (surrogate
        // pair): this string has codePointCount == max but length == 2 * max.
        // A `String.length`-based check would wrongly reject it -- this test
        // pins the code-point counting fix.
        val command = SendMessageCommand(UUID.randomUUID(), UUID.randomUUID(), "😀".repeat(4000))
        org.mockito.Mockito.`when`(messageRepository.findBySender_IdAndClientMessageId(senderId, command.clientMessageId))
            .thenReturn(null)
        org.mockito.Mockito.`when`(messageWriter.createAndCommit(senderId, command))
            .thenReturn(WriteResult.ConversationNotFound)

        val result = service(maxContentLength = 4000).send(senderId, command)

        // Reached the writer (not rejected for length): CONVERSATION_NOT_FOUND
        // proves validation let it through to the persistence stage.
        assertThat(result).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((result as SendResult.Rejected).code).isEqualTo(ErrorCodes.CONVERSATION_NOT_FOUND)
    }

    @Test
    fun `astral content one code point over the maximum is rejected before persisting`() {
        val command = SendMessageCommand(UUID.randomUUID(), UUID.randomUUID(), "😀".repeat(4001))

        val result = service(maxContentLength = 4000).send(senderId, command)

        assertThat(result).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((result as SendResult.Rejected).code).isEqualTo(ErrorCodes.INVALID_CONTENT)
        verifyNoInteractions(messageWriter)
    }

    // --- Authorization outcomes: the writer's authorization verdicts are
    //     mapped to the stable protocol error codes (CONVERSATION_NOT_FOUND
    //     for a missing conversation, FORBIDDEN for a non-participant) ---

    @Test
    fun `a non-participant sender is rejected as FORBIDDEN`() {
        val command = SendMessageCommand(UUID.randomUUID(), UUID.randomUUID(), "hello")
        org.mockito.Mockito.`when`(messageRepository.findBySender_IdAndClientMessageId(senderId, command.clientMessageId))
            .thenReturn(null)
        org.mockito.Mockito.`when`(messageWriter.createAndCommit(senderId, command))
            .thenReturn(WriteResult.NotParticipant)

        val result = service().send(senderId, command)

        assertThat(result).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((result as SendResult.Rejected).code).isEqualTo(ErrorCodes.FORBIDDEN)
    }

    @Test
    fun `a referenced conversation that does not exist is rejected as CONVERSATION_NOT_FOUND`() {
        val command = SendMessageCommand(UUID.randomUUID(), UUID.randomUUID(), "hello")
        org.mockito.Mockito.`when`(messageRepository.findBySender_IdAndClientMessageId(senderId, command.clientMessageId))
            .thenReturn(null)
        org.mockito.Mockito.`when`(messageWriter.createAndCommit(senderId, command))
            .thenReturn(WriteResult.ConversationNotFound)

        val result = service().send(senderId, command)

        assertThat(result).isInstanceOf(SendResult.Rejected::class.java)
        assertThat((result as SendResult.Rejected).code).isEqualTo(ErrorCodes.CONVERSATION_NOT_FOUND)
    }
}
