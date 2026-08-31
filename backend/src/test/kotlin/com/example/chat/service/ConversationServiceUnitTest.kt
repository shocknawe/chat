package com.example.chat.service

import com.example.chat.api.dto.ConversationDto
import com.example.chat.api.dto.UserDto
import com.example.chat.api.exception.CallerIsParticipantException
import com.example.chat.api.exception.ParticipantNotFoundException
import com.example.chat.domain.AppUser
import com.example.chat.domain.Conversation
import com.example.chat.repository.AppUserRepository
import com.example.chat.repository.ConversationRepository
import com.example.chat.repository.MessageRepository
import com.example.chat.ws.ConnectionRegistry
import com.example.chat.ws.protocol.ConversationCreated
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

/**
 * Slice 2 (3.2/3.3/3.4) — pure unit coverage (no DB, no Spring context) of
 * [ConversationService.createConversation]'s orchestration decisions:
 *
 * - validation (400 self, 404 unknown participant) happens *before* any write
 *   attempt;
 * - the pre-check hit is a cheap `Existing` that never reaches the writer;
 * - `CONVERSATION_CREATED` goes to the *other* participant only, and only on
 *   the genuinely-created path;
 * - race recovery re-reads in a fresh transaction and answers `Existing` —
 *   and never emits a second event for a conversation it did not create.
 *
 * The transactional mechanics themselves (that the re-read really is outside
 * the rolled-back transaction, that the constraint really rejects the second
 * insert) need a real database and a real proxy boundary; they are proven in
 * [ConcurrentConversationCreationIntegrationTest].
 *
 * Stubs deliberately use concrete argument values rather than Mockito
 * matchers: every method mocked here is declared on Kotlin types with
 * non-nullable parameters, and `eq()`/`any()` evaluate to `null`, which trips
 * the compiler-inserted parameter null checks at the stubbing call site.
 */
class ConversationServiceUnitTest {

    private val conversationRepository = mock(ConversationRepository::class.java)
    private val messageRepository = mock(MessageRepository::class.java)
    private val appUserRepository = mock(AppUserRepository::class.java)
    private val conversationWriter = mock(ConversationWriter::class.java)
    private val connectionRegistry = mock(ConnectionRegistry::class.java)

    private val service = ConversationService(
        conversationRepository,
        messageRepository,
        appUserRepository,
        conversationWriter,
        connectionRegistry,
    )

    private val aliceId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val bobId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val danId = UUID.fromString("55555555-5555-5555-5555-555555555555")

    /** A 1:1 [Conversation] with the two named participants and its canonical pair key. */
    private fun conversationBetween(first: Pair<UUID, String>, second: Pair<UUID, String>): Conversation =
        Conversation(id = UUID.randomUUID(), pairKey = Conversation.pairKeyFor(first.first, second.first)).apply {
            participants += AppUser(id = first.first, displayName = first.second)
            participants += AppUser(id = second.first, displayName = second.second)
        }

    private fun Conversation.toDto(): ConversationDto = ConversationDto(
        id = id,
        participants = participants.map { UserDto(id = it.id, displayName = it.displayName) }.sortedBy { it.displayName },
    )

    // --- Validation happens before any write attempt ---

    @Test
    fun `a creation request naming the caller as the participant is rejected as a client error`() {
        assertThatThrownBy { service.createConversation(aliceId, aliceId) }
            .isInstanceOf(CallerIsParticipantException::class.java)

        verifyNoInteractions(appUserRepository, conversationRepository, conversationWriter, connectionRegistry)
    }

    @Test
    fun `a participant that is not a directory user is rejected as unknown`() {
        given(appUserRepository.existsById(danId)).willReturn(false)

        assertThatThrownBy { service.createConversation(aliceId, danId) }
            .isInstanceOf(ParticipantNotFoundException::class.java)

        verifyNoInteractions(conversationWriter, connectionRegistry)
    }

    // --- Idempotency: the pre-check hit is the common no-write fast path ---

    @Test
    fun `an already-existing pair is returned as Existing without a write or an event`() {
        val existing = conversationBetween(aliceId to "Alice", danId to "Dan")
        given(appUserRepository.existsById(danId)).willReturn(true)
        given(conversationRepository.findByPairKey(Conversation.pairKeyFor(aliceId, danId))).willReturn(existing)

        val result = service.createConversation(aliceId, danId)

        assertThat(result).isEqualTo(CreateResult.Existing(existing.toDto()))
        verifyNoInteractions(conversationWriter, connectionRegistry)
    }

    // --- Creation: exactly one event, to the other participant only ---

    @Test
    fun `a new conversation is Created and its event reaches the other participant only`() {
        val created = conversationBetween(aliceId to "Alice", bobId to "Bob")
        given(appUserRepository.existsById(bobId)).willReturn(true)
        given(conversationRepository.findByPairKey(Conversation.pairKeyFor(aliceId, bobId))).willReturn(null)
        given(conversationWriter.createAndCommit(aliceId, bobId, Conversation.pairKeyFor(aliceId, bobId)))
            .willReturn(created.toDto())

        val result = service.createConversation(aliceId, bobId)

        assertThat(result).isEqualTo(CreateResult.Created(created.toDto()))
        // The other participant only — never the caller, who already holds the
        // REST response. Verified by exact value, not by an argument matcher.
        verify(connectionRegistry).sendToUser(bobId, ConversationCreated(created.toDto()))
    }

    @Test
    fun `losing the concurrent-create race re-reads the winner's row and emits nothing`() {
        val winner = conversationBetween(aliceId to "Alice", bobId to "Bob")
        given(appUserRepository.existsById(bobId)).willReturn(true)
        // First lookup (the pre-check) finds nothing; the re-read finds the
        // winner's committed row.
        given(conversationRepository.findByPairKey(Conversation.pairKeyFor(aliceId, bobId)))
            .willReturn(null, winner)
        org.mockito.Mockito.doThrow(DataIntegrityViolationException("uq_conversation_pair_key"))
            .`when`(conversationWriter).createAndCommit(aliceId, bobId, Conversation.pairKeyFor(aliceId, bobId))

        val result = service.createConversation(aliceId, bobId)

        assertThat(result).isEqualTo(CreateResult.Existing(winner.toDto()))
        // The winning request is the one that emitted the event; the losing
        // request must not emit a second one (docs/openapi.yaml: never sent
        // "when the endpoint returned the 200 (already-existed) case").
        verifyNoInteractions(connectionRegistry)
    }

    @Test
    fun `a constraint violation with no row to re-read is rethrown rather than fabricated`() {
        given(appUserRepository.existsById(bobId)).willReturn(true)
        given(conversationRepository.findByPairKey(Conversation.pairKeyFor(aliceId, bobId))).willReturn(null)
        org.mockito.Mockito.doThrow(DataIntegrityViolationException("uq_conversation_pair_key"))
            .`when`(conversationWriter).createAndCommit(aliceId, bobId, Conversation.pairKeyFor(aliceId, bobId))

        assertThatThrownBy { service.createConversation(aliceId, bobId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        verifyNoInteractions(connectionRegistry)
    }

    // --- The pair key contract itself ---

    @Test
    fun `pairKeyFor is argument-order independent and joins the two sorted UUIDs`() {
        val low = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")

        assertThat(Conversation.pairKeyFor(low, high)).isEqualTo("$low:$high")
        assertThat(Conversation.pairKeyFor(high, low)).isEqualTo("$low:$high")

        // The documented seeded-pair example (design.md decision 2).
        assertThat(Conversation.pairKeyFor(aliceId, bobId))
            .isEqualTo("11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222")
    }
}