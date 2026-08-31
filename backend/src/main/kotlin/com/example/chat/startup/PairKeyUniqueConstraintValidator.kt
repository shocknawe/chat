package com.example.chat.startup

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Fails fast at boot if the `uq_conversation_pair_key` unique constraint is
 * absent from the `conversation` table (`add-conversation-creation-presence-inspector`
 * tasks 3.1a/3.1b).
 *
 * **Why this has to be an assertion, not an assumption.** Schema evolution in
 * this project is Hibernate's `ddl-auto: update` (application.yml) with no
 * Flyway, and `update` is *not* guaranteed to add a table-level unique
 * constraint to a table that already exists — the column this change adds is
 * reliably added, the constraint is not. Without the constraint everything
 * still *looks* fine: single-threaded creation works, the happy path returns
 * the right DTOs. What silently disappears is the only database-level defence
 * against two conversations for the same participant pair — the concurrent
 * -create race recovery in `ConversationService` catches a
 * `DataIntegrityViolationException` that never comes, so duplicate
 * conversations accumulate with no error anywhere and both get created and
 * both get announced. A missing-invariant application must refuse to start,
 * not limp along (conversations-creation spec, "A missing constraint fails the
 * application, not silently").
 *
 * **Why it runs as an `ApplicationRunner` ordered ahead of `DataSeeder`.**
 * Runners execute after the `EntityManagerFactory` is up, i.e. after Hibernate
 * has already had its one chance to add the constraint — so this check sees
 * exactly what a deploy against a retained volume would see, including the
 * case `ddl-auto: update` failed to repair. Ordering before the seeder matters
 * because the seeder's backfill (`DataSeeder`) writes `pairKey` values onto
 * pre-existing rows; those writes must never land in a table whose uniqueness
 * is not enforced (`DataSeeder` has no order, and unordered runners run last).
 *
 * **Scope note.** The probe queries the Postgres catalogs directly (no
 * `information_schema` indirection): this project's deployed stack and every
 * test in the repo (Testcontainers) are PostgreSQL-only — the datasource has
 * no other dialect to support, and a dialect-portable probe would be strictly
 * more code for a query that never runs anywhere else.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class PairKeyUniqueConstraintValidator(
    private val jdbcTemplate: JdbcTemplate,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(PairKeyUniqueConstraintValidator::class.java)

    /**
     * `t.relname = ?` (rather than `conrelid = ?::regclass`) so an entirely
     * missing `conversation` table yields a count of 0 and the same actionable
     * failure, instead of a `regclass` cast error masking the real problem.
     */
    private fun constraintCount(): Int {
        val count: Integer? = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            WHERE c.conname = ? AND t.relname = ?
            """.trimIndent(),
            Integer::class.java,
            CONSTRAINT_NAME,
            TABLE_NAME,
        )
        return count?.toInt() ?: 0
    }

    override fun run(args: ApplicationArguments) {
        val count = try {
            constraintCount()
        } catch (ex: EmptyResultDataAccessException) {
            // The probe always returns a single COUNT row; this only guards an
            // unanticipated driver result, so treat it as "not found".
            log.warn("Constraint probe returned no row", ex)
            0
        }
        if (count > 0) {
            log.debug("Found {} '{}' constraint(s) on '{}'; pair-uniqueness invariant is enforced", count, CONSTRAINT_NAME, TABLE_NAME)
            return
        }

        throw IllegalStateException(
            "$CONSTRAINT_NAME is missing from the $TABLE_NAME table: the conversation-creation " +
                "race defence (a database-enforced single conversation per participant pair) is inert without it, " +
                "and 'ddl-auto: update' does not reliably add a unique constraint to an existing table. " +
                "Apply the missing constraint to the retained database, e.g. " +
                "ALTER TABLE $TABLE_NAME ADD CONSTRAINT $CONSTRAINT_NAME UNIQUE (pair_key); " +
                "then restart. Refusing to start with an unenforced pair-uniqueness invariant.",
        )
    }

    companion object {
        /** The entity-level constraint declared on [com.example.chat.domain.Conversation]. */
        const val CONSTRAINT_NAME = "uq_conversation_pair_key"

        const val TABLE_NAME = "conversation"
    }
}