## Context

This is a Senior Full Stack Engineer take-home: a working 1-to-1 private messaging MVP that runs entirely locally via Docker Compose with no external SaaS. The demo flow is two browser windows, two users, one conversation, a message that persists and arrives in real time, and history that survives reload.

The stack is fixed by the plan: React + TypeScript + TanStack Query + native WebSocket on the frontend; Kotlin + Spring Boot (MVC REST, Spring WebSocket transport, minimal Spring Security, Jackson, JPA/Hibernate) on the backend; PostgreSQL as the durable source of truth. The core exercise is the application-owned realtime layer — deliberately *not* delegated to STOMP, a broker, Redis, or RabbitMQ.

## Goals / Non-Goals

**Goals:**
- Two communication paths: REST for queryable/historical state (users, conversations, history), WebSocket for realtime events (send, receive, ack/error).
- Backend owns all authoritative message creation: server-generated id and timestamp, persistence before acknowledgement.
- Explicit, self-implemented WebSocket application protocol with visible message routing, acknowledgements, and failure behaviour.
- Per-user, per-connection registry supporting multiple concurrent browser sessions.
- Optimistic frontend UX: pending → sent/failed, with a WebSocket client module isolated from React components.
- One-command local startup via Docker Compose.

**Non-Goals:**
- No RabbitMQ, Redis, STOMP, Spring Messaging broker, horizontal scaling, presence, read receipts, typing indicators, or file uploads.
- No OAuth/OIDC/JWT/external identity provider — Day-1 auth is deliberately minimal.
- No complex repository abstractions or reactive programming.

## Decisions

### Decision: Split transport by workload — REST for state, raw WebSocket for events
REST (Spring MVC) serves conventional request/response traffic (users, conversations, history); the native browser WebSocket API talks to a Spring WebSocket `TextWebSocketHandler`. TanStack Query owns REST server-state caching; it is **not** the WebSocket transport, though WebSocket events may invalidate or patch its caches.
- **Why:** The two workloads have different shapes; conflating them (e.g., forcing realtime through query polling) obscures the routing/ack/failure behaviour that is the point of the exercise.
- **Alternative considered:** STOMP over Spring Messaging — rejected because it hides the application protocol and is explicitly disallowed.

### Decision: Server-authoritative messages, client id is a correlation and idempotency token only
The client generates a UUID `clientMessageId` to correlate its optimistic pending message with the server acknowledgement and to make command retries idempotent. The server stores that token but generates the authoritative UUID `id` and `createdAt` timestamp, commits the message, then acknowledges it. A client token is never used as the authoritative message id or ordering key.
- **Why:** Guarantees a single source of truth for message identity/ordering while allowing a command whose acknowledgement was lost to be retried safely.
- **Alternative considered:** Using the client-generated id as the message id — rejected because message identity remains server-owned.

### Decision: Explicit protocol modelled as sealed Kotlin types / TS discriminated unions
Inbound commands and outbound events are modelled as a discriminated union on a `type` field, serialized with Jackson (Kotlin sealed classes) and mirrored by TypeScript union types.
- Client → server: `SEND_MESSAGE { clientMessageId, conversationId, content }`.
- Server → client: `MESSAGE_ACK { clientMessageId, message }`, `NEW_MESSAGE { message }`, `ERROR { clientMessageId?, code, reason }`.
- **Why:** Makes invalid protocol states hard to represent and keeps client/server contracts reasoned about explicitly.
- Realtime pipeline mirrors the plan: `WebSocketConnectionHandler → ProtocolParser → MessageCommandHandler → MessageService → PostgreSQL`, with `ConnectionRegistry` fanning out to recipient sessions.

The REST/WebSocket DTOs use the same authoritative `Message { id, conversationId, senderId, content, createdAt }` shape. `clientMessageId` is returned separately in an acknowledgement and remains an internal persistence/deduplication field. Protocol errors use stable codes (`INVALID_COMMAND`, `INVALID_CONTENT`, `CONVERSATION_NOT_FOUND`, `FORBIDDEN`, `CLIENT_MESSAGE_ID_CONFLICT`, `PERSISTENCE_ERROR`) so the frontend does not branch on human-readable `reason` text.

### Decision: In-memory ConnectionRegistry keyed by userId → set of sessions
A thread-safe registry (e.g., `ConcurrentHashMap<UserId, MutableSet<WebSocketSession>>`) tracks each connection independently; sessions are added on open and removed on close/error. Spring WebSocket sessions do not permit arbitrary concurrent sends, so each registered session is wrapped with `ConcurrentWebSocketSessionDecorator` (or protected by an equivalent per-session send lock). Failure to send to one stale session removes that session and does not abort delivery to other sessions.

After commit, the originating connection receives one `MESSAGE_ACK` containing the authoritative message. `NEW_MESSAGE` is sent to every *other* active connection belonging to either participant, including the sender's other browser sessions. This gives each connection one authoritative delivery path and avoids the origin rendering both an acknowledgement and its own broadcast.
- **Why:** Single-node MVP; an in-memory registry is the simplest correct design. No Redis pub/sub needed because there is no horizontal scaling.
- **Trade-off:** Not multi-node — acceptable and in-scope-excluded.

