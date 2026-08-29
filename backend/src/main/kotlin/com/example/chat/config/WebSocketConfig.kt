package com.example.chat.config

import com.example.chat.ws.UserIdHandshakeInterceptor
import com.example.chat.ws.WebSocketConnectionHandler
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * Registers the application-owned realtime WebSocket endpoint at `/ws`
 * (design.md: raw `TextWebSocketHandler`, deliberately no STOMP/broker/
 * Spring Messaging).
 *
 * [UserIdHandshakeInterceptor] performs identity validation during the HTTP
 * upgrade itself; `setAllowedOrigins("*")` is the local single-origin MVP
 * demo's equivalent of the REST layer's permissive dev posture -- the
 * browser's native WebSocket API does not enforce CORS/same-origin the way
 * `fetch`/XHR does, so this only affects whether *this server* accepts the
 * `Origin` header it's sent; there is no separate production deployment for
 * this take-home to harden against.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val webSocketConnectionHandler: WebSocketConnectionHandler,
    private val userIdHandshakeInterceptor: UserIdHandshakeInterceptor,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(webSocketConnectionHandler, "/ws")
            .addInterceptors(userIdHandshakeInterceptor)
            .setAllowedOrigins("*")
    }
}
