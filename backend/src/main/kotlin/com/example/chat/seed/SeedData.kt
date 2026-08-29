package com.example.chat.seed

import java.util.UUID

/**
 * Fixed, deterministic identifiers for the two MVP demo users and their
 * pre-created conversation.
 *
 * These ids are stable across restarts and environments so the frontend
 * (and tests) can hardcode/reference them directly rather than discovering
 * them dynamically (design.md, "Docker Compose topology": "Schema and
 * fixed-id seed users/conversation are applied idempotently on startup").
 *
 * Do not change these values once the frontend depends on them — treat them
 * as a public contract of the MVP demo environment.
 */
object SeedData {
    val ALICE_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    const val ALICE_DISPLAY_NAME: String = "Alice"

    val BOB_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    const val BOB_DISPLAY_NAME: String = "Bob"

    val CONVERSATION_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
}
