package com.example.chat.domain

import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import java.util.UUID

/**
 * A conversation between two or more [AppUser] participants.
 *
 * For the MVP, only one pre-seeded 1-to-1 conversation exists (see
 * `com.example.chat.seed.SeedData`); the many-to-many participants join
 * still models the general case and enforces referential integrity between
 * a conversation and its participants via foreign keys on the join table
 * (design.md: "Foreign keys enforce referential integrity between
 * message<->conversation and conversation<->participants").
 */
@Entity
@Table(name = "conversation")
class Conversation(
    @Id
    val id: UUID,
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
}
