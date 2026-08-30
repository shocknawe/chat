package com.example.chat.api

import com.example.chat.api.dto.ConversationDto
import com.example.chat.api.dto.CreateConversationRequest
import com.example.chat.api.dto.MessageDto
import com.example.chat.security.AuthenticatedUser
import com.example.chat.service.ConversationService
import com.example.chat.service.CreateResult
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * conversations capability. Every endpoint here requires an established
 * demo identity (`X-User-Id`, enforced by [com.example.chat.config.SecurityConfig]
 * before this controller is reached) — `@AuthenticationPrincipal` resolves
 * directly to the [AuthenticatedUser] set by
 * [com.example.chat.security.UserIdHeaderAuthenticationFilter]. That is why
 * the creation endpoint's "unknown caller identity" case
 * ([conversations-creation spec](openspec/changes/add-conversation-creation-presence-inspector/specs/conversation-creation/spec.md),
 * "Unknown caller identity") is `401` here without this file containing any
 * 401 logic: a request whose `X-User-Id` is missing, malformed, or unknown
 * never gets past the filter chain.
 */
@RestController
@RequestMapping("/api/conversations")
class ConversationController(
    private val conversationService: ConversationService,
) {

    /** Conversations spec: "Conversations are scoped to the participating user". */
    @GetMapping
    fun listConversations(@AuthenticationPrincipal currentUser: AuthenticatedUser): List<ConversationDto> =
        conversationService.findConversationsForUser(currentUser.id)

    /**
     * `POST /api/conversations` — create (or reuse) a 1:1 conversation with
     * [CreateConversationRequest.participantId]; the caller is implicit in
     * [AuthenticatedUser]. Status split per `docs/openapi.yaml` and design.md
     * decision 3 ("idempotent, never 409"):
     * - [CreateResult.Created] → `201`
     * - [CreateResult.Existing] → `200`
     * - `400` / `404` rejections are thrown as domain exceptions from the
     *   service and mapped by
     *   [com.example.chat.api.exception.ApiExceptionHandler]; `401` is the
     *   filter chain's, never this controller's.
     *
     * When [CreateResult.Created] is returned, the
     * `CONVERSATION_CREATED` event has *already* been pushed to the other
     * participant's connections (after the creating transaction committed —
     * see [ConversationWriter]); the response and the event are therefore
     * always consistent with durable state.
     */
    @PostMapping
    fun createConversation(
        @AuthenticationPrincipal currentUser: AuthenticatedUser,
        @Valid @RequestBody request: CreateConversationRequest,
    ): ResponseEntity<ConversationDto> =
        when (val result = conversationService.createConversation(
            callerId = currentUser.id,
            participantId = request.participantId,
        )) {
            is CreateResult.Created -> ResponseEntity.status(HttpStatus.CREATED).body(result.conversation)
            is CreateResult.Existing -> ResponseEntity.ok(result.conversation)
        }

    /**
     * Conversations spec: "Message history is authorized by participation"
     * and "History is returned in deterministic chronological order".
     * Non-existent conversation → `404`; valid identity but non-participant
     * → `403` (both mapped by [com.example.chat.api.exception.ApiExceptionHandler]).
     */
    @GetMapping("/{id}/messages")
    fun getMessageHistory(
        @PathVariable id: UUID,
        @AuthenticationPrincipal currentUser: AuthenticatedUser,
    ): List<MessageDto> =
        conversationService.getMessageHistory(conversationId = id, userId = currentUser.id)
}
