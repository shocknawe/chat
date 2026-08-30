# Design: add-conversation-creation-presence-inspector

Derived from `plan-02-ui.md` ("Conversation Creation, Presence, and the Inspector UI"),
which is the authoritative argument for every decision below. This document condenses it;
where they disagree, the plan wins.

## Context

The realtime messaging MVP (archived change `2026-08-30-add-realtime-messaging-mvp`) shipped
with Alice and Bob already conversing (`SeedData.kt:18-24`), so the set of people a user
could start a conversation *with* is empty. The frontend predates the approved one-shot
design (`docs/one-shot/oneshot-info-drawer.html`). Schema evolution is `ddl-auto: update`
(`application.yml:26`) with no Flyway; participants live in a join table
(`Conversation.kt:29-45`); `ConnectionRegistry` has three session-set mutation paths
(`register`, `unregister`, `evict` — `ConnectionRegistry.kt:115`). All of this plan's
backend changes are additive by construction, so no migration tool is introduced.

**Contract first**: all five backend additions land in `docs/openapi.yaml` before either
implementation begins. Two edits are not appends: the `Message` schema's rationale paragraph
claiming `clientMessageId` is "deliberately NOT" exposed must be *replaced* with the reason
for the reversal (not deleted), and `Conversation` gains `lastMessage`, affecting every
existing listing consumer.

## Goals / Non-Goals

**Goals:**

- Make conversation creation, previews, presence, connection state, and the info drawer
  demonstrable at runtime against the real backend under Docker Compose.
- Reach parity with the one-shot design while preserving every existing frontend state
  machine (loading/error/retry/empty are re-skinned, not rewritten).
- Keep every change additive and per-slice rollback-able; contract changes before code.

**Non-Goals:**

- No in-browser mock backend (a second, conformance-tested backend that a reviewer could
  mistake for the real stack; its one irreplaceable demo value — severing the connection —
  is a `DEV`-gated `socket.close()` button instead).
- No Flyway/Liquibase (trigger to adopt is the first *non*-additive schema change).
- No read/delivery receipts, typing indicators, unread counts, message editing/deletion/
  reactions, group conversations, presence deltas, sequence numbers, history cursors.
- No real authentication (`X-User-Id` posture is deliberately retained).

## Decisions

### 1. Seed the directory before anything else

**Decision**: Add Carol, Dan, Erin (new ids); seed Alice↔Carol with three messages using
deterministic `clientMessageId`s and back-dated timestamps; leave every existing id and
name untouched; `DataSeeder` backfills `pairKey` on pre-existing 1:1 rows.
**Why**: Without reachable new-conversation candidates, roughly half the feature set is
demonstrable only by editing the database by hand. Both the `+` dialog's populated path and
its exhausted path must be reachable.
**Alternative considered**: `NOT NULL` schema change requiring `docker compose down -v` —
rejected; boot must stay non-destructive against an existing volume.

### 2. Pair uniqueness via a nullable unique `pair_key` column

**Decision**: `Conversation` gains `pairKey: String?` under an explicit *table-level* unique
constraint (`@Table(uniqueConstraints = [...])`, named `uq_conversation_pair_key` — mirroring
the `Message` pattern, not a bare `@Column(unique = true)`), computed as the two participant
UUIDs sorted lexicographically (`"<low>:<high>"`). The 1:1 non-null invariant is enforced in
the service. Because the whole race defence rests on the constraint existing, its presence is
**asserted at boot** rather than assumed (see Risks).
**Why**: A functional index over the participant pair is impossible — no row holds both ids
(join table, one `user_id` per row) and `ddl-auto: update` never emits functional indexes.
Nullable is deliberate twice: a `NOT NULL` addition fails on boot against seeded data under
`ddl-auto: update`, and Postgres permits many NULLs, leaving the column forward-compatible
with group conversations.
**Race handling**: two concurrent creates both pass the existence check; the loser's
`DataIntegrityViolationException` marks its transaction rollback-only, so the recovery
re-read runs in a **fresh transaction** at the service boundary, returning `200`. Catching
and re-reading inside the failed transaction produces
`TransactionRequiredException`/`UnexpectedRollbackException`, not a fix. Since Spring's
`@Transactional` is proxy-based, "fresh transaction" is only true if the insert lives in its
*own bean* — this codebase already proved the pattern with `MessageService`/`MessageWriter`,
and the same split applies here.

### 3. Creation is idempotent (201/200), never 409

