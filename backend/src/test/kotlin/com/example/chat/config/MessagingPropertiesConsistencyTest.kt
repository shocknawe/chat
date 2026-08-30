package com.example.chat.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/**
 * Proves `application.yml`'s `app.messaging.max-content-length` and the
 * [Message][com.example.chat.domain.Message.content] column length constant
 * ([MessagingLimits.MAX_CONTENT_LENGTH]) cannot silently drift apart
 * (design.md: "mirrored by the column definition, so an oversized payload
 * cannot reach persistence"). No database required — this loads only the
 * configuration property source.
 */
class MessagingPropertiesConsistencyTest {

    @Configuration
    @EnableConfigurationProperties(MessagingProperties::class)
    class TestConfig

    private val contextRunner = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withUserConfiguration(TestConfig::class.java)

    @Test
    fun `configured max-content-length matches the Message entity column length constant`() {
        contextRunner.run { context ->
            val properties = context.getBean(MessagingProperties::class.java)
            assertThat(properties.maxContentLength).isEqualTo(MessagingLimits.MAX_CONTENT_LENGTH)
        }
    }
}
