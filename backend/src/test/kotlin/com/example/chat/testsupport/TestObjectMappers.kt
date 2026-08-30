package com.example.chat.testsupport

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule

/**
 * Single source of truth for the hand-built [ObjectMapper] used by pure unit
 * tests that need Jackson (de)serialization behavior without a Spring
 * context (no `@JsonTest`/`@SpringBootTest`).
 *
 * Mirrors every `spring.jackson.*` setting in `application.yml` so these
 * tests observe exactly the same wire shape the production, Spring-managed
 * `ObjectMapper` bean produces -- a mismatch here (e.g. missing
 * `default-property-inclusion: non_null`) previously let a test assert
 * behavior ("omits a null field") that didn't actually hold against the
 * mapper it ran with:
 *
 * - Kotlin module + `findAndAddModules()` (module auto-discovery, same as
 *   Spring Boot's `Jackson2ObjectMapperBuilder`).
 * - `default-property-inclusion: non_null` -> [JsonInclude.Include.NON_NULL].
 * - `deserialization.fail-on-unknown-properties: false`.
 * - `serialization.write-dates-as-timestamps: false`.
 */
object TestObjectMappers {

    /** A fresh [ObjectMapper] instance configured to match the production Jackson bean. */
    fun create(): ObjectMapper =
        JsonMapper.builder()
            .addModule(kotlinModule())
            .findAndAddModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build()
}
