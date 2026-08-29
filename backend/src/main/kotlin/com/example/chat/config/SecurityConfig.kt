package com.example.chat.config

import com.example.chat.security.RestAuthenticationEntryPoint
import com.example.chat.security.UserIdHeaderAuthenticationFilter
import com.example.chat.security.UserIdentityService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter

/**
 * Real security posture for work package 3 (`user-directory` / `conversations`),
 * replacing the WP1 scaffold's blanket `permitAll`.
 *
 * Demo identity binding (design.md: "Window-scoped demo identity with
 * minimal Spring Security binding"):
 * - `GET /api/users` and actuator health/info stay public.
 * - Every other request must carry an `X-User-Id` header that
 *   [UserIdHeaderAuthenticationFilter] resolves, via the shared
 *   [UserIdentityService], to a seeded user. Missing/malformed/unknown
 *   identities are left unauthenticated, so `authorizeHttpRequests`'s
 *   `authenticated()` rule rejects them with `401` via
 *   [RestAuthenticationEntryPoint] — never `403`.
 * - `403` is reserved for a *valid* identity that isn't a participant in the
 *   requested conversation; that check is not expressible as a static
 *   `authorizeHttpRequests` rule (it depends on the path variable and a DB
 *   lookup), so it is enforced in the service/controller layer instead (see
 *   `ConversationService`) and mapped to `403` by the API exception handler.
 *
 * Stateless: no session/cookie is created (`SessionCreationPolicy.STATELESS`)
 * — every request re-authenticates from its `X-User-Id` header, consistent
 * with CSRF being inapplicable to this session-less API.
 *
 * A later work package (WP4) adds a WebSocket handshake interceptor that
 * reuses the same [UserIdentityService] for the `?userId=` query parameter;
 * extend this class rather than scattering security config elsewhere.
 */
@Configuration
class SecurityConfig(
    private val userIdentityService: UserIdentityService,
    private val restAuthenticationEntryPoint: RestAuthenticationEntryPoint,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/actuator/health", permitAll)
                authorize("/actuator/info", permitAll)
                authorize(HttpMethod.GET, "/api/users", permitAll)
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                authenticationEntryPoint = restAuthenticationEntryPoint
            }
            addFilterBefore<AnonymousAuthenticationFilter>(
                UserIdHeaderAuthenticationFilter(userIdentityService),
            )
        }
        return http.build()
    }
}
