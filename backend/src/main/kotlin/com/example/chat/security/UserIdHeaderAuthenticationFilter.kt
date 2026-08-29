package com.example.chat.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Demo identity binding filter (design.md: "Window-scoped demo identity with
 * minimal Spring Security binding").
 *
 * Reads the `X-User-Id` header on every request and resolves it via the
 * shared [UserIdentityService]. Only when it resolves to a known seeded user
 * does this filter populate the request's
 * [org.springframework.security.core.context.SecurityContext] with an
 * authenticated principal ([AuthenticatedUser]).
 *
 * Missing, malformed, or unknown identities are left unauthenticated
 * (anonymous) rather than rejected here directly — [SecurityConfig] requires
 * `authenticated()` on every non-public endpoint, so Spring Security's own
 * authorization filter uniformly turns that anonymous state into a `401`
 * via [RestAuthenticationEntryPoint]. This keeps the 401-vs-403 split
 * centralized in one place: 401 means no/invalid identity (this filter),
 * 403 means a valid identity that is not a conversation participant
 * (checked later, in the service/controller layer, once a real
 * [AuthenticatedUser] is already established).
 */
class UserIdHeaderAuthenticationFilter(
    private val userIdentityService: UserIdentityService,
) : OncePerRequestFilter() {

    companion object {
        const val HEADER_NAME = "X-User-Id"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HEADER_NAME)
        val user = userIdentityService.resolve(header)
        if (user != null) {
            val authentication = UsernamePasswordAuthenticationToken(user, null, emptyList())
            SecurityContextHolder.getContext().authentication = authentication
        }
        filterChain.doFilter(request, response)
    }
}
