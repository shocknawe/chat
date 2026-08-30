package com.example.chat.startup

import com.example.chat.ChatApplication
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * `add-conversation-creation-presence-inspector` tasks 3.1a/3.1b: the boot-time
 * presence assertion for `uq_conversation_pair_key`
 * ([PairKeyUniqueConstraintValidator]), exercised against real PostgreSQL
 * through the full [ChatApplication] boot path.
 *
 * The property under test is **not** "a fresh schema contains the constraint"
 * — every integration test in this repo already proves that incidentally,
 * because they boot a fresh schema and must therefore pass the validator. What
 * must be proven here is the case the assertion exists for: a database created
 * *before* the constraint (or upgraded onto one `ddl-auto: update` failed to
 * repair) refuses to start. The retained-volume shape is approximated as
 * [com.example.chat.persistence.RestartRetentionIntegrationTest] approximates a
 * restart — multiple independent boots of the real application context against
 * one persistent Testcontainers database — with the dangerous state produced
 * explicitly:
 *
 * 1. **Boot 1** (`ddl-auto=update`): the schema, constraint, and seed data a
 *    current deployment would have.
 * 2. Raw SQL drops the constraint, reconstructing exactly the pre-constraint /
 *    failed-upgrade database: `pair_key` present and populated, uniqueness not
 *    enforced.
 * 3. **Boot 2** (`ddl-auto=none`): Hibernate is forbidden from repairing the
 *    schema, mimicking a frozen retained volume, so the boot sees the
 *    constraint's genuine absence and must fail — not silently continue.
 * 4. The documented operator fix is applied by hand (`ADD CONSTRAINT`), and
 *    **boot 3** (`ddl-auto=none`, same untouched-in-otherwise database)
 *    succeeds. This is the positive control proving step 3 failed *because of
 *    the missing constraint* and not because of the pre-seeded data, the
 *    frozen schema, or the multi-boot setup itself.
 */
@Testcontainers
class PairKeyUniqueConstraintBootIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("chat")
                .withUsername("chat")
                .withPassword("chat")
    }

    private fun boot(vararg jvmArgs: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(ChatApplication::class.java)
            .run(
                "--server.port=0",
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                *jvmArgs,
            )

    /** Raw JDBC, deliberately outside any Spring context (the seeder owns none of this state). */
    private fun executeSql(vararg statements: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach(statement::execute)
            }
        }
    }

    private fun dropPairKeyConstraint() =
        executeSql("ALTER TABLE conversation DROP CONSTRAINT IF EXISTS ${PairKeyUniqueConstraintValidator.CONSTRAINT_NAME}")

    private fun addPairKeyConstraint() =
        executeSql(
            "ALTER TABLE conversation DROP CONSTRAINT IF EXISTS ${PairKeyUniqueConstraintValidator.CONSTRAINT_NAME}",
            "ALTER TABLE conversation ADD CONSTRAINT ${PairKeyUniqueConstraintValidator.CONSTRAINT_NAME} UNIQUE (pair_key)",
        )

    private fun jdbcConstraintCount(): Long =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = '${PairKeyUniqueConstraintValidator.CONSTRAINT_NAME}'",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    @Test
    fun `boot fails against a pre-constraint database and succeeds once the constraint is restored`() {
        // 1. A normal boot: full current schema (constraint included) and seed data.
        boot("--spring.jpa.hibernate.ddl-auto=update").close()
        assertThat(jdbcConstraintCount()).isEqualTo(1)

        // 2. Simulate the database this assertion guards against: a retained
        //    volume that predates the constraint, or an upgrade pass where
        //    `ddl-auto: update` added the pair_key column but not the
        //    table-level constraint.
        dropPairKeyConstraint()
        assertThat(jdbcConstraintCount()).isZero()

        // 3. Booting against that frozen schema (no Hibernate repair possible)
        //    must fail, with the failing constraint named in the failure.
        val bootFailure = catchThrowable { boot("--spring.jpa.hibernate.ddl-auto=none") }
        assertThat(bootFailure).isNotNull()
        assertThat(rootCauseMessage(bootFailure!!))
            .contains(PairKeyUniqueConstraintValidator.CONSTRAINT_NAME)
            .contains("ALTER TABLE")

        // 4. Positive control: the same database, same frozen schema, once the
        //    documented manual fix is applied — booting succeeds. A failure in
        //    step 3 caused by anything other than the missing constraint would
        //    fail here too.
        addPairKeyConstraint()
        assertThat(jdbcConstraintCount()).isEqualTo(1)
        boot("--spring.jpa.hibernate.ddl-auto=none").close()

        // 5. The constraint is a one-time gate, not a recurring obstacle: the
        //    retained database keeps booting.
        boot("--spring.jpa.hibernate.ddl-auto=none").close()
        assertThat(jdbcConstraintCount()).isEqualTo(1)
    }
}

private tailrec fun rootCause(ex: Throwable): Throwable =
    if (ex.cause == null || ex.cause === ex) ex else rootCause(ex.cause!!)

private fun rootCauseMessage(ex: Throwable): String = rootCause(ex).message.orEmpty()