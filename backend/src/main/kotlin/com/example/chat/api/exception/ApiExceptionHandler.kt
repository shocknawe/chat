package com.example.chat.api.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Centralizes the REST error-status mapping for this work package's domain
 * exceptions, keeping the 401-vs-403-vs-404 split explicit:
 * - `401 Unauthorized` — no/invalid `X-User-Id` identity; handled entirely by
 *   Spring Security ([com.example.chat.security.RestAuthenticationEntryPoint]),
 *   never reaches this class.
 * - `403 Forbidden` — a valid identity that is not a participant in the
 *   requested conversation ([NotConversationParticipantException]).
 * - `404 Not Found` — the requested conversation does not exist
 *   ([ConversationNotFoundException]).
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ConversationNotFoundException::class)
    fun handleConversationNotFound(ex: ConversationNotFoundException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Conversation not found")
        problem.title = "Conversation Not Found"
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem)
    }

    @ExceptionHandler(NotConversationParticipantException::class)
    fun handleNotParticipant(ex: NotConversationParticipantException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN,
            "You are not a participant of this conversation.",
        )
        problem.title = "Forbidden"
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem)
    }
}
