package com.example.chat.persistence

import com.example.chat.domain.AppUser
import com.example.chat.domain.Conversation
import com.example.chat.repository.AppUserRepository
import com.example.chat.repository.ConversationRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * Slice 2 (3.1) — the `pair_key` column and its `uq_conversation_pair_key`
 * unique constraint, against real PostgreSQL (Testcontainers, not H2 — see the
 * repo testing standard), covering the properties the creation race defence
 * rests on:
 *
 * - the constraint is genuinely present in the schema (not merely declared on
 *   the entity — declaring it and having Hibernate create it are two different
 *   events under `ddl-auto: update`);
 * - a second insert with the same pair key is rejected, which is the mechanism
 *   `ConversationWriter`/`ConversationService` rely on to resolve the
 *   concurrent-create race;
 * - any number of `NULL` pair keys coexist, which is what keeps the column
 *   nullable-by-design viable (group conversations, pre-backfill rows).
 *
 * The colliding inserts run in their own *committed* transactions
 * (`@DataJpaTest` wraps each test in one open rollback-by-default transaction,
 * and a rejected INSERT aborts that transaction mid-test). Real services
 * insert in their own committed transaction — that is precisely the state the
 * race recovery assumes — so each insert here runs `REQUIRES_NEW` and
 * commits, and the surviving row is then counted through a raw JDBC connection
 * outside the (rolled-back) test transaction.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ConversationPairKeyPersistenceIntegrationTest {

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
    lateinit var transactionManager: PlatformTransactionManager

    /**
     * A `REQUIRES_NEW` template: the unique-violating insert must happen in its
     * own transaction (as `ConversationWriter` does) both to mirror the race
     * being modelled and because a rejected INSERT aborts the transaction it
     * runs in — it cannot share the test's rollback-by-default transaction.
     */
    private fun independentTransaction() = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionTemplate.PROPAGATION_REQUIRES_NEW
    }

    private fun insertConversation(pairKey: String?) {
        independentTransaction().executeWithoutResult { _ ->
            conversationRepository.saveAndFlush(Conversation(id = UUID.randomUUID(), pairKey = pairKey))
        }
    }

    private fun constraintNames(): List<String> =
        entityManager.createNativeQuery(
            """
            SELECT c.conname
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            WHERE c.contype = 'u' AND t.relname = 'conversation'
            """.trimIndent(),
        ).resultList as List<String>

    /** Raw JDBC count of the rows carrying [pairKey] — deliberately outside any Spring-managed persistence context, so the assertion does not depend on the code it checks. */
    private fun rowCountInDatabase(pairKey: String): Int =
        java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM conversation WHERE pair_key = ?").use { stmt ->
                stmt.setString(1, pairKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `the conversation table carries the uq_conversation_pair_key unique constraint`() {
        assertThat(constraintNames()).contains("uq_conversation_pair_key")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `a second conversation carrying the same pair key is rejected by the constraint`() {
        insertConversation("aaaa:bbbb")

        assertThatThrownBy {
            insertConversation("aaaa:bbbb")
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        // Exactly one row survives — the winner of the insert, which is the row
        // the race recovery re-reads.
        assertThat(rowCountInDatabase("aaaa:bbbb")).isEqualTo(1)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `many conversations without a pair key are allowed together`() {
        // Nullable-by-design (design.md decision 2): group conversations and
        // not-yet-backfilled rows must never trip the unique constraint.
        insertConversation(null)
        insertConversation(null)
        insertConversation(null)

        assertThat(conversationRepository.findByPairKeyIsNull()).hasSize(3)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `distinct pairs coexist under the constraint`() {
        insertConversation("eeee:ffff")
        insertConversation("eeee:fff0")

        assertThat(conversationRepository.findByPairKey("eeee:ffff")).isNotNull
        assertThat(conversationRepository.findByPairKey("eeee:fff0")).isNotNull
    }
}