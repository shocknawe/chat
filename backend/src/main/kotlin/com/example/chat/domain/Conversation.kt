package com.example.chat.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID

/**
 * A conversation between two or more [AppUser] participants.
 *
 * For the MVP, conversations created by `POST /api/conversations` are always
 * 1-to-1 (see [pairKey]); the many-to-many participants join still models the
 * general case and enforces referential integrity between a conversation and
 * its participants via foreign keys on the join table (design.md: "Foreign
 * keys enforce referential integrity between message<->conversation and
 * conversation<->participants").
 *
 * [pairKey] carries the single-per-pair uniqueness invariant for 1:1
 * conversations (add-conversation-creation-presence-inspector design.md
 * decision 2). It is deliberately a *table-level* unique constraint rather
 * than a bare `@Column(unique = true)`: Hibernate maps a column-level
 * `unique = true` onto a DDL fragment on the column definition, which behaves
 * less predictably under `ddl-auto: update` against an already-existing table,
 * and — more importantly — a named, table-level constraint is exactly the
 * artifact the boot-time presence check
 * ([com.example.chat.startup.PairKeyUniqueConstraintValidator]) looks for. The
 * proven precedent is `Message`'s `uq_message_sender_client_message_id`.
 */
@Entity
@Table(
    name = "conversation",
    uniqueConstraints = [
        // "Uniqueness is database-enforced so that concurrent duplicate
        // creation is impossible even when both requests pass the
        // application-level existence check" (conversations-creation spec,
        // "Concurrent creation persists exactly one conversation"). The
        // constraint is the final arbiter; the service-level pre-check can
        // always race and miss.
        UniqueConstraint(
            name = "uq_conversation_pair_key",
            columnNames = ["pair_key"],
        ),
    ],
)
class Conversation(
    @Id
    val id: UUID,

    /**
     * The participant pair, written as the two member UUIDs in sorted
     * (lexicographic) order separated by a colon — `"<low>:<high>"`, e.g.
     * `"1111…-1111…:2222…-2222…"` for the seeded Alice↔Bob conversation. Sort
     * the two ids before joining (never trust the argument order); the
     * canonical form is computed by [pairKeyFor] and every writer of this
     * column must go through it, so both directions of an asymmetric call
     * ("start a conversation with Alice" vs. "start a conversation with Bob")
     * produce the same key.
     *
     * Nullable twice over, on purpose (design.md decision 2):
     * - A `NOT NULL` addition fails to boot under `ddl-auto: update` against a
     *   database already holding conversations; backfill
     *   ([com.example.chat.seed.DataSeeder]) fills pre-existing rows instead.
     * - Postgres permits any number of `NULL`s under a unique constraint, so
     *   group conversations (which have no pair key) remain representable
     *   without a schema change.
     *
     * The "a 1:1 conversation always carries a non-null pair key" invariant is
     * enforced in the service layer ([com.example.chat.service.ConversationWriter]),
     * not the schema: the column must stay nullable to keep both of the above
     * properties.
     */
    @Column(name = "pair_key", length = PAIR_KEY_MAX_LENGTH)
    var pairKey: String? = null,
) {
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "conversation_participant",
        joinColumns = [
            JoinColumn(
                name = "conversation_id",
                nullable = false,
                foreignKey = ForeignKey(name = "fk_conversation_participant_conversation"),
            ),
        ],
        inverseJoinColumns = [
            JoinColumn(
                name = "user_id",
                nullable = false,
                foreignKey = ForeignKey(name = "fk_conversation_participant_user"),
            ),
        ],
    )
    val participants: MutableSet<AppUser> = mutableSetOf()

    companion object {
        /** Two UUIDs (36 chars each) plus the one-colon separator. */
        const val PAIR_KEY_MAX_LENGTH = 73

        /**
         * The canonical pair key for the unordered participant pair
         * ({@code first}, {@code second}): the two UUID strings in
         * lexicographic order, joined by a colon. Argument order is
         * irrelevant — `{@code pairKeyFor(a, b) == pairKeyFor(b, a)}` — which
         * is what makes the value usable as an idempotency key for POST
         * `/api/conversations`, where either party may be the caller.
         *
         * String (not [UUID] object) ordering is deliberate: every component
         * of this value is a string, and the sort only has to be
         * deterministic and total over the two members of the pair, which
         * string ordering is.
         */
        fun pairKeyFor(first: UUID, second: UUID): String {
            val a = first.toString()
            val b = second.toString()
            return if (a < b) "$a:$b" else "$b:$a"
        }
    }
}