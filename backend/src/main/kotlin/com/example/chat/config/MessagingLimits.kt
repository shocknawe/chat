package com.example.chat.config

/**
 * Compile-time constants shared between the persistence layer and
 * configuration binding for messaging limits.
 *
 * [Message.content][com.example.chat.domain.Message.content] mirrors
 * [MAX_CONTENT_LENGTH] as its column length (design.md: "mirrored by the
 * column definition, so an oversized payload cannot reach persistence"),
 * while [MessagingProperties] exposes the same number to runtime validation
 * (added in a later work package) via `app.messaging.max-content-length`.
 *
 * JPA column-length annotation attributes must be compile-time constants, so
 * this value cannot be read directly from `application.yml` at the
 * annotation site. [com.example.chat.config.MessagingPropertiesConsistencyTest]
 * (test source set) asserts the configured property equals this constant so
 * the two never silently drift apart.
 */
object MessagingLimits {
    const val MAX_CONTENT_LENGTH: Int = 4000
}
