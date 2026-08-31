package com.example.chat.seed

import java.time.Instant
import java.util.UUID

/**
 * Fixed, deterministic identifiers for the MVP demo directory: users, their
 * pre-created conversations, and — for the seeded Alice&harr;Carol history —
 * the messages' correlation tokens and timestamps.
 *
 * These ids are stable across restarts and environments so the frontend
 * (and tests) can hardcode/reference them directly rather than discovering
 * them dynamically (design.md, "Docker Compose topology": "Schema and
 * fixed-id seed users/conversation are applied idempotently on startup").
 *
 * Do not change these values once the frontend depends on them — treat them
 * as a public contract of the MVP demo environment. Alice, Bob, and
 * `CONVERSATION_ID` predate `add-conversation-creation-presence-inspector`
 * and are unchanged by it; Carol, Dan, Erin, the Alice&harr;Carol
 * conversation, and its seeded messages were added by that change
 * (design.md decision 1, "Seed the directory before anything else") so that
 * conversation creation, rail previews, and presence are all demonstrable —
 * Dan and Erin start with no conversations at all, guaranteeing at least one
 * reachable new-conversation candidate; the Alice&harr;Bob conversation is
 * deliberately left empty (no messages) so the empty-history preview and
 * empty-thread states remain reachable.
 */
object SeedData {
    val ALICE_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    const val ALICE_DISPLAY_NAME: String = "Alice"

    val BOB_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    const val BOB_DISPLAY_NAME: String = "Bob"

    val CONVERSATION_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

    val CAROL_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
    const val CAROL_DISPLAY_NAME: String = "Carol"

    val DAN_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
    const val DAN_DISPLAY_NAME: String = "Dan"

    val ERIN_ID: UUID = UUID.fromString("66666666-6666-6666-6666-666666666666")
    const val ERIN_DISPLAY_NAME: String = "Erin"

    /**
     * The seeded Alice&harr;Carol conversation. Unlike [CONVERSATION_ID]
     * (Alice&harr;Bob, left empty), this one carries three seeded messages
     * so a non-empty preview and a rendered date divider are both reachable.
     */
    val ALICE_CAROL_CONVERSATION_ID: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")

    /**
     * Deterministic `clientMessageId`s and back-dated `createdAt` timestamps
     * for the three seeded Alice&harr;Carol messages. The timestamps
     * deliberately straddle a day boundary (two late on 2024-03-14, one
     * early on 2024-03-15) so the frontend's date divider renders against
     * seeded data (design.md, Open Questions #5).
     */
    val ALICE_CAROL_MESSAGE_1_CLIENT_ID: UUID = UUID.fromString("88888888-8888-8888-8888-888888888881")
    val ALICE_CAROL_MESSAGE_1_CREATED_AT: Instant = Instant.parse("2024-03-14T23:10:00Z")

    val ALICE_CAROL_MESSAGE_2_CLIENT_ID: UUID = UUID.fromString("88888888-8888-8888-8888-888888888882")
    val ALICE_CAROL_MESSAGE_2_CREATED_AT: Instant = Instant.parse("2024-03-14T23:42:00Z")

    val ALICE_CAROL_MESSAGE_3_CLIENT_ID: UUID = UUID.fromString("88888888-8888-8888-8888-888888888883")
    val ALICE_CAROL_MESSAGE_3_CREATED_AT: Instant = Instant.parse("2024-03-15T08:05:00Z")
}