### Decision: Window-scoped demo identity with minimal Spring Security binding
`GET /api/users` is public. The frontend keeps the selected user in window-scoped state (optionally `sessionStorage`, never shared `localStorage`). Protected REST requests carry the selected id in `X-User-Id`; a Spring Security filter validates that the UUID belongs to a seeded user and creates the request `Authentication`. Because the native browser WebSocket API cannot set custom headers, the WebSocket handshake carries the same non-secret id as `?userId=...`; a handshake interceptor performs the same validation and assigns it to the connection `Principal`/attributes. The handler derives the sender only from that validated connection identity, never from command JSON.
- **Why:** This deliberately spoofable local-demo mechanism is small, uses Spring Security at the REST boundary, and lets two ordinary windows on the same origin select different users without fighting over a shared cookie.
- **Alternative considered:** A user-bound `JSESSIONID` cookie — rejected for the MVP demo because normal windows share cookies, preventing two identities from being demonstrated reliably in the same browser profile.
- **Trade-off:** This is identity selection, not production authentication. The header/query value is not a credential and must be replaced before deployment outside the local take-home environment.

### Decision: Data model and ordering
`User(id UUID, displayName)`, `Conversation(id UUID)` with a participants join, `Message(id UUID, conversationId FK, senderId FK, clientMessageId UUID, content, createdAt)`. Content is bounded by a configured maximum length, enforced in `SEND_MESSAGE` validation (rejected as `INVALID_CONTENT`) and mirrored by the column definition, so an oversized payload cannot reach persistence. History is ordered by `(createdAt ASC, id ASC)`; the UUID is not assumed to be chronological, only a stable deterministic tiebreaker when timestamps collide. Foreign keys enforce referential integrity between message↔conversation and conversation↔participants. Database table names avoid reserved identifiers (for example, `app_user`).
- **Why:** Deterministic ordering is a hard requirement; a stable secondary key removes ambiguity on timestamp ties.

### Decision: Idempotency for duplicate SEND_MESSAGE
Duplicate delivery is keyed by `(senderId, clientMessageId)` and enforced by a database unique constraint. If the existing row has the same `conversationId` and `content`, the server persists nothing new and returns `MESSAGE_ACK` with the existing authoritative message to the retrying connection, without re-broadcasting `NEW_MESSAGE`. Reuse of the token with different content or a different conversation returns `ERROR { code: "CLIENT_MESSAGE_ID_CONFLICT" }`; it never acknowledges the wrong message. The database constraint is the final arbiter for concurrent duplicates, not a check-then-insert performed only in memory.
- **Why:** Reconnect/retry can replay a command; this provides at-most-once logical creation even when duplicate commands race.

### Decision: Commit before acknowledgement or fan-out
Message creation runs in a transaction that validates membership and writes the message. The command handler emits `MESSAGE_ACK`/`NEW_MESSAGE` only after that transaction has committed successfully; a JPA `save` or flush alone is not treated as durable success. Constraint or commit failures become a correlated `ERROR` to the origin, and no success event is emitted.
- **Why:** Prevents clients observing a message that is later rolled back.

### Decision: Frontend cache reconciliation is idempotent
The WebSocket module owns connection state, keeps unacknowledged commands in memory, and retries them with the same `clientMessageId` after an unexpected reconnect. UI integration uses one `upsertMessage` path keyed by authoritative message `id` for REST history, `MESSAGE_ACK`, and `NEW_MESSAGE`; an acknowledgement replaces the correlated pending item. On reconnect, active conversation/history queries are invalidated so messages missed while offline are recovered. Cache merging must not let an in-flight history response erase a newer realtime event.
- **Why:** REST and WebSocket can race, and realtime delivery can be repeated. Idempotent reconciliation prevents duplicate bubbles and missed messages without turning TanStack Query into the transport.

### Decision: Docker Compose topology
Three services — `db` (PostgreSQL with a named volume for durability), `backend` (Spring Boot, depends_on db healthcheck), `frontend` (served build / dev server proxying REST + WS to backend). Schema and fixed-id seed users/conversation are applied idempotently on startup (JPA schema generation + seed is acceptable for this greenfield MVP).
- **Why:** Satisfies one-command local startup and restart-retention requirements.

## Risks / Trade-offs

- **In-memory registry lost on backend restart** → Acceptable: durable messages are recoverable via REST history; clients reconnect and reload history.
- **Native WebSocket has no built-in reconnect/backoff** → Mitigate with a small client module handling reconnect with backoff and surfacing an "unavailable" state to the UI.
- **JPA `ddl-auto` for schema in a take-home** → Acceptable for MVP; keep entities simple and seed deterministically. Revisit with migrations if scope grows.
- **Demo identity is trivially spoofable** → Clearly constrain it to local use and still enforce participant checks server-side on every REST request and every `SEND_MESSAGE`, so authorization logic is real even though authentication is not.
- **Fan-out to a participant's stale/closing session** → Mitigate by removing sessions on close/error and guarding sends; a failed send to one session must not affect other connections or the persisted message.
- **Backend restart loses active connections and in-memory pending state** → Clients reconnect and invalidate history; PostgreSQL remains authoritative. A full page reload may lose a pending bubble but recovers any committed message through history.

## Verification Strategy

- Backend unit tests cover parsing, validation, authorization, and fan-out isolation. PostgreSQL integration tests cover constraints, transaction/commit behavior, concurrent idempotency, restart-safe retrieval, and deterministic ordering.
- Frontend tests cover protocol parsing, pending → sent/failed transitions, authoritative upsert/deduplication, and reconnect retry using the same `clientMessageId`.
- The final smoke test runs the exact Docker Compose two-window demo and the negative authorization/protocol cases.

## Open Questions

None for the MVP. Conversations are pre-seeded with deterministic ids, and the local identity mechanism is fixed above.
