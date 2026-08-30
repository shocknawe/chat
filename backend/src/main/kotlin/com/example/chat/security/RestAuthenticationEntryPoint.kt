package com.example.chat.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

/**
 * Writes a uniform `401 Unauthorized` [ProblemDetail] response whenever an
 * unauthenticated request reaches an endpoint that requires an established
 * demo identity — i.e. the `X-User-Id` header was missing, malformed, or did
 * not match a seeded user (see [UserIdHeaderAuthenticationFilter]).
 *
 * This is intentionally distinct from `403 Forbidden`, which is reserved for
 * a *valid* identity that simply isn't a participant in the requested
 * conversation (handled separately by the service/controller layer, not by
 * Spring Security's authentication mechanism).
 */
@Component
class RestAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            "Missing or unknown X-User-Id identity.",
        )
        problem.title = "Unauthenticated"
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        objectMapper.writeValue(response.writer, problem)
    }
}
