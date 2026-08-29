package com.example.chat.ws

import com.example.chat.security.UserIdentityService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.util.UriComponentsBuilder

/**
 * Validates the WebSocket handshake `?userId=` query parameter against the
 * seeded MVP users, reusing the same [UserIdentityService] as the REST
 * `X-User-Id` header filter (design.md: "Window-scoped demo identity with
 * minimal Spring Security binding" -- "the WebSocket handshake carries the
 * same non-secret id as `?userId=...`; a handshake interceptor performs the
 * same validation and assigns it to the connection ... attributes").
 *
 * A missing or unknown user id rejects the handshake outright (`false`),
 * which Spring WebSocket turns into the HTTP response status already set
 * here rather than upgrading the connection at all -- the spec's "Unknown
 * user cannot establish a connection" scenario.
 */
@Component
class UserIdHandshakeInterceptor(
    private val userIdentityService: UserIdentityService,
) : HandshakeInterceptor {

    private val log = LoggerFactory.getLogger(UserIdHandshakeInterceptor::class.java)

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val rawUserId = UriComponentsBuilder.fromUri(request.uri)
            .build()
            .queryParams["userId"]
            ?.firstOrNull()

        val user = userIdentityService.resolve(rawUserId)
        if (user == null) {
            log.debug("Rejecting WebSocket handshake for unknown/missing userId={}", rawUserId)
            response.setStatusCode(HttpStatus.UNAUTHORIZED)
            return false
        }

        attributes[WebSocketSessionAttributes.USER] = user
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) {
        // No-op: nothing to clean up if the upgrade itself failed after a
        // successful beforeHandshake (no session was ever registered yet).
    }
}
