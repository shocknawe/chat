package com.example.chat.ws.protocol

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The result of attempting to parse raw inbound WebSocket text into an
 * [InboundCommand].
 */
sealed interface ParsedCommand {
    data class Valid(val command: InboundCommand) : ParsedCommand
    data class Invalid(val reason: String) : ParsedCommand
}

/**
 * Parses raw inbound WebSocket text frames into [InboundCommand]s.
 *
 * Reuses the Spring-managed [ObjectMapper] bean (rather than constructing a
 * private one) so this parser inherits the application's Jackson
 * configuration wholesale -- notably `fail-on-unknown-properties: false`
 * (`application.yml`), which is what makes an extraneous client-supplied
 * field (e.g. a spoofed `senderId`) silently ignored rather than rejected,
 * and the Kotlin + `java.time` modules already registered for [MessageDto]
 * over REST.
 *
 * Any failure to parse -- malformed JSON, a missing/unknown `type`
 * discriminator, or a well-typed command missing a required field -- is
 * reported as [ParsedCommand.Invalid] rather than propagating an exception,
 * so a single bad frame never terminates the WebSocket session or affects
 * any other connection (design.md / spec: "Protocol errors are isolated to
 * the offending command").
 */
@Component
class ProtocolParser(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(ProtocolParser::class.java)

    fun parse(payload: String): ParsedCommand =
        try {
            val command = objectMapper.readValue(payload, InboundCommand::class.java)
            ParsedCommand.Valid(command)
        } catch (ex: Exception) {
            // Deliberately broad: any Jackson parse/mapping failure (malformed
            // JSON, unknown/missing `type`, missing required field, wrong
            // field type, ...) must degrade to a protocol ERROR for this
            // command alone, never propagate and risk taking the connection
            // down.
            log.debug("Failed to parse inbound WebSocket command: {}", ex.message)
            ParsedCommand.Invalid(describe(ex))
        }

    private fun describe(ex: Exception): String =
        "Malformed or unsupported command" + (ex.message?.let { ": $it" } ?: "")
}
