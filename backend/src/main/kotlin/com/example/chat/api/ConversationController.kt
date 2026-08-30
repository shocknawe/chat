package com.example.chat.api

import com.example.chat.api.dto.ConversationDto
import com.example.chat.api.dto.MessageDto
import com.example.chat.security.AuthenticatedUser
import com.example.chat.service.ConversationService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * conversations capability. Every endpoint here requires an established
 * demo identity (`X-User-Id`, enforced by [com.example.chat.config.SecurityConfig]
 * before this controller is reached) — `@AuthenticationPrincipal` resolves
 * directly to the [AuthenticatedUser] set by
 * [com.example.chat.security.UserIdHeaderAuthenticationFilter].
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
