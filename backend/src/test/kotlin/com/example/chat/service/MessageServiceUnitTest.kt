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
}
