package com.example.chat.ws

/**
 * The `WebSocketSession.attributes` key under which the connection's
 * validated [com.example.chat.security.AuthenticatedUser] is bound.
 *
 * Populated once, during the handshake, by [UserIdHandshakeInterceptor]
 * (design.md: "Window-scoped demo identity"); read afterwards by
 * [WebSocketConnectionHandler] (to register/unregister the connection) and
 * [ConnectionRegistry] (to resolve a stale session's owner on eviction).
 * Handshake attributes are copied verbatim into the session's own attribute
 * map by Spring's WebSocket support, so this key is stable for the lifetime
 * of the connection.
 */
internal object WebSocketSessionAttributes {
    const val USER = "com.example.chat.ws.AUTHENTICATED_USER"
}
