# One-shot design explorations

Static, self-contained HTML prototypes of the realtime chat UI. No build step, no
dependencies — open any file directly in a browser. Each one fakes the server
(pending → confirmed / failed, connection drop, retry) so the message lifecycle is
demonstrable without the backend running.

Kept here for posterity; these are references for the frontend, not shipped assets.

| File | Direction |
| --- | --- |
| [`variant-a-delivery-ledger.html`](variant-a-delivery-ledger.html) | **Delivery Ledger** — purple-black glass control room. Header over a conversation rail, thread centre, and a *permanent* third column recording every outbound message's transport state. |
| [`variant-b-conversation-canvas.html`](variant-b-conversation-canvas.html) | **Conversation Canvas** — the roomier, single-surface take. |
| [`variant-c-window-pair.html`](variant-c-window-pair.html) | **Window Pair** — crisp pale-blue/white planes, compact Lato, built to stay readable at half-screen width for the two-window demo. The cleanest chat window of the three. |
| [`oneshot-info-drawer.html`](oneshot-info-drawer.html) | **Info Drawer** — the combination: A's shell (header, conversation rail, panel docked right) wearing C's chat window, with A's ledger demoted from a permanent column to an **Info** drawer summoned by the ⓘ under any message. |

## Info Drawer — what to try

- **ⓘ under any message** opens Info on that message's record — lifecycle, identifiers,
  timing, and the actual wire frames. Press it again, or `Esc`, to close.
- The **list icon in the thread header** opens Info with no message selected, showing
  connection and protocol facts instead.
- The **Connected pill** beside your avatar drops the connection. Messages sent while
  offline queue as *Waiting for connection…* and their lifecycle shows *Held in the
  replay queue*; **Reconnect now** flushes them.
- The seeded failed message offers **Retry** in the bubble and **Send again** in Info.
- The **+ in the sidebar header** opens *New conversation* and offers whoever you are
  not already talking to. As Alice that's Charlie; as Bob the list is exhausted, so the
  dialog says so rather than showing an empty box.
- The **avatar chip** (top right, with the caret) opens the identity picker — identity
  is per-window. Theme toggle persists to `localStorage`.
- Each **rail row carries a presence dot** on the right: green for online, grey plus
  *Offline* otherwise. Dropping your own connection greys every row, since no presence
  signal is arriving. See the caveat below — this one is not backed by the repo.

Above 980px the drawer pushes the thread aside; below that it slides over with a
scrim and traps focus. Verified with no horizontal overflow from 320px to 1440px.

## What Info shows, and why

The panel is deliberately grounded in what this repo's backend actually produces. Every
field maps to real code:

| Info shows | Comes from |
| --- | --- |
| Lifecycle: queued → sent → committed → acknowledged → fanned out | `App.handleSendMessage` → `ChatSocket.sendMessage` → `MessageWriter.createAndCommit` → `MESSAGE_ACK` → `NEW_MESSAGE` fan-out |
| "Committed before acknowledged" | `MessageWriter` is a separate `@Transactional` bean; `CommitBeforeAckWebSocketIntegrationTest` asserts the row is visible to an *independent* JDBC connection before the ack arrives |
| Message id | `message.id` — server `UUID.randomUUID()`, explicitly not chronological |
| Client message id | `clientMessageId` — `crypto.randomUUID()`, the idempotency key behind `uq_message_sender_client_message_id` |
| Sender id "taken from the socket" | `MessageCommandHandler` passes the handshake-authenticated user; `senderId` is not a field on `SEND_MESSAGE` |
| Server `createdAt` | `Instant.now().truncatedTo(ChronoUnit.MICROS)`, UTC |
| Client `createdAt` | `new Date().toISOString()` in `usePendingMessages.addPending` — never persisted |
| Ordering key `(createdAt ASC, id ASC)` | `idx_message_conversation_created_at_id` and `compareMessages` |
| Wire frames | Verbatim shapes from `ws/protocol/InboundCommand.kt` and `OutboundEvent.kt` |
| Error codes | `ws/protocol/ErrorCodes.kt` — `INVALID_COMMAND`, `INVALID_CONTENT`, `CONVERSATION_NOT_FOUND`, `FORBIDDEN`, `CLIENT_MESSAGE_ID_CONFLICT`, `PERSISTENCE_ERROR` |
| "A correlated error is final" | `chatSocket.onMessage` deletes the pending command on any ERROR carrying a `clientMessageId`; it is never auto-retried |
| Reconnect backoff | `500 ms × 2ⁿ`, cap `10_000 ms`, `±30%` jitter, no attempt cap |
| History endpoint | `GET /api/conversations/{id}/messages` — full history, no cursor, merged by `message.id` with server rows winning |
| Max content | `MessagingLimits.MAX_CONTENT_LENGTH = 4000`, counted in **code points** |

Alice, Bob, and the Alice–Bob conversation use the real ids from `seed/SeedData.kt`
(that file calls them a public contract of the demo environment). Charlie and the other
two conversations follow the same pattern.

### What Info deliberately does *not* claim

The backend produces none of these, so the panel says so rather than inventing them:

- **No delivery receipt.** `MESSAGE_ACK` confirms server persistence only. The fan-out
  step is rendered as a hollow, unverified node — `ConnectionRegistry.sendToUser`
  swallows per-session failures, so the sender never learns the outcome.
- **No read receipts**, no `readAt`/`seenAt`, no READ event.
- **No sequence numbers, cursors, or offsets** — ordering is `(createdAt, id)` only.
- **No `clientMessageId` on inbound messages** — `NEW_MESSAGE` omits it and REST history
  has no such column, so a received message's client-id row reads `—`.
- **No status column in the database.** `pending`/`failed` live only in React state and
  are lost on reload.
- **No typing indicators, unread counts, edits, deletions, or reactions.**

### The one exception: presence

The green/grey dots in the conversation rail are **simulated** — they are the only thing
in this prototype not backed by the current stack. Worth noting, though, that the data
already exists server-side: `ConnectionRegistry` holds a
`ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>`, so the server knows exactly who
has an open session. What is missing is a wire event to publish it — `OutboundEvent` has
only `MESSAGE_ACK`, `NEW_MESSAGE`, and `ERROR`. Shipping this would mean adding a
presence event plus register/unregister broadcasts in `WebSocketConnectionHandler`.
Info's *Protocol* section states this outright rather than letting the dots imply a
capability that isn't there.
