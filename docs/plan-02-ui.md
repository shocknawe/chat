# Plan 02 v3 — Conversation Creation, Presence, and the Inspector UI

The React app catches up to
[`docs/one-shot/oneshot-info-drawer.html`](docs/one-shot/oneshot-info-drawer.html) — Info
drawer, connection indicator, conversation creation, rail previews, presence dots, shell
re-skin — backed by real endpoints rather than fixtures.

Five backend additions make that possible: `POST /api/conversations`, a
`CONVERSATION_CREATED` event, `Conversation.lastMessage`, a `PRESENCE` event, and
`Message.clientMessageId`.

## Load-bearing decisions

Four choices the rest of this document rests on. Each is argued where it is specified; they
are collected here because getting any of them wrong invalidates a whole slice.

- **The directory is seeded before anything else is built.** Today it is Alice and Bob,
  already conversing (`SeedData.kt:18-24`), so the set of people you could start a
  conversation *with* is empty. Until that changes, roughly half of this plan is unreachable
  at runtime — demonstrable only by editing the database by hand.
- **Pair uniqueness is a `pair_key` column, not a functional index over the participant
  pair.** Participants live in a join table (`Conversation.kt:29-45`), one `user_id` per row,
  so no row holds both ids for an index to cover; and schema evolution is `ddl-auto: update`
  (`application.yml:26`) with no Flyway, which emits unique constraints for annotated columns
  and never functional indexes.
- **Presence is scoped to conversation partners.** In a 1:1 private-messaging app, who is
  online is the one signal that leaks across conversations by default. Scoping it costs one
  repository query per transition.
- **Presence transitions are detected inside `ConnectionRegistry`.** Three paths remove a
  session — `register`, `unregister`, and `evict` (`ConnectionRegistry.kt:115`) — and the
  third is the one that runs when a connection has already failed, which is exactly when
  presence is most likely to be wrong.

