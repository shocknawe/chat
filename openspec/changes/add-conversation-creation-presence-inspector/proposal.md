# Proposal: add-conversation-creation-presence-inspector

## Why

The realtime chat MVP shipped with Alice and Bob already conversing, so there is no way to
start a new conversation, no way to see who is online, no message previews in the rail, and
no way to inspect the transport behaviour the app is built to demonstrate. The frontend has
also not caught up to the approved design (`docs/one-shot/oneshot-info-drawer.html`). This
change brings the React app to parity with that design — backed by real endpoints, not
fixtures — via five additive backend additions (`POST /api/conversations`,
`CONVERSATION_CREATED`, `Conversation.lastMessage`, `PRESENCE`, `Message.clientMessageId`
exposure) and a shell re-skin.

## What Changes

- **Seed data first**: add Carol, Dan, and Erin to the directory; seed an Alice↔Carol
  conversation with three back-dated messages. Existing ids and names are unchanged;
  `DataSeeder` backfills `pair_key` on pre-existing rows. Without this, creation, previews,
  and presence are unreachable at runtime.
- **Conversation creation**: `POST /api/conversations` creates a 1:1 conversation
  idempotently (`201` new, `200` existing — never `409`), enforced by a nullable unique
  `pair_key` column (sorted UUID pair), with the concurrent-insert race resolved by re-read
  in a fresh transaction. After commit, `CONVERSATION_CREATED` is pushed to the *other*
  participant's connections only.
- **Conversation previews**: `Conversation.lastMessage` (latest by `(createdAt ASC, id ASC)`,
  absent when the history is empty — the service's global `non_null` Jackson inclusion means
  omitted rather than `null` unless the DTO opts out) returned by both the listing and
  creation responses; rail rows render from it with a client-side pending/failed override.
- **Presence**: online iff ≥1 open session in `ConnectionRegistry`; transitions detected
  across all three session-set mutation paths (`register`/`unregister`/`evict`) inside the
  registry lock but broadcast outside it; `PRESENCE` snapshots scoped to each recipient's
  conversation partners, sent wholesale (never merged) through a single-threaded dispatcher
  so wholesale replacement cannot apply a stale snapshot, and sent as a new socket's first
  event.
- **Message correlation token**: `clientMessageId` (already stored non-null) is now exposed
  in REST history and in `NEW_MESSAGE`/`MESSAGE_ACK`, reversing the previously documented
  deliberate omission.
- **Frontend**: `+` creation dialog (focus-confined, error states stay in-dialog), rail
  previews and presence indicators (text plus colour), connection-state pill with
  `reconnectNow()` and offline banner, a DEV-gated drop-connection control stripped from
  production builds, an info drawer (transport ledger, docked ≥980px / overlaid below,
  "observed or absent" honesty rule), and a full shell re-skin preserving every existing
  loading/error/retry/empty state.
- **Contract first**: all five backend additions land in `docs/openapi.yaml` before
  implementation; the reversed `clientMessageId` decision is re-recorded with its reason,
  not deleted.
- **Deliberately not doing**: no in-browser mock backend, no Flyway/Liquibase (all schema
  changes are additive under `ddl-auto: update`), no read receipts/typing indicators/group
  conversations/presence deltas.

## Capabilities

### New Capabilities

- `demo-seed-data`: expanded seeded directory (Carol, Dan, Erin), seeded Alice↔Carol history,
  step-wise idempotent seeding, non-destructive boot against previously seeded databases,
  `pair_key` backfill.
- `conversation-creation`: idempotent 1:1 creation endpoint with database-enforced pair
  uniqueness (asserted at boot, since `ddl-auto: update` may not add a unique constraint to
  an existing table), `CONVERSATION_CREATED` post-commit event, and the frontend `+` dialog
  with focus confinement, error handling, and duplicate-submit guard.
- `conversation-previews`: `Conversation.lastMessage` in listing and creation responses;
  rail rows render server previews with client pending/failed override, updated on
  ack/new-message without refetch.
- `presence`: registry-driven online state across all connection-set mutation paths, scoped
  snapshot events (first event on connect, ordered wholesale replacement), multi-connection
  safety, no I/O or delivery under the registry lock, frontend indicators in words plus
  colour.
- `connection-state`: always-visible connection pill, offline banner with `reconnectNow()`,
  waiting-message wording, transient recovery confirmation, correlated-error wording from
  error code, manual (never automatic) retry of rejected messages under their original token,
  single-live-socket guarantee, DEV-gated drop-connection control.
- `message-inspector`: info affordance per message and thread-header control, docked/overlaid
  drawer with mode re-presentation across the layout threshold, bounded session-scoped
  transport ledger, honest rendering of unobserved values.
- `app-shell`: re-skinned header/rail/thread/composer to the approved one-shot design,
  theme toggle (light/dark, OS default, persisted override), mobile slide-over rail, skip
  link, live-region announcer, visible focus, reduced-motion support.

### Modified Capabilities

- `message-persistence`: the stored `clientMessageId` is now exposed in history responses
  and realtime events (including for messages sent by the other participant), while
  remaining a per-sender idempotency key and never the authoritative identifier.

## Impact

- **API**: `docs/openapi.yaml` gains a creation endpoint, two events, and two schema
  properties; the `Message` schema's deliberate-omission rationale is replaced, along with
  the three other places asserting that omission (the `MESSAGE_ACK`/`ERROR` correlation note,
  and the `MessageDto` and `OutboundEvent` KDoc). An OpenAPI parse check is added, and
  not-yet-implemented elements carry `x-status: planned` until their slice lands.
- **Backend (Kotlin/Spring)**: `Conversation` entity (`pairKey`, `lastMessage` assembly),
  `ConversationService`/controller, `DataSeeder`, `ConnectionRegistry` (transition
  listener), realtime handler (`PRESENCE`, `CONVERSATION_CREATED`), DTOs (`clientMessageId`).
- **Database**: one additive nullable unique column; no migration tool; seeder backfills.
- **Frontend (React)**: `App.tsx`, `ConversationList.tsx`, `ThreadPane.tsx`, `Composer.tsx`
  re-skinned (state machines preserved); new dialog, drawer, presence, and connection-state
  components; `ChatSocket` gains `reconnectNow()` and `onCommandSent`.
- **Verification**: integration tests for concurrent creation, `CONVERSATION_CREATED`
  fan-out, multi-connection presence, eviction-driven presence (including eviction occurring
  *during* a presence broadcast), presence scoping, preview ordering, token-survives-reload;
  frontend tests for focus confinement/return, snapshot replacement, preview override, and
  manual-only retry. Constraint and seed behaviour are verified against a retained database
  volume, not only a freshly created schema.
- **Out of scope**: read/delivery receipts, typing indicators, unread counts, message
  editing/deletion/reactions, group conversations, presence deltas/sequence numbers/history
  cursors, any in-browser backend substitute.
