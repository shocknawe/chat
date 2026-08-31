package com.example.chat.ws

import java.util.UUID

/**
 * The online-state edge detected inside [ConnectionRegistry]'s
 * `sessionsByUser.compute*` lambda for a single user: the user's bound
 * connection set changed between empty and non-empty
 * (add-conversation-creation-presence-inspector task 5.1, design.md decision
 * 4). [online] is the state *after* the mutation; [userId] is the user whose
 * state changed as resolved by the registry's own indexes -- never inferred
 * from a session late in its lifecycle.
 *
 * Published via `ApplicationEventPublisher` immediately *after* the `compute`
 * that observed the edge returns -- never from inside the lambda, and never
 * together with any repository access or socket delivery. That is the whole
 * point of the seam: the registry must not depend on the broadcaster (which
 * calls back into `registry.sendToUser`), so the signal is the loosest
 * possible coupling -- a plain application event a listener may consume
 * without the registry knowing the consumer exists (task 5.1a: breaks the
 * registry<->broadcaster constructor cycle explicitly).
 *
 * Delivery semantics, deliberately minimal: the event carries only the fact
 * of the edge, no recipient list and no snapshot. Everything scoped --
 * partner resolution, snapshot computation, per-recipient delivery -- happens
 * later in [PresenceBroadcaster], on its own single-threaded executor, which
 * is what keeps connection bookkeeping free of database access and message
 * delivery (spec: "Presence evaluation never blocks connection bookkeeping").
 *
 * Note the event is delivered synchronously to listeners on the publishing
 * thread. [PresenceBroadcaster]'s listener does nothing but enqueue work on
 * its executor and never blocks or throws, so the edge detection that
 * produced this event stays cheap even mid-eviction.
 */
data class PresenceTransition(
    val userId: UUID,

    /** `true` when the user's first connection appeared; `false` when the last one left. */
    val online: Boolean,
)