package com.example.chat.api.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
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
 *   ([ConversationNotFoundException]), or a referenced directory user does not
 *   exist ([ParticipantNotFoundException], `POST /api/conversations` — a
 *   distinct 404 whose semantics are documented separately in
 *   `docs/openapi.yaml`).
 * - `400 Bad Request` — a malformed creation request
 *   ([CallerIsParticipantException], or a body that does not deserialize into
 *   [com.example.chat.api.dto.CreateConversationRequest]).
 *
 * Spring's *default* handling of [MethodArgumentNotValidException] and
 * [HttpMessageNotReadableException] would return the servlet error body
 * (`{"timestamp", "status", "error", "path"}`) rather than the RFC 7807
 * `application/problem+json` shape that `docs/openapi.yaml` promises for every
 * `4xx` on this endpoint, so both are mapped here explicitly.
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

    /** `POST /api/conversations` named the caller as the participant → `400` (conversations-creation spec, "Participant is the caller"). */
    @ExceptionHandler(CallerIsParticipantException::class)
    fun handleCallerIsParticipant(ex: CallerIsParticipantException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Invalid participant")
        problem.title = "Bad Request"
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem)
    }

    /** `POST /api/conversations` named a non-directory user → `404` (conversations-creation spec, "Unknown participant"). */
    @ExceptionHandler(ParticipantNotFoundException::class)
    fun handleParticipantNotFound(ex: ParticipantNotFoundException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.message ?: "participantId does not name a directory user.",
        )
        problem.title = "User Not Found"
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem)
    }

    /**
     * A Bean Validation failure on a request payload (e.g. `POST
     * /api/conversations` with a body missing `participantId`) → `400`, in the
     * documented ProblemDetail shape. The detail is the *constraint message*,
     * not the exception's own message (which is a Spring-internal sentence
     * about "validation failed for argument"), so the response states what was
     * wrong with the payload in words.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailure(ex: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> {
        val detail = ex.bindingResult.fieldErrors
            .joinToString("; ") { fieldError -> fieldError.defaultMessage ?: "${fieldError.field} is invalid" }
            .ifEmpty { "Request payload failed validation" }
        return badRequest(detail)
    }

    /**
     * A request body that could not be read at all (not valid JSON, or — for
     * [com.example.chat.api.dto.CreateConversationRequest] — a `participantId`
     * that is missing, explicitly `null`, or not a well-formed UUID, which
     * Jackson/Kotlin binding rejects before any controller code runs) → `400`.
     * Only the parse failure is stated, never the parse path or offsets from
     * [ex], which are server-internal detail.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ResponseEntity<ProblemDetail> =
        badRequest("Request body is missing, malformed, or not valid JSON")

    private fun badRequest(detail: String): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail)
        problem.title = "Bad Request"
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem)
    }
}
