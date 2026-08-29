package com.example.chat.domain

import com.example.chat.config.MessagingLimits
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * A persisted, authoritative chat message.
 *
 * Identity and ordering are server-owned:
 * - [id] and [createdAt] are assigned by the server (default-generated at
 *   construction time so every code path that builds a `Message` gets a
 *   real value), never accepted from client input. The transactional
 *   `MessageService.send` pipeline added in a later work package
 *   (design.md: "Commit before acknowledgement") is expected to construct
 *   this entity explicitly, capturing the same `id`/`createdAt` it returns
 *   in the acknowledgement.
 * - [clientMessageId] is stored only as a client-supplied correlation and
 *   deduplication token (design.md: "Idempotency for duplicate
 *   SEND_MESSAGE") — it is never treated as the authoritative id or used
 *   for ordering.
 * - History ordering is `(createdAt ASC, id ASC)`: the UUID is a stable
 *   deterministic tiebreaker only, not assumed chronological itself. See
 *   [com.example.chat.repository.MessageRepository].
 *
 * [content] is bounded by [MessagingLimits.MAX_CONTENT_LENGTH], mirrored
 * here as the column length so an oversized payload cannot reach
 * persistence even if application-level validation (added in a later work
 * package) is bypassed.
 */
@Entity
@Table(
    name = "message",
    uniqueConstraints = [
        // Database-enforced idempotency key (design.md: "Idempotency for
        // duplicate SEND_MESSAGE" — "The database constraint is the final
        // arbiter for concurrent duplicates, not a check-then-insert
        // performed only in memory").
        UniqueConstraint(
            name = "uq_message_sender_client_message_id",
            columnNames = ["sender_id", "client_message_id"],
        ),
    ],
    indexes = [
        // Supports the deterministic history query ordering.
        Index(name = "idx_message_conversation_created_at_id", columnList = "conversation_id, created_at, id"),
    ],
)
class Message(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "conversation_id",
        nullable = false,
        foreignKey = ForeignKey(name = "fk_message_conversation"),
    )
    val conversation: Conversation,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "sender_id",
        nullable = false,
        foreignKey = ForeignKey(name = "fk_message_sender"),
    )
    val sender: AppUser,

    @Column(name = "client_message_id", nullable = false)
    val clientMessageId: UUID,

    @Column(nullable = false, length = MessagingLimits.MAX_CONTENT_LENGTH)
    val content: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
