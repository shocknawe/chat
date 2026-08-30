package com.example.chat.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Runtime-bound view of `app.messaging.*` configuration.
 *
 * [maxContentLength] defaults to [MessagingLimits.MAX_CONTENT_LENGTH] so the
 * validation limit applied by later work packages (`SEND_MESSAGE` content
 * validation) stays consistent with the [com.example.chat.domain.Message]
 * column length by construction, even though the two cannot literally share
 * one Kotlin expression across an annotation boundary.
 */
@ConfigurationProperties(prefix = "app.messaging")
data class MessagingProperties(
    val maxContentLength: Int = MessagingLimits.MAX_CONTENT_LENGTH,
)
