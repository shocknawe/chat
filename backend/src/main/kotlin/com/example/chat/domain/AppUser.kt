package com.example.chat.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * An MVP demo user.
 *
 * Table name is `app_user` (not `user`), which is a reserved identifier in
 * PostgreSQL — see design.md, "Data model and ordering".
 *
 * For the MVP, ids are fixed/seeded (see `com.example.chat.seed.SeedData`)
 * rather than generated per-request, so no `@GeneratedValue` strategy is
 * used; the id is always supplied by the caller.
 */
@Entity
@Table(name = "app_user")
class AppUser(
    @Id
    val id: UUID,

    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,
)
