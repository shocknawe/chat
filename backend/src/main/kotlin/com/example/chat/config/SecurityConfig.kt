package com.example.chat.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain

/**
 * Scaffold security configuration (work package 1).
 *
 * `spring-boot-starter-security` is on the classpath so Spring Boot's default
 * auto-config would otherwise secure *every* request behind HTTP Basic with a
 * generated password — including `/actuator/health`, which the docker-compose
 * `backend` healthcheck depends on. This bean replaces that default with the
 * minimum needed for WP1: actuator health/info are open, everything else
 * currently permits all too since no real endpoints exist yet.
 *
 * This is intentionally NOT the final security posture. Work package 3
 * (`user-directory` / `conversations`) replaces this with the real
 * `X-User-Id` validation filter described in design.md, and later work
 * packages add the WebSocket handshake interceptor. Extend this class then —
 * do not scatter security config elsewhere.
 */
@Configuration
class SecurityConfig {

	@Bean
	fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
		http {
			// Stateless API scaffold: no browser session/cookie auth, so CSRF
			// protection (which defends session-based state changes) is not
			// applicable here. Revisit if session-based auth is introduced.
			csrf { disable() }
			authorizeHttpRequests {
				authorize("/actuator/health", permitAll)
				authorize("/actuator/info", permitAll)
				// TODO(WP3): replace with real X-User-Id authorization rules
				// once REST endpoints exist. Left open for WP1 scaffold only.
				authorize(anyRequest, permitAll)
			}
		}
		return http.build()
	}
}
