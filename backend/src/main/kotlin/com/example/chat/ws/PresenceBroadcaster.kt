package com.example.chat.ws

import com.example.chat.service.PresenceService
import com.example.chat.ws.protocol.Presence
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Turns presence edges into delivery: consumes [PresenceTransition] events
 * from [ConnectionRegistry] and gets each affected recipient a fresh, complete
 * scoped [Presence] snapshot
 * (add-conversation-creation-presence-inspector tasks 5.2 / 5.2a / 5.3,
 * design.md decision 4).
 *
 * ## Why this class exists as a separate bean, with an event in between
 *
 * The broadcaster must call back into the registry (`send`/`sessionsOf`) while
 * the registry must not depend on the broadcaster. Wiring the broadcaster
 * straight into the registry's constructor (or vice versa) creates a circular
 * bean dependency. The edge is therefore cut explicitly with an
 * `ApplicationEventPublisher`/`@EventListener` pair (task 5.1a): the registry
 * publishes an edge and forgets it; this bean listens without the registry
 * ever knowing a consumer exists. The listener is deliberately trivial --
 * nothing but an enqueue -- so the synchronous event dispatch costs the
 * registry's calling thread nothing.
 *
 * ## One single-threaded executor is the whole ordering story (task 5.2a)
 *
 * Snapshots are **wholesale replacements**, so an out-of-order delivery turns
 * into stale client state that no later frame necessarily repairs -- wholesale
 * replacement is only self-healing if the newest snapshot is also the *last
 * delivered* one. That invariant is produced here, not in the client: the
 * entire compute-and-enqueue chain (partner lookup, registry reads, per-
 * recipient snapshot computation, sends) runs on **one** single-threaded
 * executor, so transitions are handled strictly in the order their edges were
 * published, and snapshots are computed and delivered in that same total
 * order.
 *
 * That is also why the snapshot is computed *on* the executor rather than on
 * the publishing thread before enqueueing: presence listeners run on whatever
 * WebSocket container thread observed the edge, and several threads publish
 * concurrently; a snapshot computed before enqueueing could be computed out of
 * order even though the enqueues themselves were ordered. Computing inside the
 * task makes computation order == enqueue order == delivery order, per
 * recipient.
 *
 * ## Eviction during a broadcast (task 5.7)
 *
 * A broadcast run calls `registry.send`, and a failing send evicts its
 * session synchronously -- potentially publishing another
 * [PresenceTransition] *from within this executor's thread*. That is safe and
 * terminal: the registry's `compute` lambda has long since returned (task
 * 5.1), the listener only enqueues (an executor may enqueue from its own
 * thread; the task is simply dequeued later), and every eviction strictly
 * shrinks the registry, so the chain of follow-up tasks terminates. This
 * thread never blocks on its own queue, so there is no self-deadlock; the
 * executor's thread is replaced if a task ever escaped its guard, but the
 * guard below means no broadcast failure can kill the worker and silently
 * strand the next transitions.
 *
 * ## The first event on a new connection is its snapshot (task 5.3)
 *
 * [onSessionOpened] is called by `WebSocketConnectionHandler` *before*
 * `registry.register`. Enqueueing the connect snapshot first puts that task
 * ahead of every task any later edge can produce for this session; a task
 * enqueued earlier has nothing to target yet, because the session only becomes
 * visible to the registry -- and only becomes a transition target -- inside
 * `register`.
 *
 * Between enqueue and execution the session is still not a transition target:
 * while its id sits in [awaitingInitialSnapshot], transition broadcasts skip
 * it ([broadcastTransition] filters via `sessionsOf`). The gate is cleared
 * only when the snapshot task itself has run. Since the single executor runs
 * tasks in enqueue order, precisely one of two things holds for any edge
 * observed before the snapshot task ran: its broadcast task was enqueued
 * before the snapshot task, so it runs first and *skips* this gated session --
 * the edge is not lost, because the snapshot the session receives right after
 * is computed at its own execution time and already reflects it -- or it was
 * enqueued after, so the snapshot went first. Either way the first event the
 * socket receives is its own snapshot, computed no earlier than any transition
 * it missed.
 *
 * (Scope note: only *presence* broadcasts are gated. Other fan-out --
 * `NEW_MESSAGE`, `MESSAGE_ACK` -- is delivered directly by its sender, not
 * here, and is not ordered against snapshots. Wholesale replacement makes that
 * race self-healing: a message that beats the snapshot by microseconds leaves
 * presence stale for exactly as long as it takes the already-enqueued snapshot
 * to arrive.)
 *
 * ## Recipients on a transition
 *
 * A transition for user U targets every *connected* user sharing a
 * conversation with U -- the partners of U, from [PresenceService], intersected
 * with the registry's live online set. U themself is never a target: a user is
 * not their own conversation partner, and U's own sockets already learn their
 * own state at connect. Each recipient then gets a snapshot scoped to *them*
 * (their partners, intersected with the online set captured once per run), not
 * a snapshot scoped to U.
 *
 * Shutdown: the worker is a named daemon thread and the bean implements
 * [DisposableBean], so a graceful shutdown stops accepting tasks, drains what
 * is in flight, and never leaks the thread either way. Rejections during
 * context close are swallowed -- a presence broadcast lost to shutdown is
 * inconsequential, and "presence evaluation never blocks connection
 * bookkeeping" extends to shutting down.
 */
