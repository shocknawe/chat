package com.example.chat.ws

import com.example.chat.security.AuthenticatedUser
import com.example.chat.ws.protocol.OutboundEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of active WebSocket connections, keyed by userId
 * (design.md: "In-memory ConnectionRegistry keyed by userId -> set of
 * sessions").
 *
 * Tracks each user's connections independently in a
 * `ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>` (spec: "Connection
 * registry tracks active sessions per user" -- multiple sessions per user are
 * tracked independently), plus a session-id index used to resolve the
 * registered (decorated) session instance for a given raw
 * `WebSocketSession.id` (`WebSocketConnectionHandler` needs this because
 * Spring passes the *undecorated* session into `handleTextMessage`, not the
 * `ConcurrentWebSocketSessionDecorator`-wrapped instance registered here).
 *
 * Every session registered here is expected to already be wrapped in a
 * `ConcurrentWebSocketSessionDecorator` by the caller (see
 * [WebSocketConnectionHandler]), so concurrent sends to the same session from
 * different threads (e.g. two other users' messages fanning out to the same
 * recipient session at once) are serialized by that decorator rather than
 * racing on the raw session -- Spring WebSocket sessions do not support
 * concurrent `sendMessage` calls.
 *
 * [send] and [sendToUser] isolate a single session's failure: a broken pipe,
 * timeout, or buffer-limit exceeded on one session evicts only that session
 * and never propagates to the caller, so one stale/slow recipient can never
 * abort delivery to other sessions or affect the already-committed message
 * (design.md: "Fan-out to a participant's stale/closing session").
 */
@Component
class ConnectionRegistry(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(ConnectionRegistry::class.java)

    private val sessionsByUser = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()
    private val sessionsById = ConcurrentHashMap<String, WebSocketSession>()

    /** Registers [session] (expected to already be a `ConcurrentWebSocketSessionDecorator`) as active for [userId]. */
    fun register(userId: UUID, session: WebSocketSession) {
        sessionsById[session.id] = session
        sessionsByUser.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    /** Removes [session] from both the per-user and per-session-id indexes. */
    fun unregister(userId: UUID, session: WebSocketSession) {
        sessionsById.remove(session.id)
        sessionsByUser.computeIfPresent(userId) { _, sessions ->
            sessions.remove(session)
            sessions.ifEmpty { null }
        }
    }

    /** Resolves the registered (decorated) session instance for a raw session id, if still registered. */
    fun sessionById(sessionId: String): WebSocketSession? = sessionsById[sessionId]

    /**
     * Sends [event] to [session] only, isolating any failure to that single
     * session: a closed/broken session is evicted from the registry and the
     * failure is swallowed (logged), never rethrown.
     */
    fun send(session: WebSocketSession, event: OutboundEvent) {
        if (!session.isOpen) {
            evict(session)
            return
        }
        try {
            val payload = objectMapper.writeValueAsString(event)
            session.sendMessage(TextMessage(payload))
        } catch (ex: Exception) {
            log.warn(
                "Failed to deliver {} to WebSocket session {}; evicting session",
                event::class.simpleName,
                session.id,
                ex,
            )
            evict(session)
        }
    }

    /**
     * Sends [event] to every currently active session of [userId], optionally
     * skipping [excluding] (the originating session, so it never receives
     * both a `MESSAGE_ACK` and a `NEW_MESSAGE` for the same command). A
     * snapshot of the session set is iterated so that one session's
     * mid-iteration eviction (on send failure) cannot cause a
     * `ConcurrentModificationException` or skip a sibling session.
     */
    fun sendToUser(userId: UUID, event: OutboundEvent, excluding: WebSocketSession? = null) {
        val sessions = sessionsByUser[userId] ?: return
        sessions.toList().forEach { session ->
            if (excluding != null && session.id == excluding.id) return@forEach
            send(session, event)
        }
    }

    private fun evict(session: WebSocketSession) {
        sessionsById.remove(session.id)
        val user = runCatching {
            session.attributes[WebSocketSessionAttributes.USER] as? AuthenticatedUser
        }.getOrNull()
        if (user != null) {
            sessionsByUser.computeIfPresent(user.id) { _, sessions ->
                sessions.remove(session)
                sessions.ifEmpty { null }
            }
        }
        runCatching { session.close(CloseStatus.SERVER_ERROR) }
    }
}