**Decision**: The intent "open a conversation with this person" is satisfied either way;
the UI treats both statuses identically. Caller is implicit (`{caller, participantId}`), so
no `403` exists on this endpoint. Errors follow the document's existing reservation: `400`
missing/malformed/self, `401` unknown identity, `404` unknown participant.
**After commit**, `CONVERSATION_CREATED` (with `lastMessage: null`) is pushed to every
active connection of the *other* participant only — the creator already holds the REST
response.

### 4. Presence is scoped snapshots detected inside `ConnectionRegistry`

**Decision**: Online iff ≥1 open session. All three mutation paths funnel through
`sessionsByUser.compute*`, so the empty↔non-empty edge is observable in exactly one place;
a transition **detected** there covers `register`, `unregister`, **and** `evict` — the
eviction path runs precisely when a connection has failed and presence is most likely to be
wrong.
**Detect inside the lock, dispatch outside it.** The `compute*` lambda captures the
before/after emptiness into a local and does nothing else — no repository call, no `send`.
`ConcurrentHashMap` forbids a remapping function from updating other mappings, and the
violating cycle is reachable here: `send` evicts on delivery failure and `evict` re-enters
`computeIfPresent` on the same map, giving `IllegalStateException: Recursive update` on the
same key or a bin-lock deadlock across keys — while holding that lock across a JDBC round
trip. The broadcast therefore runs after `compute*` returns.
Snapshots (full scoped set, wholesale replacement) not deltas: self-healing, no client
merge logic, cannot drift after a dropped frame — **provided snapshots arrive in the order
they were computed**. Wholesale replacement turns any out-of-order delivery into a stale
state that persists until the next transition, so all presence broadcasts (including a new
socket's first-event snapshot) are dispatched through a single-threaded executor, giving
computation and enqueue a total order. This is why sequence numbers are not needed, not an
accident of them being out of scope.
**Scoping** is a privacy boundary, not a payload optimization: presence is the one signal
that leaks across conversations by default, and 1:1 messaging is the product. Each
recipient gets only their conversation partners; one partner-set query runs per transition
(acceptable now; a cache invalidated by `CONVERSATION_CREATED` — the only event that changes
partner sets — is the documented next step). That query lives in a
`@Transactional(readOnly = true)` `PresenceService` and projects partner **ids** directly:
`open-in-view` is `false` and transitions fire on a WebSocket thread with no transaction, so
touching the lazy `participants` association there would throw
`LazyInitializationException`.
A new socket's first event is its presence snapshot.

### 5. `lastMessage` is computed, not denormalized

**Decision**: `Conversation.lastMessage` = the last element under the existing
`(createdAt ASC, id ASC)` ordering (queried as `ORDER BY created_at DESC, id DESC LIMIT 1`,
served by the existing composite index as a backward scan), absent for empty history;
returned by listing and creation responses.
**Wire form**: `spring.jackson.default-property-inclusion: non_null` means a null
`lastMessage` is *omitted* from the JSON, not serialised as `null`. Either the DTO opts out
of that globally-set inclusion rule or the property is genuinely optional — the choice is
recorded in `docs/openapi.yaml` (`nullable: true`, not `required`), and clients treat absent
and null identically either way.
**Why computed**: The justification is not N+1 avoidance — the listing already does an N+1
on `participants` today, and this change makes N grow (creating conversations is the
headline feature; a five-user directory reaches four partners in a minute of clicking). It
is that rail preview and thread history become two views of one server-ordered fact that
cannot disagree, with no per-conversation fetch-and-sort in the frontend. The accepted bound
is O(N)+1: an `@EntityGraph` collapses the participants fetch and one indexed lookup per
conversation supplies the preview. At scale the fix is a denormalized
`last_message_id` maintained in the message-write transaction — which introduces a
write-ordering concern this plan deliberately does not want yet.
The frontend keeps its pending/failed client-side override on top of the server value.

### 6. `clientMessageId` is exposed everywhere, still non-authoritative

**Decision**: Required on `Message` in REST history and `NEW_MESSAGE`/`MESSAGE_ACK`; already
stored non-null under `uq_message_sender_client_message_id`. The token is per-sender scoped
and grants nothing — it is its own sender's idempotency key, never an identifier. This is a
deliberate contract reversal (recorded in `docs/openapi.yaml` with its reason), which is the
point the inspector hangs on: received messages and post-reload messages can now show their
correlation token honestly instead of a fabricated value.

### 7. Frontend: re-skin, don't rewrite; bounded honest inspector

**Decision**: Re-skin `App.tsx`, `ConversationList.tsx`, `ThreadPane.tsx`, `Composer.tsx`
(state machines preserved). `ChatSocket` gains `reconnectNow()` (cancels scheduled backoff;
no-op while connected, after `terminate()`, and while an attempt is already in flight —
`scheduleReconnect` nulls `retryTimer` *before* calling `connect()`, so "no timer" does not
mean "not attempting") and `dropConnection()` (non-1000 close so the normal reconnect path
engages and the queue survives; `terminate()` is irreversible and would end the demo). Both
require `connect()` to first detach listeners from and close any prior socket — it currently
reassigns `socket` without doing so, which would leave two live sockets, two registry entries
for one user (breaking presence), and duplicated `NEW_MESSAGE`. The existing queue already
re-sends under the original `clientMessageId` (`chatSocket.ts:99-109`), so no new dedup logic
— and because a correlated `ERROR` removes the queue entry, manual retry of a rejected
message can reuse the same token without hitting the dedup guard.
**Inspector**: bounded session-scoped transport ledger fed at existing instrumentation
boundaries plus a new `onCommandSent` hook; `InspectTarget` union; docked ≥980px, overlaid
below. Crossing the threshold while open **re-presents** in the other mode — closing would
discard a deliberate selection at the one moment both presentations exist.
**Two kinds of absence**: persistent fields (ids, token, timestamps, ordering key, content
length) come from the message itself and are always available; transport steps are
session-scoped. A message loaded from history with no ledger record states "transport not
observed in this session" rather than rendering a column of em-dashes; a field that was
never applicable renders as absent. Both honest — only one is confusing as a dash.

### 8. Vertical slices with bottom-up cutting

Implementation order (each independently shippable and demonstrable): **0** contract+seed →
**1** shell re-skin → **2** conversation creation → **3** rail previews → **4** presence →
**5** connection state → **6** info drawer. Cut from the bottom: theme toggle/mobile overlay
inside slice 1, then slice 5's recovery confirmation, then slice 6 entire. Slice 0 is never
cut — without it slices 2–4 cannot be demonstrated.

## Risks / Trade-offs

- **`X-User-Id` is not authentication** → accepted MVP posture; fix (session cookie/signed
  token at the same filter) changes none of this design's shapes.
- **`404` on unknown `participantId` is a user-enumeration oracle** → irrelevant against a
  five-user seeded directory with public `GET /api/users`; revisit when the directory is real.
- **`ddl-auto: update` is not guaranteed to add a unique constraint to a table that already
  exists** → the whole race defence in decision 2 is inert without it, and an integration
  test run against a freshly created schema passes in exactly the case that is not at risk.
  Mitigated by asserting `uq_conversation_pair_key` exists at boot and by verifying against a
  retained volume, not only a fresh one.
- **Presence scoping is a boundary any user can widen** → creating a conversation is
  unilateral (no consent, no rate limit), so a user can make themselves everyone's partner in
  as many requests as there are users and see the whole directory's presence. Accepted for a
  five-user seeded demo; a real directory needs consent-on-create or an accept step.
- **Presence has no heartbeat** → a half-open connection (lid closed, NAT timeout) reports
  online until a send to it fails, which for a user receiving no messages is unbounded. The
  spec's "online exactly while a connection is bound" is therefore best-effort. Accepted;
  ping/pong with an idle timeout is the fix if presence accuracy becomes load-bearing.
- **Presence is single-instance** (in-memory `ConnectionRegistry`) → same limitation already
  applies to message fan-out; shared registry or pub/sub explicitly excluded.
- **Partner-set query per transition** → fine at this size; cache invalidated by
  `CONVERSATION_CREATED` when needed.
- **Slice 0 publishes contract elements that slices 2–6 implement** → shipping slice 0 or 1
  alone would leave `docs/openapi.yaml` asserting five things that do not exist, and the
  parse check cannot catch it. Mitigated by `x-status: planned` markers removed per slice.
- **Transport ledger is session-scoped; reload loses it** → correct trade (the alternative
  is a logging product); the UI says so rather than hiding it.
- **The one-shot HTML will drift after slice 1** → accepted; it is a design artifact, not a
  fixture.

## Migration Plan

Deploy is per-slice; rollback is per-slice. Every backend change is additive; every frontend
change is confined to existing components. Slice 6's `clientMessageId` is the only contract
change existing clients could notice, and it is an added property on a schema that tolerates
unknown properties in both directions. Boot against an existing database works via seeder
backfill — no dump-and-recreate step.

## Open Questions

Resolved-by-default (per plan; revisit only under time pressure):

1. Slice 6 in or out (in; largest but highest-signal slice).
2. `pair_key` nullable+backfill vs `NOT NULL`+destructive recreate (nullable+backfill).
3. Presence scoped vs full-directory (scoped).
4. Recovery confirmation: timer vs next-action (timer; next-action is friendlier to
   screen-reader users).
5. Seeded Alice↔Carol messages straddle a day boundary so slice 1's date divider renders.
