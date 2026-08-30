package com.example.chat.security

import com.example.chat.api.ConversationController
import com.example.chat.api.exception.ApiExceptionHandler
import com.example.chat.config.SecurityConfig
import com.example.chat.domain.AppUser
import com.example.chat.repository.AppUserRepository
import com.example.chat.service.ConversationService
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.Optional
import java.util.UUID

/**
 * WP3.2 — proves the `X-User-Id` demo identity binding at the Spring
 * Security filter-chain boundary using a real (not mocked) [SecurityConfig] /
 * [UserIdHeaderAuthenticationFilter] / [UserIdentityService] /
 * [RestAuthenticationEntryPoint] wired against a sliced MVC context.
 *
 * [ConversationService] is mocked because this test targets authentication
 * (401), not the controller's business behavior (that is covered by
 * [com.example.chat.api.ConversationApiIntegrationTest]).
 */
@WebMvcTest(controllers = [ConversationController::class])
@Import(
    SecurityConfig::class,
    UserIdentityService::class,
    RestAuthenticationEntryPoint::class,
    ApiExceptionHandler::class,
)
class ConversationControllerSecuritySliceTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var appUserRepository: AppUserRepository

    @MockitoBean
    lateinit var conversationService: ConversationService

    @Test
    fun `missing X-User-Id header is rejected with 401`() {
        mockMvc.get("/api/conversations")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `unknown X-User-Id is rejected with 401`() {
        val unknownId = UUID.randomUUID()
        given(appUserRepository.findById(unknownId)).willReturn(Optional.empty())

        mockMvc.get("/api/conversations") {
            header("X-User-Id", unknownId.toString())
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `malformed X-User-Id is rejected with 401`() {
        mockMvc.get("/api/conversations") {
            header("X-User-Id", "not-a-uuid")
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `valid seeded X-User-Id is authenticated and reaches the controller`() {
        val aliceId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        given(appUserRepository.findById(aliceId))
            .willReturn(Optional.of(AppUser(id = aliceId, displayName = "Alice")))
        given(conversationService.findConversationsForUser(aliceId)).willReturn(emptyList())

        mockMvc.get("/api/conversations") {
            header("X-User-Id", aliceId.toString())
        }.andExpect { status { isOk() } }
    }
}
