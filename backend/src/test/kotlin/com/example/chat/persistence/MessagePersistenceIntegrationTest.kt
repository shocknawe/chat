package com.example.chat.persistence

import com.example.chat.domain.AppUser
import com.example.chat.domain.Conversation
import com.example.chat.domain.Message
import com.example.chat.repository.AppUserRepository
import com.example.chat.repository.ConversationRepository
import com.example.chat.repository.MessageRepository
import com.example.chat.seed.DataSeeder
import com.example.chat.seed.SeedData
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Covers WP2 (2.1-2.5) against a real PostgreSQL instance (Testcontainers,
 * not H2 — see repo testing standard): entity mapping/table naming,
 * foreign-key referential integrity, the `(sender_id, client_message_id)`
 * unique constraint, deterministic `(createdAt ASC, id ASC)` history
 * ordering, and seed idempotency.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataSeeder::class)
@Testcontainers
class MessagePersistenceIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("chat")
                .withUsername("chat")
                .withPassword("chat")

        @DynamicPropertySource
        @JvmStatic
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var appUserRepository: AppUserRepository

    @Autowired
    lateinit var conversationRepository: ConversationRepository

    @Autowired
    lateinit var messageRepository: MessageRepository

    @Autowired
    lateinit var dataSeeder: DataSeeder

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser
    private lateinit var conversation: Conversation

    @BeforeEach
    fun setUp() {
        alice = appUserRepository.save(AppUser(id = UUID.randomUUID(), displayName = "Alice"))
        bob = appUserRepository.save(AppUser(id = UUID.randomUUID(), displayName = "Bob"))
        conversation = conversationRepository.save(
            Conversation(id = UUID.randomUUID()).apply {
                participants += alice
                participants += bob
            },
        )
    }

    @Test
    fun `message persists and is retrievable by conversation`() {
        val saved = messageRepository.saveAndFlush(
            Message(conversation = conversation, sender = alice, clientMessageId = UUID.randomUUID(), content = "hello"),
        )

        val history = messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(conversation.id)
        assertThat(history.map { it.id }).containsExactly(saved.id)
    }

    @Test
    fun `unique constraint rejects a true duplicate sender and clientMessageId pair`() {
        val clientMessageId = UUID.randomUUID()
        messageRepository.saveAndFlush(
            Message(conversation = conversation, sender = alice, clientMessageId = clientMessageId, content = "first"),
        )

        assertThatThrownBy {
            messageRepository.saveAndFlush(
                Message(conversation = conversation, sender = alice, clientMessageId = clientMessageId, content = "duplicate retry"),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `same clientMessageId is allowed for different senders`() {
        val clientMessageId = UUID.randomUUID()
        messageRepository.saveAndFlush(
            Message(conversation = conversation, sender = alice, clientMessageId = clientMessageId, content = "from alice"),
        )

        val bobsMessage = messageRepository.saveAndFlush(
            Message(conversation = conversation, sender = bob, clientMessageId = clientMessageId, content = "from bob"),
        )

        assertThat(bobsMessage.id).isNotNull()
    }

    @Test
    fun `lookup by sender and clientMessageId finds the persisted message`() {
        val clientMessageId = UUID.randomUUID()
        val saved = messageRepository.saveAndFlush(
            Message(conversation = conversation, sender = alice, clientMessageId = clientMessageId, content = "hi"),
        )

        val found = messageRepository.findBySender_IdAndClientMessageId(alice.id, clientMessageId)
        assertThat(found?.id).isEqualTo(saved.id)
    }

    @Test
    fun `history is ordered by id when createdAt collides`() {
        val sharedInstant = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val smallerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val largerId = UUID.fromString("00000000-0000-0000-0000-000000000002")

        // Insert in reverse id order to prove ordering isn't accidental insertion order.
        messageRepository.saveAndFlush(
            Message(id = largerId, conversation = conversation, sender = bob, clientMessageId = UUID.randomUUID(), content = "same timestamp, larger id", createdAt = sharedInstant),
        )
        messageRepository.saveAndFlush(
            Message(id = smallerId, conversation = conversation, sender = alice, clientMessageId = UUID.randomUUID(), content = "same timestamp, smaller id", createdAt = sharedInstant),
        )

        val history = messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(conversation.id)
        assertThat(history.map { it.id }).containsExactly(smallerId, largerId)
    }

    @Test
    fun `history orders primarily by createdAt even when ids sort the other way`() {
        val earlierInstant = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS)
        val laterInstant = Instant.now().truncatedTo(ChronoUnit.MICROS)

        // A UUID with no chronological relationship to its createdAt on
        // purpose, proving the UUID is never used as the primary ordering
        // key (design.md: "the UUID is not assumed to be chronological,
        // only a stable deterministic tiebreaker").
        val earlyMessageWithHighId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
        val laterMessageWithLowId = UUID.fromString("00000000-0000-0000-0000-000000000000")

        messageRepository.saveAndFlush(
            Message(id = laterMessageWithLowId, conversation = conversation, sender = alice, clientMessageId = UUID.randomUUID(), content = "later, low id", createdAt = laterInstant),
        )
        messageRepository.saveAndFlush(
            Message(id = earlyMessageWithHighId, conversation = conversation, sender = bob, clientMessageId = UUID.randomUUID(), content = "earlier, high id", createdAt = earlierInstant),
        )

        val history = messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(conversation.id)
        assertThat(history.map { it.id }).containsExactly(earlyMessageWithHighId, laterMessageWithLowId)
    }

    @Test
    fun `preview query returns the last element of the documented history ordering, ties included`() {
        // The wire-preview guarantee (add-conversation-creation-presence-inspector
        // slice 3, task 4.5): `findFirstByConversation_IdOrderByCreatedAtDescIdDesc`
        // is the documented `(createdAt ASC, id ASC)` ordering reversed on BOTH
        // keys, so its first row must be that ordering's last row — even for a
        // `createdAt` tie, and even when the tie-loser was inserted last (an
        // implementation accidentally keyed on insertion order would pick the
        // smaller id here and fail).
        val sharedInstant = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val tieLoser = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val tieWinner = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")

        messageRepository.saveAndFlush(
            Message(id = tieWinner, conversation = conversation, sender = alice, clientMessageId = UUID.randomUUID(), content = "tie winner, inserted first", createdAt = sharedInstant),
        )
        messageRepository.saveAndFlush(
            Message(id = tieLoser, conversation = conversation, sender = bob, clientMessageId = UUID.randomUUID(), content = "tie loser, inserted last", createdAt = sharedInstant),
        )

        val history = messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(conversation.id)
        assertThat(history.map { it.id }).containsExactly(tieLoser, tieWinner)

        val preview = messageRepository.findFirstByConversation_IdOrderByCreatedAtDescIdDesc(conversation.id)
        // Same element the history ends on — the rail preview and the thread
        // tail are two views of one ordering, never two orderings.
        assertThat(preview?.id).isEqualTo(history.last().id).isEqualTo(tieWinner)
        assertThat(preview?.id).isEqualTo(messageRepository.findById(tieWinner).orElseThrow().id)
    }

    @Test
    fun `preview query orders primarily by createdAt and returns null for an empty history`() {
        val earlierInstant = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS)
        val laterInstant = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val laterLowId = UUID.fromString("00000000-0000-0000-0000-000000000000")
        val earlierHighId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")

        // Empty: no preview, the "no messages yet" case the DTO omits.
        assertThat(messageRepository.findFirstByConversation_IdOrderByCreatedAtDescIdDesc(conversation.id)).isNull()

        // Latest = the later createdAt, regardless of the id ordering.
        messageRepository.saveAndFlush(
            Message(id = earlierHighId, conversation = conversation, sender = alice, clientMessageId = UUID.randomUUID(), content = "earlier, high id", createdAt = earlierInstant),
        )
        messageRepository.saveAndFlush(
            Message(id = laterLowId, conversation = conversation, sender = bob, clientMessageId = UUID.randomUUID(), content = "later, low id", createdAt = laterInstant),
        )

        val preview = messageRepository.findFirstByConversation_IdOrderByCreatedAtDescIdDesc(conversation.id)
        assertThat(preview?.id).isEqualTo(laterLowId)

        assertThat(
            messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(conversation.id).last().id,
        ).isEqualTo(preview?.id)
    }

    @Test
    fun `foreign key rejects a message referencing a nonexistent conversation`() {
        val thrown = catchThrowable {
            entityManager.createNativeQuery(
                """
                INSERT INTO message (id, conversation_id, sender_id, client_message_id, content, created_at)
                VALUES (:id, :conversationId, :senderId, :clientMessageId, :content, :createdAt)
                """.trimIndent(),
            )
                .setParameter("id", UUID.randomUUID())
                .setParameter("conversationId", UUID.randomUUID()) // does not exist
                .setParameter("senderId", alice.id)
                .setParameter("clientMessageId", UUID.randomUUID())
                .setParameter("content", "orphaned")
                .setParameter("createdAt", Instant.now())
                .executeUpdate()
        }

        assertThat(thrown).isNotNull()
        assertThat(rootCauseMessage(thrown!!)).contains("fk_message_conversation")
    }

    @Test
    fun `foreign key rejects a message referencing a nonexistent sender`() {
        val thrown = catchThrowable {
            entityManager.createNativeQuery(
                """
                INSERT INTO message (id, conversation_id, sender_id, client_message_id, content, created_at)
                VALUES (:id, :conversationId, :senderId, :clientMessageId, :content, :createdAt)
                """.trimIndent(),
            )
                .setParameter("id", UUID.randomUUID())
                .setParameter("conversationId", conversation.id)
                .setParameter("senderId", UUID.randomUUID()) // does not exist
                .setParameter("clientMessageId", UUID.randomUUID())
                .setParameter("content", "orphaned")
                .setParameter("createdAt", Instant.now())
                .executeUpdate()
        }

        assertThat(thrown).isNotNull()
        assertThat(rootCauseMessage(thrown!!)).contains("fk_message_sender")
    }

    @Test
    fun `foreign key rejects a participant link referencing a nonexistent user`() {
        val thrown = catchThrowable {
            entityManager.createNativeQuery(
                "INSERT INTO conversation_participant (conversation_id, user_id) VALUES (:conversationId, :userId)",
            )
                .setParameter("conversationId", conversation.id)
                .setParameter("userId", UUID.randomUUID()) // does not exist
                .executeUpdate()
        }

        assertThat(thrown).isNotNull()
        assertThat(rootCauseMessage(thrown!!)).contains("fk_conversation_participant_user")
    }

    @Test
    fun `foreign key rejects a participant link referencing a nonexistent conversation`() {
        val thrown = catchThrowable {
            entityManager.createNativeQuery(
                "INSERT INTO conversation_participant (conversation_id, user_id) VALUES (:conversationId, :userId)",
            )
                .setParameter("conversationId", UUID.randomUUID()) // does not exist
                .setParameter("userId", alice.id)
                .executeUpdate()
        }

        assertThat(thrown).isNotNull()
        assertThat(rootCauseMessage(thrown!!)).contains("fk_conversation_participant_conversation")
    }

    @Test
    fun `seeding twice does not duplicate the fixed MVP users or conversation`() {
        val args = DefaultApplicationArguments()

        dataSeeder.run(args)
        dataSeeder.run(args)

        // 2 fixture users from setUp() + 5 fixed-id seed users
        // (Alice, Bob, Carol, Dan, Erin), never duplicated.
        assertThat(appUserRepository.count()).isEqualTo(7)
        assertThat(appUserRepository.findById(SeedData.ALICE_ID)).isPresent
        assertThat(appUserRepository.findById(SeedData.BOB_ID)).isPresent
        assertThat(appUserRepository.findById(SeedData.CAROL_ID)).isPresent
        assertThat(appUserRepository.findById(SeedData.DAN_ID)).isPresent
        assertThat(appUserRepository.findById(SeedData.ERIN_ID)).isPresent

        // 1 fixture conversation from setUp() + 2 fixed-id seed conversations
        // (Alice<->Bob, Alice<->Carol), never duplicated.
        assertThat(conversationRepository.count()).isEqualTo(3)
        val seededConversation = conversationRepository.findById(SeedData.CONVERSATION_ID).orElseThrow()
        assertThat(seededConversation.participants.map { it.id })
            .containsExactlyInAnyOrder(SeedData.ALICE_ID, SeedData.BOB_ID)

        val aliceCarolConversation = conversationRepository.findById(SeedData.ALICE_CAROL_CONVERSATION_ID)
            .orElseThrow()
        assertThat(aliceCarolConversation.participants.map { it.id })
            .containsExactlyInAnyOrder(SeedData.ALICE_ID, SeedData.CAROL_ID)
        assertThat(
            messageRepository.findByConversation_IdOrderByCreatedAtAscIdAsc(SeedData.ALICE_CAROL_CONVERSATION_ID),
        ).hasSize(3)
    }
}

private tailrec fun rootCause(ex: Throwable): Throwable =
    if (ex.cause == null || ex.cause === ex) ex else rootCause(ex.cause!!)

private fun rootCauseMessage(ex: Throwable): String = rootCause(ex).message.orEmpty()
