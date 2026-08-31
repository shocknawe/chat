package com.example.chat.service

import com.example.chat.domain.Conversation
import com.example.chat.seed.SeedData
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import org.springframework.http.ResponseEntity
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Slice 2 (task 3.10): two creation requests for the same participant pair
 * racing for real, against a real PostgreSQL `uq_conversation_pair_key`
 * constraint. Both requests must succeed, and exactly one conversation row may
 * exist for the pair afterwards.
 *
 * A blind "spin N threads and hope" race test is a bad oracle here: it passes
 * even when the requests serialise (the second one would simply find the pair
 * on its pre-check and answer 200), proving nothing about the
 * constraint-and-re-read recovery. So the race is *staged* deterministically
 * instead: the first request is held at the [ConversationWriter] boundary (a
 * spy of the real writer) *before* its transaction even opens, until the second
 * request has passed the existence check, inserted, and committed. Both
 * requests have then passed the application-level check — exactly the state
 * the defensive design assumes — and the constraint is the only thing left to
 * decide who wins:
 *
 * - request 1: transaction opened only after request 2 committed → the
 *   constraint rejects its INSERT → its transaction rolls back → the service
 *   re-reads in a fresh transaction → `200` (Existing);
 * - request 2: inserts and commits untouched → `201` (Created).
 *
 * The choreography also pins the property the recovery depends on: the
 * catch/re-read really does run *outside* the rolled-back transaction. Catching
 * the violation inside the doomed transaction would surface as
 * `UnexpectedRollbackException`, failing this test rather than hanging or
 * flaking.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConcurrentConversationCreationIntegrationTest {

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

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    /**
     * Spied, not replaced: [ConversationWriter] remains the real one (the spy
     * delegates through `callRealMethod` into the proxy, which opens the real
     * transaction and does the real insert). The spy's only role is to hold
     * the loser at the writer boundary before its transaction opens. No other
     * test lives in this class, so this override never leaks.
     *
     * The writer, not its [ConversationRepository], is the staging point
     * because a Spring Data repository is an *interface*: Mockito cannot
     * `callRealMethod()` on an abstract method, so the insert either has to be
     * faked (losing the very constraint behaviour under test) or fails with
     * `MockitoException`. Holding the class at the proxy boundary also holds
     * *before* the loser's transaction even opens, which keeps the two
     * transactions from ever overlapping: the winner's row is fully committed
     * and the loser's INSERT is rejected by the constraint alone — not by a
     * lock wait — which is exactly the state the recovery assumes.
     */
    @MockitoSpyBean
    lateinit var conversationWriter: ConversationWriter

    private fun url(path: String) = "http://localhost:$port$path"

    private fun createConversation(callerId: UUID, participantId: UUID) =
        restTemplate.exchange(
            url("/api/conversations"),
            HttpMethod.POST,
            HttpEntity(
                """{"participantId":"$participantId"}""",
                HttpHeaders().apply {
                    set("X-User-Id", callerId.toString())
                    contentType = MediaType.APPLICATION_JSON
                },
            ),
            String::class.java,
        )

    /** A raw, spy-free COUNT of the pair key rows (an independent referee, not the code under test). */
    private fun rowCountForPairKey(pairKey: String): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM conversation WHERE pair_key = ?").use { stmt ->
                stmt.setString(1, pairKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun `two concurrent creations for the same pair persist exactly one conversation and both succeed`() {
        // Hold the FIRST writer call before its transaction opens; released in
        // step 3 below (and unconditionally in the finally block).
        val firstWriteArrived = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val writes = AtomicInteger()
        val alice = SeedData.ALICE_ID
        val erin = SeedData.ERIN_ID
        val pairKey = Conversation.pairKeyFor(alice, erin)
        doAnswer { invocation: org.mockito.invocation.InvocationOnMock ->
            if (writes.incrementAndGet() == 1) {
                firstWriteArrived.countDown()
                assertThat(releaseFirstWrite.await(15, TimeUnit.SECONDS))
                    .describedAs("the second request had to complete before the first insert ran")
                    .isTrue()
            }
            invocation.callRealMethod()
        // Concrete arguments, not matchers (Kotlin non-null parameter checks
        // trip on matcher nulls) — and scoped to this exact pair, so any other
        // creation that ever ran here would not be held.
        }.`when`(conversationWriter).createAndCommit(alice, erin, pairKey)

        val pool = Executors.newFixedThreadPool(2)
        try {
            // 1. Request 1 runs up to — but not past — its transactional insert.
            val first: java.util.concurrent.Future<ResponseEntity<String>> =
                pool.submit(java.util.concurrent.Callable { createConversation(alice, erin) })
            assertThat(firstWriteArrived.await(15, TimeUnit.SECONDS)).isTrue()

            // 2. Request 2 passes the existence pre-check (nothing visible yet),
            //    inserts, and commits. Joining before the release guarantees the
            //    winner's row is committed by the time the loser's INSERT runs,
            //    so the loser's failure is the constraint, not a lock timeout.
            val second: java.util.concurrent.Future<ResponseEntity<String>> =
                pool.submit(java.util.concurrent.Callable { createConversation(alice, erin) })
            val secondResponse = second.get(30, TimeUnit.SECONDS)
            assertThat(secondResponse.statusCode)
                .describedAs("the request that won the race (inserted and committed first)")
                .isEqualTo(HttpStatus.CREATED)
            val createdConversationId = objectMapper.readTree(secondResponse.body).get("id").asText()

            // 3. The loser's INSERT now collides with the committed row.
            releaseFirstWrite.countDown()
            val firstResponse = first.get(30, TimeUnit.SECONDS)

            // Both requests succeed: one created (201), one found existing (200),
            // and both bodies announce the same single conversation.
            assertThat(firstResponse.statusCode)
                .describedAs("the request that lost the race must recover via re-read, not fail")
                .isEqualTo(HttpStatus.OK)
            assertThat(objectMapper.readTree(firstResponse.body).get("id").asText())
                .isEqualTo(createdConversationId)

            // Exactly one persisted conversation for the pair — the database,
            // not the service, is what made this true.
            assertThat(rowCountForPairKey(pairKey)).isEqualTo(1)
        } finally {
            releaseFirstWrite.countDown()
            pool.shutdownNow()
        }
    }
}