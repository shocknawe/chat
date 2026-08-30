package com.example.chat.ws

import com.example.chat.ws.protocol.OutboundEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
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
 * tracked independently), plus two session-id indexes:
 *
 * - [sessionsById] resolves the registered (decorated) session instance for a
 *   raw `WebSocketSession.id` (`WebSocketConnectionHandler` needs this because
 *   Spring passes the *undecorated* session into `handleTextMessage`, not the
 *   `ConcurrentWebSocketSessionDecorator`-wrapped instance registered here).
 * - [ownerBySessionId] resolves the owning user for a session id. Presence
 *   removal ([unregister]/[evict]) resolves the owner from *this registry
 *   index*, deliberately not from `session.attributes`
 *   (add-conversation-creation-presence-inspector task 5.1b).
 *
 * The two indexes and the per-user set are three separate maps, so they can
 * diverge transiently under a racing register/removal — divergence is
 * tolerated in *both* directions and always self-corrects rather than
 * stranding state:
 *
 * - a session in [sessionsByUser] but absent from the indexes is reachable by
 *   [removeFully]'s scan fallback (and a register racing that removal
 *   re-verifies residency before publishing anything, see [register]);
 * - a session in the indexes but absent from [sessionsByUser] is dead weight
 *   only ([sendToUser] iterates the per-user set, never the indexes) and
 *   disappears on the first failed send or eviction — [register] rolls its
 *   own index writes back when it loses exactly that race.
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
 *
 * ## Presence transitions are detected inside the `compute*` lambda, dispatched after it
 *
 * All three mutation paths (`register`, `unregister`, `evict`) funnel through
 * `sessionsByUser.compute*`, so the empty<->non-empty edge for a user is
 * observable in exactly one place (task 5.1, design.md decision 4). The
 * lambda captures the before/after emptiness into a local and does *nothing*
 * else: no repository call, no `send`, no `sendToUser`. The lambda must return
 * the updated set (its remapping contract), so the edge travels out through a
 * local: [register]/[removeFully] write it inside the lambda and read it
 * immediately after `compute` returns.
 *
 * The reason is structural, not cosmetic: `ConcurrentHashMap` forbids a
 * remapping function from updating any mapping of the same map, and the
 * violating cycle is directly reachable here -- `send` evicts on delivery
 * failure and `evict` re-enters `computeIfPresent` on the same map, giving
 * `IllegalStateException: Recursive update` on the same key or a bin-lock
 * deadlock across keys, all while holding that lock. Delivering the transition
 * *after* `compute` returns (spec: "Broadcast happens after the registry
 * mutation completes") also means the published [PresenceTransition] observes
 * already-settled registry state: a listener that calls back into this
 * registry (as [PresenceBroadcaster] does, via `sendToUser`) sees the post-
 * mutation view, and any eviction its delivery triggers enqueues further work
 * instead of recursing into a lambda.
 *
 * The broadcaster itself is reached only through [ApplicationEventPublisher]
 * (task 5.1a): this class has no constructor dependency on it, so the
 * "broadcaster calls back into the registry" edge stays one-way and no
 * circular bean dependency can form.
 */
@Component
class ConnectionRegistry(
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(ConnectionRegistry::class.java)

    private val sessionsByUser = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()
    private val sessionsById = ConcurrentHashMap<String, WebSocketSession>()

    /**
     * Reverse index session id -> owning user id, maintained by
     * [register]/[removeFully]. Written *after* the per-user set gains the
     * session and removed *before* the set loses it, so a session visible in
     * [sessionsByUser] is always reachable through at least one of the two
     * ownership sources (this index, or [removeFully]'s scan fallback).
     */
    private val ownerBySessionId = ConcurrentHashMap<String, UUID>()

    /**
     * Registers [session] (expected to already be a
     * `ConcurrentWebSocketSessionDecorator`) as active for [userId].
     *
     * Ordering matters: the session enters [sessionsByUser] *first*, then the
     * id indexes. A removal racing between the two can always find the owner
     * (via [removeFully]'s scan, since the reverse index is not yet written)
     * and drop the session from the per-user set -- a session can never be
     * left in [sessionsByUser] while absent from the indexes (spec: "A
     * connection is never half-removed from the registry").
     *
     * The add happens *inside* the compute so a session closing at the exact
     * instant a new one registers cannot orphan the new session: compute on
     * one key is serialized, so an unregister racing here either runs before
     * us (we re-create the set) or after us (it removes our session -- never
     * silently drops it).
     *
     * The reverse divergence is re-verified rather than assumed away: a
     * removal (of *this* very session -- a client that closes in the instant
     * between `compute` and the index writes) can win the race, remove the set
     * entry and publish its offline edge while this method is still between
     * the two. Writing the indexes afterwards would leave a dead session
     * indexed and would publish an online edge for a user with zero live
     * sessions -- spurious, never-corrected online state. So, after the index
     * writes, residency is checked again: if the session is gone, the two
     * index entries are rolled back and no online edge is published (the
     * racing removal already announced the offline state).
     *
     * Emits [PresenceTransition] (online) after the compute returns iff this
     * was the user's first connection *and* the session is still resident.
     */
    fun register(userId: UUID, session: WebSocketSession) {
        var transition: PresenceTransition? = null
        sessionsByUser.compute(userId) { _, sessions ->
            val wasEmpty = sessions.isNullOrEmpty()
            val updated = (sessions ?: ConcurrentHashMap.newKeySet()).also { it.add(session) }
            // Task 5.1: the only side effect inside the lambda is capturing
            // the before/after emptiness into this local. Publication happens
            // below, after the compute has returned and released the bin.
            if (wasEmpty) transition = PresenceTransition(userId = userId, online = true)
            updated
        }
        sessionsById[session.id] = session
        ownerBySessionId[session.id] = userId

        if (sessionsByUser[userId]?.contains(session) != true) {
            // A removal won the race described above: un-index the dead
            // session and stay silent -- the offline edge is already out.
            log.debug("Session {} of user {} was removed while registering; dropping indexes", session.id, userId)
            sessionsById.remove(session.id)
            ownerBySessionId.remove(session.id)
            return
        }
        transition?.let(::publish)
    }

    /**
     * Removes [session] from every index that tracks it and emits a
     * [PresenceTransition] (offline) iff this was the user's last connection.
     *
     * [userId] is the caller's handshake-derived hint; ownership is still
     * resolved through this registry's own state (task 5.1b), so a hint that
     * disagrees with what was actually registered cannot strand the session.
     */
    fun unregister(userId: UUID, session: WebSocketSession) {
        removeFully(session, hintUserId = userId)
    }

    /** Resolves the registered (decorated) session instance for a raw session id, if still registered. */
    fun sessionById(sessionId: String): WebSocketSession? = sessionsById[sessionId]

    /** A point-in-time copy of the user ids currently holding at least one connection. */
    fun onlineUserIds(): Set<UUID> = sessionsByUser.keys.toSet()

    /** Whether [userId] currently holds at least one connection. */
    fun isOnline(userId: UUID): Boolean = sessionsByUser.containsKey(userId)

    /**
     * A point-in-time copy of [userId]'s currently registered sessions, for
     * senders that need to filter sessions before delivering to them
     * ([PresenceBroadcaster] skips sessions still waiting for their
     * first-event snapshot).
     */
    fun sessionsOf(userId: UUID): List<WebSocketSession> = sessionsByUser[userId]?.toList() ?: emptyList()

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
        sessionsOf(userId).forEach { session ->
            if (excluding != null && session.id == excluding.id) return@forEach
            send(session, event)
        }
    }

    private fun evict(session: WebSocketSession) {
        removeFully(session)
        runCatching { session.close(CloseStatus.SERVER_ERROR) }
    }

    /**
     * Removes [session] from both id indexes and from its owning user's set,
     * emitting `PresenceTransition(userId, online=false)` iff the user's set
     * became empty. Every removal path funnels here so "half-removed" is
     * structurally impossible (spec: "A connection is never half-removed from
     * the registry"):
     *
     * - Ownership comes from this registry's own index ([ownerBySessionId]),
     *   never from `session.attributes` (task 5.1b). Under the previous
     *   attributes-based resolution an unresolvable identity left the session
     *   in `sessionsByUser` while it was already gone from `sessionsById` --
     *   that user then stayed "online" forever, held up by a connection that
     *   no longer existed.
     * - The caller's [hintUserId] and, as the last resort, a scan of
     *   [sessionsByUser] cover the remaining windows: a removal racing a
     *   still-incomplete [register] (hint present, reverse index not yet
     *   written), and the reverse strand (indexes consumed before the set
     *   entry landed). A concurrent `ConcurrentHashMap` scan is weakly
     *   consistent and cannot throw here; it only ever runs in these rare
     *   races, never on the steady path.
     */
    private fun removeFully(session: WebSocketSession, hintUserId: UUID? = null) {
        sessionsById.remove(session.id)
        val ownerId = ownerBySessionId.remove(session.id)
            ?: hintUserId
            ?: sessionsByUser.entries.firstOrNull { it.value.contains(session) }?.key

        var transition: PresenceTransition? = null
        if (ownerId != null) {
            sessionsByUser.computeIfPresent(ownerId) { _, sessions ->
                sessions.remove(session)
                val updated = sessions.ifEmpty { null }
                if (updated == null) {
                    transition = PresenceTransition(userId = ownerId, online = false)
                }
                updated
            }
        }
        transition?.let(::publish)
    }

    /**
     * Publishes an observed edge. Deliberately the *only* presence work this
     * class does outside its own maps: the listener decides what happens next
     * (resolve partners, compute snapshots, deliver), on its own thread and
     * in its own transaction.
     */
    private fun publish(transition: PresenceTransition) {
        log.debug(
            "Presence transition: user {} is now {}",
            transition.userId,
            if (transition.online) "online" else "offline",
        )
        eventPublisher.publishEvent(transition)
    }
}