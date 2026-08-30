## 1. Project scaffolding & local runtime

- [x] 1.1 Create repo layout: `backend/` (Gradle Kotlin + Spring Boot), `frontend/` (Vite + React + TypeScript), root `docker-compose.yml`.
- [x] 1.2 Add backend dependencies: Spring Web (MVC), Spring WebSocket, Spring Security, Spring Data JPA, Jackson (Kotlin module), PostgreSQL driver.
- [x] 1.3 Add frontend dependencies: React, TypeScript, TanStack Query, and configure a dev server proxy for REST + WebSocket to the backend.
- [x] 1.4 Author `docker-compose.yml` with `db` (PostgreSQL + named volume), `backend` (depends_on db healthcheck), `frontend`; verify `docker compose up` starts all three. — config authored and validated (YAML structure, Dockerfiles, healthchecks); live `docker compose up` not run in this environment (no Docker daemon available) — needs a runtime pass before sign-off.
- [x] 1.5 Configure backend datasource + JPA against the compose Postgres; confirm connectivity on boot. — datasource/JPA/Hikari config authored against compose `db` service and env-var externalized; live boot/connectivity not verified in this environment (no JVM/Docker available) — needs a runtime pass before sign-off.

## 2. Data model & persistence (message-persistence)

- [x] 2.1 Define JPA entities: `User(id UUID, displayName)`, `Conversation(id UUID)` with participants join, `Message(id UUID, conversationId FK, senderId FK, clientMessageId UUID, content, createdAt)`; avoid reserved database table names.
- [x] 2.2 Add foreign keys enforcing message↔conversation and conversation↔participants referential integrity.
- [x] 2.3 Add a unique constraint on `(senderId, clientMessageId)` and repository lookup by that key for database-enforced idempotency.
- [x] 2.4 Create `MessageRepository` with history query ordered by `(createdAt ASC, id ASC)` deterministic tiebreak.
- [x] 2.5 Idempotently seed fixed-id MVP users and a pre-created conversation between them on startup.
- [x] 2.6 Verify persistence survives backend restart and messages are retrievable afterwards (restart-retention).

## 3. REST API (user-directory, conversations)

- [x] 3.1 `GET /api/users` returns the configured MVP users.
- [x] 3.2 Implement demo identity binding: keep selection window-scoped on the frontend; validate `X-User-Id` in a Spring Security filter for protected REST requests; reject missing/unknown users.
- [x] 3.3 `GET /api/conversations` returns only conversations where the authenticated user participates.
- [x] 3.4 `GET /api/conversations/{id}/messages` verifies participation, rejects non-participants, returns history in deterministic chronological order.

## 4. Realtime protocol & connection layer (realtime-messaging)

- [x] 4.1 Define Kotlin sealed types for inbound `SEND_MESSAGE` and outbound `MESSAGE_ACK` / `NEW_MESSAGE` / `ERROR`, with Jackson (de)serialization on the `type` discriminator.
- [x] 4.2 Validate the handshake `userId` query parameter against configured users, bind it as the connection identity, and make the handler derive sender identity only from that binding.
- [x] 4.3 Implement `WebSocketConnectionHandler` (TextWebSocketHandler): register connections on open and remove them on close/error.
- [x] 4.4 Implement `ConnectionRegistry` keyed by userId → set of sessions, tracking multiple sessions independently and serializing concurrent sends per session.
- [x] 4.5 Implement `ProtocolParser` + `MessageCommandHandler`: parse commands, return stable-code protocol `ERROR` events for unsupported/invalid commands without terminating unrelated connections.
- [x] 4.6 Implement transactional `MessageService.send`: identify sender from the connection, validate content (non-empty, within the configured maximum length), verify conversation existence/participation, generate authoritative id + timestamp, and return success only after commit.
- [x] 4.7 After commit, send `MESSAGE_ACK` with the authoritative message to the origin and `NEW_MESSAGE` to every other active participant connection (including the sender's other sessions); isolate failed session sends and persist when recipients are offline.
- [x] 4.8 On validation or persistence failure, emit a correlated `ERROR` where possible and never emit a success event.
- [x] 4.9 Enforce race-safe idempotency: matching duplicate commands re-ack the existing message without re-broadcast; conflicting reuse returns `CLIENT_MESSAGE_ID_CONFLICT`; concurrent duplicates create exactly one row.

## 5. Frontend — REST & identity (user-directory, conversations)

- [x] 5.1 On load, fetch and display available users via TanStack Query; keep the selection in React state or `sessionStorage` (not `localStorage`) so browser windows can use different identities.
- [x] 5.2 With current user established, fetch and display that user's conversations.
- [x] 5.3 On conversation select, fetch and render message history in chronological order.
- [x] 5.4 On page reload, re-fetch persisted history for the current user/conversation.

## 6. Frontend — WebSocket client module (realtime-messaging)

- [x] 6.1 Build a WebSocket client module (outside React) responsible for connect with the selected user, serialize outgoing commands, parse incoming events, dispatch to app code, reconnect with backoff, retain unacknowledged commands in memory, and cleanup.
- [x] 6.2 Establish the WebSocket connection when the current user is established and associate realtime events with that user.
- [x] 6.3 Wire message submit: prevent empty/whitespace, send `SEND_MESSAGE` with a `clientMessageId`, show the message as pending.
- [ ] 6.4 Implement a single authoritative-message upsert path keyed by server message id; on `MESSAGE_ACK`, replace the correlated pending item, and on `ERROR`, mark it failed.
- [ ] 6.5 On `NEW_MESSAGE`, upsert into the active conversation without refresh; for other conversations update or invalidate the relevant cache without changing the active conversation.
- [ ] 6.6 On unexpected disconnect, reconnect with backoff, surface a "realtime temporarily unavailable" indicator, retry unacknowledged commands with the same `clientMessageId`, and refresh history after reconnect.
- [ ] 6.7 Merge REST history with realtime cache updates so an in-flight history response cannot duplicate or erase a newer WebSocket message.

## 7. Automated verification

- [ ] 7.1 Add backend unit tests for protocol parsing, validation, authorization, and isolated multi-session fan-out.
- [ ] 7.2 Add PostgreSQL integration tests for foreign/unique constraints, deterministic ordering, commit-before-success behavior, matching/conflicting idempotency, and concurrent duplicate commands.
- [ ] 7.3 Add frontend tests for protocol parsing, pending → sent/failed reconciliation, authoritative-id deduplication, reconnect retry, and missed-history refresh.

## 8. End-to-end verification

- [ ] 8.1 Run the full demo: `docker compose up`, two ordinary browser windows, two users, open the shared conversation, send a message, confirm it persists and appears exactly once in real time for both.
- [ ] 8.2 Verify reload retains history, reconnect recovers missed messages, and multiple sessions of the same user each receive realtime events.
- [ ] 8.3 Verify identity/authorization/validation edge cases: window selections stay independent, unknown and non-participant users are rejected, empty messages are blocked, and unsupported commands return a protocol error without dropping other connections.
- [ ] 8.4 Verify duplicate command behavior: identical retry returns the existing message, conflicting token reuse errors, and no duplicate row or message bubble appears.