There is no in-browser mock backend; see [Deliberately not doing](#deliberately-not-doing).

---

# Design

## Contract first

All five additions land in [`docs/openapi.yaml`](docs/openapi.yaml) before either
implementation. Two of them are not additive to the document and must be edited, not
appended:

- The `Message` schema carries a rationale paragraph that says `clientMessageId` is
  "**deliberately NOT** part of this shape". That paragraph is now wrong. Replace it with the
  reason for the reversal rather than deleting it — the next reader needs to know the
  omission was considered and overturned, not overlooked.
- `Conversation` gains a `lastMessage` property, so every existing consumer of the listing
  response is affected.

`GET /api/users` is already public (`security: []`); the creation endpoint is not. Error
semantics stay consistent with what the document already reserves: `401` for an unknown
identity, `403` for a valid identity acting outside its own participation, `404` for a
resource that does not exist.

## Seed data — a prerequisite, not a detail

`SeedData.kt` declares its ids "a public contract of the MVP demo environment." We **add**
without changing any existing value:

| User | Id | Conversations at boot |
| --- | --- | --- |
| Alice | `1111…` (unchanged) | Bob (`3333…`, unchanged), Carol |
| Bob | `2222…` (unchanged) | Alice |
| Carol | new | Alice |
| Dan | new | none |
| Erin | new | none |

The Alice↔Carol conversation is seeded with three messages so that the rail has more than one
row and previews have something to preview; seeded messages use deterministic
`clientMessageId`s and back-dated server timestamps. Every user has at least one person they
can start a conversation with, so the `+` dialog's populated path and its exhausted path are
both reachable.

Without this, roughly half of the plan is unreachable at runtime — the feature would be
demonstrable only by editing the database by hand.

## Backend

### `POST /api/conversations` — idempotent 1:1 creation

```text
POST /api/conversations        X-User-Id: <caller>
{ "participantId": "<uuid>" }

201 Created  -> Conversation   a new 1:1 conversation was created
200 OK       -> Conversation   one already existed between these two users
400          -> participantId missing, malformed, or equal to the caller
401          -> missing, malformed, or unknown caller identity
404          -> participantId is not a known user
```

**Idempotent, not `409`.** The intent is "open a conversation with this person," satisfied
either way. The UI treats 200 and 201 identically.

**Caller is implicit.** Participants are `{caller, participantId}`, so the request cannot
name a conversation the caller is not part of. There is no `403` on this endpoint because
there is no way to ask for one.

**`pair_key` is how uniqueness is enforced.** `Conversation` gains a nullable
`pairKey: String?` with `@Column(unique = true)`, computed as the two participant UUIDs
sorted lexicographically and joined (`"1111…:2222…"`). Three consequences worth stating
before anyone writes the code:

- *Nullable is deliberate, twice over.* Adding a `NOT NULL` column to a table that already
  holds the seeded conversation fails on boot under `ddl-auto: update` — you would need a
  `docker compose down -v`, which is exactly the destructive migration this plan claims not
  to require. Nullable also leaves the column forward-compatible with group conversations
  (out of scope): Postgres unique indexes permit many NULLs, so a future N-party
  conversation simply carries no pair key. The non-null invariant for 1:1 conversations is
  enforced in the service, not the schema.
- *The seeder backfills.* `DataSeeder` computes `pairKey` for any 1:1 conversation that has
  none, so the pre-existing Alice↔Bob row joins the constraint without manual intervention.
- *The race is handled where it actually surfaces.* Two concurrent creates both pass the
  existence check and both attempt the insert; the loser gets a
  `DataIntegrityViolationException`. Its transaction is already marked rollback-only at that
  point, so the recovery read must run **outside** it — catch at the service boundary and
  re-read in a fresh transaction, returning `200`. Catching and re-reading inside the failed
  transaction is the standard way to turn this into a
  `TransactionRequiredException`/`UnexpectedRollbackException` instead of a fix.

**`CONVERSATION_CREATED`.** After commit, the new `Conversation` (with `lastMessage: null`)
is pushed to every active connection of the *other* participant so their rail updates without
a refetch. The creator has the REST response and receives no event.

### `Conversation.lastMessage` — rail previews

`Conversation` gains `lastMessage: Message | null` — the latest message by the existing
`(createdAt ASC, id ASC)` ordering, or `null` for an empty history. Returned by both
`GET /api/conversations` and `POST /api/conversations`.

The honest justification at this size is **not** N+1 avoidance — N is 2. It is that the rail
row's preview and the thread's history are then two views of one server-ordered fact, so they
cannot disagree, and the frontend needs no per-conversation fetch-and-sort to render a list.
The N+1 argument becomes the real one at a directory of any size, which is why the shape is
right even though the current cost is not the reason.

The frontend keeps its pending/failed client-side override on top of this value: a message
still in flight reads as sending in the rail, because the server has nothing to say about it
yet.

### Presence — scoped snapshots

```text
server -> client: { "type": "PRESENCE", "onlineUserIds": ["<uuid>", ...] }
```

- A user is **online iff they hold ≥1 open session** in `ConnectionRegistry`.
- The set sent to a recipient contains **only that recipient's conversation partners** who
  are online. Each recipient therefore gets a different payload. This is a privacy boundary,
  not a payload optimization: presence is the one signal in this app that leaks across
  conversations by default, and 1:1 messaging is the product.
- **Snapshots, not deltas.** The full scoped set replaces the client's set wholesale. A
  self-healing snapshot removes all client merge logic and cannot drift after a dropped
  frame.
- A newly connected socket receives its snapshot as its first event.

**Transitions are detected inside `ConnectionRegistry`.** All three mutation paths —
`register`, `unregister`, and `evict` — already funnel through `sessionsByUser.compute*`, so
the empty↔non-empty edge is observable in one place. A listener fired from there is the only
design that cannot be defeated by the eviction path, which is the path that runs precisely
when a connection has failed and presence is most likely to be wrong.

On a transition, the changed user's partner set is read from the repository and each
connected partner is sent a freshly computed snapshot. One query per transition is
acceptable here; the obvious next step, if the directory grows, is a partner-set cache
invalidated by `CONVERSATION_CREATED` — which is the only event that can change it.

### `clientMessageId` on `Message`

`Message` gains a required `clientMessageId` — the sender's correlation token, already stored
non-null under `uq_message_sender_client_message_id`. It now appears in REST history and in
`NEW_MESSAGE`/`MESSAGE_ACK`.

The consequence for the inspector is the point: a *received* message can show the sender's
correlation token, and any message can show it after a reload. The one-shot's caveat —
"inbound messages do not carry the sender's token" — is reversed by a deliberate contract
change, not papered over with a fabricated value.

The token is per-sender scoped and grants nothing: it is an idempotency key for its own
sender, never an authoritative identifier, and knowing another user's token permits no
action. That was already true; exposing it does not change it.

## Frontend

### Shell re-skin

Header, rail rows with avatars, thread header, per-message row with a meta line
(status · Retry · ⓘ), date dividers, empty thread, auto-growing composer, theme toggle,
mobile slide-over rail, skip link, live-region announcer. Every existing loading, error,
retry, and empty state is preserved — this is a re-skin of `App.tsx`,
`ConversationList.tsx`, `ThreadPane.tsx`, and `Composer.tsx`, not a rewrite of their state
machines.

### Connection state

An always-visible pill (word plus dot, never colour alone), an in-thread offline banner with
*Reconnect now*, offline composer copy, and a transient recovery confirmation.

`ChatSocket` gains `reconnectNow()`, which cancels the scheduled backoff delay and attempts
immediately; it is a no-op while connected or after `terminate()`. The existing queue
already re-sends under the original `clientMessageId` (`chatSocket.ts:99-109`), so restored
connections need no new dedup logic.

A **`DEV`-gated** drop-connection control makes the offline path demonstrable without waiting
for a real failure. It is stripped from production builds by `import.meta.env.DEV`.

### Rail previews and presence

Previews render from `lastMessage`, overridden client-side while the user's own newest
message is pending or failed. Presence renders as a dot **plus a word** on each rail row, and
a `PRESENCE` snapshot replaces the online set rather than merging into it.

### `+` conversation creation

A dialog listing directory users the caller has no conversation with, excluding the caller.
Focus is confined while open and returns to the `+` button on dismissal. On success the
dialog closes, the conversation is selected, focus moves to the composer, and the change is
announced. Failure keeps the dialog open with the reason in words and no rail mutation. An
in-flight guard prevents a duplicate submission for the same person.

### Info drawer

A bounded transport ledger fed at the existing instrumentation boundaries plus a new
`onCommandSent` hook, an `InspectTarget` union, docked at ≥980px and overlaid below, and the
"observed or `—`" honesty rule.

Crossing the layout threshold while the drawer is open **re-presents** it in the other mode
rather than closing it. That moment is the one point at which both presentations are
implemented and available; closing is the implementation giving up in front of the user, and
it discards a selection they made deliberately.

One consequence the one-shot does not address: the ledger is bounded and session-scoped,
while the ⓘ affordance sits on every message including messages loaded from history. Opening
the drawer on an old message must not render a column of em-dashes. The **persistent** fields
(message id, correlation token, conversation and sender ids, server timestamp, ordering key,
content length) come from the message itself and are always available; only the **transport
steps** are session-scoped. So the drawer distinguishes two absences in words: a field that
was never applicable, and a message whose transport was not observed in this session. Both
are honest; only one of them is confusing when rendered as a dash.

## Deliberately not doing

**The mock server.** It was a second implementation of the backend (MirageJS, a hand-written
`MockWebSocket`, a broker, a cross-tab bridge, latency and failure injection) plus a third
artifact — a bidirectional conformance test — whose only job was keeping implementation #2
honest against implementation #1. It bought a frontend dev loop without a JVM; `docker
compose up` already provides one, and the graded requirement is explicitly that the app runs
under Docker Compose.

Two specific reasons beyond cost:

- Its cross-tab delivery ran over `BroadcastChannel`. The brief requires a stack that "does
  not rely solely on frontend based peer-to-peer technologies," verified by opening two
  browser windows. A reviewer who runs `dev:mock` first sees exactly the forbidden thing
  working perfectly with no backend running, and has to read the mode gating to learn
  otherwise. That is a bad bet on someone else's reading order.
- The follow-up interview is conducted without AI assistance. A second backend is a second
  thing to defend live.

Its one irreplaceable demo value — severing the connection on demand — is a `DEV`-gated
button calling `socket.close()`.

**Flyway/Liquibase.** `ddl-auto: update` is a deliberate MVP posture already recorded in
`application.yml:24-26`, and every change in this plan is additive by construction (see
`pair_key` above). The trigger to add migrations is the first change that is *not* additive:
a column drop, a type narrowing, or a backfill that cannot be expressed idempotently in the
seeder. That change is not in this plan.

---

# Requirements

## contract

WHEN any implementation work begins,
THE SYSTEM SHALL have the conversation-creation endpoint, the conversation preview property,
the presence event, the conversation-created event, and the message correlation token
documented in the API specification first.

WHERE the specification previously recorded a decision that this plan reverses,
THE SYSTEM SHALL record the reversal and its reason rather than removing the original
statement.

## seed data

THE SYSTEM SHALL seed a directory in which at least one user has no conversation with at
least one other user.

THE SYSTEM SHALL seed at least one user whose conversation list contains more than one
conversation.

THE SYSTEM SHALL seed at least one conversation with a non-empty message history and at
least one with an empty history.

THE SYSTEM SHALL NOT change the identifier or display name of any user or conversation
seeded before this plan.

WHEN the application boots against a database seeded by an earlier version,
THE SYSTEM SHALL add the new seed data and derive any newly required property of existing
rows, without requiring the database to be recreated.

## backend: conversation creation

WHEN an authenticated user requests a conversation with another directory user,
THE SYSTEM SHALL create a conversation whose participants are the caller and that user, and
return it with a created status.

IF a conversation already exists between the caller and the named user,
THEN THE SYSTEM SHALL return that existing conversation with a success status distinct from
creation, without creating a second one.

IF two requests to create a conversation between the same pair arrive concurrently,
THEN THE SYSTEM SHALL persist exactly one conversation and answer both requests successfully.

THE SYSTEM SHALL enforce single-conversation-per-pair with a database constraint rather than
an application-level check alone.

IF a creation request omits the other user, names the caller, or carries a malformed
identifier,
THEN THE SYSTEM SHALL reject it as a client error and create no conversation.

IF a creation request names a user that is not in the directory,
THEN THE SYSTEM SHALL reject it as not found and create no conversation.

IF a creation request carries a missing, malformed, or unknown caller identity,
THEN THE SYSTEM SHALL reject it as unauthorized.

WHEN a conversation is created,
THE SYSTEM SHALL emit a conversation-created event carrying the new conversation to every
active connection of the other participant, after the creating transaction has committed.

## backend: conversation previews

WHEN a user's conversations are listed,
THE SYSTEM SHALL include in each conversation its latest message by the ordering used for
message history, or no message when the history is empty.

WHEN a creation response returns a conversation,
THE SYSTEM SHALL include the same preview property as the listing.

## backend: presence

THE SYSTEM SHALL consider a user online exactly while at least one realtime connection is
bound to that user.

THE SYSTEM SHALL derive presence from the live connection registry at the moment of
broadcast, and shall treat every path that removes a connection — including removal caused by
a delivery failure — as a possible online-state change.

WHEN a realtime connection is established,
THE SYSTEM SHALL send that socket the current set of online user identifiers as its first
event.

WHEN a user's online state changes,
THE SYSTEM SHALL send an updated complete set to every connected user who shares a
conversation with them.

THE SYSTEM SHALL limit each presence set to users with whom the recipient shares a
conversation.

WHILE a user holds more than one connection,
THE SYSTEM SHALL NOT announce that user as offline when one of those connections closes.

## backend: message correlation token

WHEN a message is returned by the history endpoint or carried by any realtime event,
THE SYSTEM SHALL include the correlation token under which it was submitted, including for
messages submitted by another participant.

THE SYSTEM SHALL continue to treat the correlation token as a per-sender idempotency key and
never as the authoritative message identifier.

## frontend: conversation creation

WHEN the signed-in application is displayed,
THE SYSTEM SHALL present an accessibly named control in the conversation rail's heading that
starts a new conversation.

WHEN the user activates that control,
THE SYSTEM SHALL open a modal dialog offering each directory user the current user has no
conversation with, excluding the current user.

IF every other directory user already has a conversation with the current user,
THEN THE SYSTEM SHALL state that in the dialog and present no empty selection list.

WHILE the dialog is open,
THE SYSTEM SHALL confine keyboard focus to it; on dismissal it shall close and return focus
to the opening control.

WHEN the user chooses a person and the request succeeds with either creation or an existing
conversation,
THE SYSTEM SHALL close the dialog, add or select the conversation in the rail, move focus to
the composer, and announce to assistive technology that the conversation with that person is
open.

IF a creation request fails,
THEN THE SYSTEM SHALL keep the dialog open, state the failure in words, allow another
attempt, and add no conversation to the rail.

WHILE a creation request is in flight,
THE SYSTEM SHALL indicate progress and prevent a duplicate submission for the same person.

WHEN a conversation-created event arrives for a conversation the rail does not list,
THE SYSTEM SHALL add it to the rail with an empty-history preview, without changing the
user's active conversation.

## frontend: rail previews and presence

WHEN a conversation's most recent message exists,
THE SYSTEM SHALL summarise it in that conversation's rail row using the server-provided
preview.

WHEN a conversation has no messages,
THE SYSTEM SHALL state in its rail row that there are no messages yet.

WHEN the current user's newest message in a conversation is awaiting acknowledgement or was
rejected,
THE SYSTEM SHALL let that client-side state override the preview wording as sending or not
sent.

WHEN a message is acknowledged or received for a conversation,
THE SYSTEM SHALL update that conversation's rail preview without refetching the listing.

WHILE a user is online or offline,
THE SYSTEM SHALL mark that user's rail rows accordingly using text in addition to any colour.

WHEN a presence snapshot arrives,
THE SYSTEM SHALL replace its online set wholesale rather than merging it.

## frontend: connection state

THE SYSTEM SHALL display the current realtime connection state at all times while a user is
signed in, including while connected, in words and never by colour alone.

WHILE realtime messaging is unavailable,
THE SYSTEM SHALL state inside the conversation that messaging is unavailable and reconnection
is being attempted, keep the composer usable with waiting copy, and mark each waiting message
as waiting for connection rather than sending.

WHILE realtime messaging is unavailable,
THE SYSTEM SHALL offer a control that cancels the scheduled delay and reconnects at once;
activated while connected or after an intentional close, it shall do nothing.

WHEN realtime messaging becomes available after being unavailable,
THE SYSTEM SHALL confirm the recovery in the conversation and withdraw that confirmation
without user action.

WHEN the connection is restored,
THE SYSTEM SHALL send each waiting message with its original correlation token and
acknowledge each exactly once.

WHILE a message is shown as rejected,
THE SYSTEM SHALL offer a control to re-submit it under its original correlation token, and
shall never re-submit it automatically.

WHEN a correlated error arrives for a submitted command,
THE SYSTEM SHALL retain that error's code and reason against the message and word the
rejection from the code, not the reason text.

THE SYSTEM SHALL NOT include a control that intentionally severs the connection in a
production build.

## frontend: info drawer

THE SYSTEM SHALL present an accessibly named information affordance with every message,
including the user's own pending messages, and a thread-header control that opens the drawer
with no message selected to show connection and protocol facts.

WHEN the user activates a message's information affordance,
THE SYSTEM SHALL open the inspector on that message's record, distinguish that message
visually, and expose that affordance as expanded.

WHEN the inspector closes,
THE SYSTEM SHALL return focus to the control that opened it, or to the thread-header control
if the originator is gone.

WHILE the viewport is at or above the wide-layout threshold,
THE SYSTEM SHALL dock the open inspector beside the thread; below the threshold it shall
overlay the thread with a scrim and confine focus.

WHEN the viewport crosses the threshold while the inspector is open,
THE SYSTEM SHALL present it in the other mode, retaining the inspected message.

WHERE the inspector shows a message's record,
THE SYSTEM SHALL present the ordered transport steps with their observed timestamps, the
message identifier, the correlation token from the message itself, the conversation and
sender identifiers, server and (for own messages) client creation times, the ordering key,
the content length against the documented maximum, and the associated realtime protocol
frames using the shapes defined by the API specification.

THE SYSTEM SHALL derive every value the inspector presents from data the client observed or
holds, and shall render any unobserved value as absent.

IF no transport record is held for the inspected message,
THEN THE SYSTEM SHALL state that its transport was not observed in this session, rather than
presenting its steps as absent values.

WHILE the inspector shows an acknowledged message,
THE SYSTEM SHALL state that acknowledgement confirms persistence, not delivery or reading,
and shall present fan-out as unverified.

WHEN a message being inspected is acknowledged,
THE SYSTEM SHALL continue showing its record against its server identity without closing the
inspector.

WHILE the inspector is open,
THE SYSTEM SHALL update its contents as the underlying state changes, and shall bound the
number of retained transport records, discarding oldest first.

WHEN the user selects a different conversation or the current user changes,
THE SYSTEM SHALL clear the inspected message selection.

## frontend: shell and appearance

THE SYSTEM SHALL present the signed-in application as an accessibly named header above a
conversation rail and a message thread, keeping all existing loading, error, retry, and empty
states.

WHEN a conversation is selected,
THE SYSTEM SHALL present the other participant's initials, name, a supporting line, and the
inspector control at the head of the thread.

WHEN a message is displayed,
THE SYSTEM SHALL state its delivery status in words, and shall visually distinguish own
messages from received ones without relying on colour alone.

WHILE the user is reading earlier messages and a message arrives,
THE SYSTEM SHALL NOT change the user's reading position.

WHEN the user submits a message,
THE SYSTEM SHALL position the thread to show that message, trimmed at its boundaries only.

IF the composer's content is empty or whitespace only,
THEN THE SYSTEM SHALL make submission unavailable.

THE SYSTEM SHALL present a light and a dark form, defaulting to the operating system
preference and persisting an explicit override.

WHILE the viewport is below the compact threshold,
THE SYSTEM SHALL present the rail as an overlay dismissed by selection, dismiss key, or
scrim; opening any overlay shall dismiss any other.

THE SYSTEM SHALL offer a skip-to-conversation link, announce conversation, identity, and
delivery-status changes to assistive technology, show a visible focus indicator on every
operable control, and honour reduced-motion preferences.

## verification

THE SYSTEM SHALL have an integration test proving that concurrent creation requests for the
same pair yield exactly one persisted conversation and two successful responses.

THE SYSTEM SHALL have an integration test proving that the conversation-created event reaches
the other participant's connections and not the creator's.

THE SYSTEM SHALL have an integration test proving that a user holding two connections is not
announced offline when one closes, and is announced offline when the last closes.

THE SYSTEM SHALL have a test proving that a connection removed by a delivery failure changes
that user's announced presence.

THE SYSTEM SHALL have a test proving that a presence set delivered to a recipient contains no
user with whom that recipient shares no conversation.

THE SYSTEM SHALL have a test proving that a conversation's preview matches the last message
of its history under the documented ordering.

THE SYSTEM SHALL have a test proving that the correlation token survives a reload for both
sent and received messages.

THE SYSTEM SHALL have frontend tests covering dialog focus confinement and focus return,
inspector focus return, presence snapshot replacement rather than merge, and the pending or
failed preview override.

WHEN the specification changes,
THE SYSTEM SHALL have a check that fails if the specification document does not parse as a
valid OpenAPI document.

## out of scope

THE SYSTEM SHALL NOT display read receipts, delivery receipts, typing indicators, or unread
counts.

THE SYSTEM SHALL NOT support message editing, deletion, or reactions.

THE SYSTEM SHALL NOT create group conversations.

THE SYSTEM SHALL NOT introduce presence deltas, sequence numbers, or history cursors.

THE SYSTEM SHALL NOT introduce an in-browser substitute for the backend.

---

# Implementation order

Vertical slices. Each one is independently shippable and independently demonstrable, so
running out of time truncates the feature list rather than leaving a half-wired layer.

| # | Slice | Size | Demonstrable at the end |
| --- | --- | --- | --- |
| 0 | **Contract + seed.** Five additions into `docs/openapi.yaml`; three new users, one new seeded conversation with history, `pair_key` backfill in `DataSeeder`. | S | Alice's rail has two conversations; three users are reachable but unmessaged. |
| 1 | **Shell re-skin.** Header, rail rows, thread header, message meta line, dividers, composer, theme, mobile overlay, skip link, announcer — every existing state preserved. | L | The app looks like the one-shot. |
| 2 | **Conversation creation.** `pair_key` + endpoint + `CONVERSATION_CREATED` backend-side; `+` dialog frontend-side. | M | Alice starts a conversation with Dan; Dan's rail updates live in the other window. |
| 3 | **Rail previews.** `Conversation.lastMessage` + preview rendering with the pending/failed override. | S | Rail rows show last messages; sending updates them without a refetch. |
| 4 | **Presence.** Registry transition listener across all three paths, scoped snapshots, rail dots with words. | M | Closing one of Bob's two windows does nothing; closing the second greys him out for Alice only. |
| 5 | **Connection state.** `reconnectNow()`, pill, banner, offline composer copy, recovery confirmation, `DEV` drop button. | M | Drop the connection, watch queued messages flush under their original tokens. |
| 6 | **Info drawer.** `clientMessageId` on `Message`, `onCommandSent`, transport ledger, `InspectTarget`, docked/overlaid inspector. | L | Any message's full transport record, including received ones, surviving a reload. |

**Cut from the bottom.** If time runs short, slices 5 and 6 are the ones to drop — but note
that slice 6 is the strongest differentiator in the whole plan and the best material for the
follow-up interview. Preferred order of sacrifice: the theme toggle and mobile overlay inside
slice 1, then slice 5's recovery confirmation, then slice 6 entire. Do not cut slice 0 —
without it slices 2 through 4 cannot be demonstrated.

**Rollback is per-slice.** Every backend change is additive; every frontend change is
confined to components that already exist. Slice 6's `clientMessageId` is the only contract
change that existing clients could notice, and it is an added property on a schema that
already tolerates unknown properties in both directions.

---

# Known obstacles, deliberately unsolved

Recorded because the brief asks for foresight, not for a complete solution.

- **`X-User-Id` is not authentication.** Any client can claim any identity. Presence, the
  directory, and conversation creation all inherit that. It is the right MVP posture and the
  wrong production one; the fix is a session cookie or a signed token bound at the same
  filter, and none of this plan's shapes change when it lands.
- **`404` on an unknown `participantId` is a user-enumeration oracle.** Irrelevant against a
  five-user seeded directory with a public `GET /api/users`; it stops being irrelevant the
  moment the directory is real and the endpoint is not.
- **Presence is single-instance.** `ConnectionRegistry` is in-memory, so a second backend
  instance would report only its own connections as online. The same limitation already
  applies to message fan-out. The fix is a shared registry or a pub/sub fan-out layer, both
  explicitly excluded from the MVP.
- **The partner-set query runs on every transition.** Fine at this size, linear in
  transitions at any other. `CONVERSATION_CREATED` is the only event that invalidates a
  partner set, which makes the cache easy when it is needed.
- **`lastMessage` is computed per listing.** For a small number of conversations this is a
  correlated subquery and nothing more. At scale it becomes a denormalized
  `conversation.last_message_id` updated in the message-write transaction — which introduces
  a write-ordering concern this plan does not want yet.
- **The transport ledger is session-scoped and bounded.** Reloading loses it. This is
  correct — the alternative is persisting observed transport, which is a logging product, not
  a chat product — but it means the inspector is strictly less informative after a reload,
  and the UI says so rather than hiding it.
- **The one-shot HTML in `docs/one-shot/` will drift.** It is a design artifact, not a
  fixture, and nothing keeps it in step with the implementation once slice 1 lands.

---

# Open questions

1. **Is slice 6 in or out?** It is the largest slice and the highest-signal one. Deciding
   before slice 1 starts changes how much of the message row's meta line is worth building.
2. **Should `pair_key` be `NOT NULL` with a `docker compose down -v` in the runbook instead?**
   Nullable-plus-backfill is chosen here to keep boot non-destructive against an existing
   volume. If the demo environment is always recreated, `NOT NULL` is simpler and stronger.
3. **Should presence scoping be relaxed to the full directory for the demo?** Scoped is more
   correct and more defensible; unscoped is roughly thirty fewer lines and makes the two-user
   demo identical either way. This plan chooses scoped; the reverse is defensible if slice 4
   is under time pressure.
4. **Does the recovery confirmation withdraw on a timer or on the next user action?** A timer
   is simpler and is what the one-shot implies; the next-action variant is friendlier to
   screen-reader users, who may not have heard it yet when it disappears.
5. **How many seeded messages in Alice↔Carol?** Three is enough to render a date divider only
   if they straddle a day boundary. If the divider matters for slice 1's review, they need
   back-dated timestamps across two days.