@Component
class PresenceBroadcaster(
    private val presenceService: PresenceService,
    private val connectionRegistry: ConnectionRegistry,
) : DisposableBean {

    private val log = LoggerFactory.getLogger(PresenceBroadcaster::class.java)

    /**
     * Single worker on purpose (task 5.2a): every presence broadcast in the
     * process is computed and enqueued here, giving snapshots a total order.
     * Daemon, and named for diagnosability in thread dumps.
     */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "presence-broadcaster").apply { isDaemon = true }
    }

    /**
     * Sessions whose first-event snapshot has been *enqueued* but not yet
     * *sent*. A session id is present here from [onSessionOpened] until its
     * snapshot task actually runs -- exactly the window in which the session
     * is not yet eligible to receive transition broadcasts.
     */
    private val awaitingInitialSnapshot = ConcurrentHashMap.newKeySet<String>()

    /**
     * Called by `WebSocketConnectionHandler` before the session is registered:
     * enqueues this connection's scoped snapshot as its first event (task
     * 5.3). See the class doc for why enqueuing before registration is what
     * makes the ordering guarantee, rather than merely hoping for it.
     *
     * The gate entry is written *before* the enqueue (a task that started
     * running before we marked the session would already be too late) and
     * rolled back if the executor rejects -- a task discarded at shutdown must
     * not leave its session gated forever, which would silence every later
     * transition broadcast aimed at it.
     */
    fun onSessionOpened(userId: UUID, session: WebSocketSession) {
        awaitingInitialSnapshot.add(session.id)
        val queued = submit {
            try {
                sendInitialSnapshot(userId, session)
            } finally {
                // Cleared once sent (or once the send was attempted and, e.g.,
                // the session was evicted while it waited) -- from this point
                // the session is an ordinary transition target.
                awaitingInitialSnapshot.remove(session.id)
            }
        }
        if (!queued) {
            awaitingInitialSnapshot.remove(session.id)
        }
    }

    /**
     * Presence edge -> one broadcast run. Only an enqueue happens on the
     * publishing (WebSocket container) thread: no database access, no sends,
     * nothing that could block connection bookkeeping.
     *
     * This relies on Spring's **default synchronous**
     * `ApplicationEventMulticaster`: the edge is published on the container
     * thread that mutated the registry, and this method runs *right there*,
     * enqueueing in that same order. The publish->enqueue order is what the
     * total-ordering AC rests on -- if this listener ever ran on an async
     * task-executing multicaster, edges would be dispatched in whatever order
     * that executor picked, snapshots could reach a recipient out of order,
     * and wholesale replacement would silently start serving stale presence.
     */
    @EventListener
    fun onPresenceTransition(transition: PresenceTransition) {
        submit {
            try {
                broadcastTransition(transition.userId)
            } catch (ex: Exception) {
                // Never let one failed broadcast run kill the worker: the
                // next edge must still be broadcast.
                log.warn("Presence broadcast for user {} failed", transition.userId, ex)
            }
        }
    }

    override fun destroy() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("Presence broadcaster did not drain within 5s; discarding pending broadcasts")
            executor.shutdownNow()
        }
    }

    private fun submit(task: () -> Unit): Boolean {
        try {
            executor.execute(task)
            return true
        } catch (ex: RejectedExecutionException) {
            // Only reachable while the bean is shutting down. Losing a
            // presence broadcast then is correct (and inevitable); letting the
            // exception escape into `register`/`evict` is not.
            log.debug("Presence broadcast discarded; broadcaster is shutting down", ex)
            return false
        }
    }

    /**
     * The connect snapshot for [userId]'s new [session]: every online user
     * sharing a conversation with them, computed at execution time so it
     * reflects every transition that preceded it.
     */
    private fun sendInitialSnapshot(userId: UUID, session: WebSocketSession) {
        val snapshot = snapshotFor(userId, connectionRegistry.onlineUserIds())
        log.debug("Sending initial presence snapshot to session {} of user {}", session.id, userId)
        connectionRegistry.send(session, Presence(online = snapshot))
    }

    private fun broadcastTransition(changedUserId: UUID) {
        // Deliberate MVP trade-offs, recorded so they do not get relitigated
        // silently: (a) one partner-set query *per online recipient* per
        // transition -- an accepted N+1 (design.md decision 4) at demo scale;
        // the documented next step is a cache invalidated by
        // CONVERSATION_CREATED, the only event that changes partner sets.
        // (b) Delivery shares this executor with snapshot computation, so one
        // stuck recipient stalls presence broadcasts for everyone for up to
        // the session's send-time limit (10s today) -- accepted: presence is
        // low-volume, the ConcurrentWebSocketSessionDecorator bounds each
        // send, and queueing delivery on per-session threads would surrender
        // the total order the whole design rests on.
        // Captured once per run: every snapshot in this run is computed against
        // one consistent instant of the live registry.
        val online = connectionRegistry.onlineUserIds()
        for (recipient in presenceService.partnerIdsOf(changedUserId)) {
            if (!connectionRegistry.isOnline(recipient)) continue
            val snapshot = snapshotFor(recipient, online)
            connectionRegistry.sessionsOf(recipient)
                .filter { it.id !in awaitingInitialSnapshot }
                .forEach { connectionRegistry.send(it, Presence(online = snapshot)) }
        }
    }

    /**
     * [candidateIds] (this recipient's conversation partners) intersected with
     * the live online set -- the recipient's complete scoped online set. Sorted
     * so the wire form is deterministic for a given state, which makes wire
     * assertions possible without weakening the contract (order carries no
     * meaning).
     */
    private fun snapshotFor(recipientId: UUID, online: Set<UUID>): List<UUID> =
        presenceService.partnerIdsOf(recipientId).filterTo(mutableListOf()) { it in online }.sorted()
}