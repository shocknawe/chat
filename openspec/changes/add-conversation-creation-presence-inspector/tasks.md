# Tasks: add-conversation-creation-presence-inspector

Vertical slices per `design.md` decision 8. Each slice is independently shippable; slice 0
is never cut (slices 2–4 depend on it to be demonstrable).

## 1. Slice 0 — Contract and seed data

- [x] 1.1 Add `POST /api/conversations` (request/response, 200/201/400/401/404 semantics) to `docs/openapi.yaml`, documenting explicitly that `404` on this endpoint means the named `participantId` is not a directory user — distinct from `404` on `/conversations/{id}/messages`, which means the conversation does not exist
- [x] 1.2 Add the `CONVERSATION_CREATED` and `PRESENCE` events to `docs/openapi.yaml`
- [x] 1.3 Add `lastMessage` to the `Conversation` schema in `docs/openapi.yaml` as `nullable: true`, NOT listed under `required` (see 4.0 — the global `non_null` Jackson setting means the property is omitted, not null, for an empty history)
- [x] 1.4 Add `clientMessageId` to the `Message` schema in `docs/openapi.yaml`, replacing the "deliberately NOT" rationale paragraph with the reason for the reversal (do not delete the record of the original decision). Update the three *other* places that assert the omission so they cannot drift: the `MESSAGE_ACK`/`ERROR` correlation note in `docs/openapi.yaml`, the `MessageDto` KDoc, and the `OutboundEvent` KDoc
- [x] 1.5 Add a check that fails if `docs/openapi.yaml` does not parse as a valid OpenAPI document
- [x] 1.5a Mark each newly documented element `x-status: planned` in `docs/openapi.yaml`, and make removing that marker part of the slice that implements it (slice 2 for the endpoint and `CONVERSATION_CREATED`, slice 3 for `lastMessage`, slice 4 for `PRESENCE`, slice 6 for `clientMessageId`), so a partially shipped change never leaves the document asserting behaviour that does not exist
- [x] 1.5b Restructure `DataSeeder.run` into independent find-or-create steps per entity, removing the early `return` that skips all later seeding once the original conversation exists — otherwise none of 1.6–1.8 runs against an existing database
- [x] 1.6 Expand `SeedData.kt` with Carol, Dan, and Erin (new ids; existing ids and names unchanged)
- [x] 1.7 Seed the Alice↔Carol conversation with three messages using deterministic `clientMessageId`s and back-dated timestamps straddling a day boundary; leave the seeded Alice↔Bob conversation empty (the empty-history preview and empty-thread states depend on it)
- [x] 1.8 Add `pairKey` backfill to `DataSeeder` for pre-existing 1:1 conversations lacking one
- [x] 1.9 Verify boot against a previously seeded database (retained Docker volume) adds the new data without recreation

## 2. Slice 1 — Shell re-skin

- [x] 2.1 Re-skin the header (accessible name) in `App.tsx`
- [x] 2.2 Re-skin rail rows with avatars in `ConversationList.tsx`
- [x] 2.3 Re-skin the thread header (initials, name, supporting line, inspector control placeholder) and per-message rows (meta line, date dividers, empty thread) in `ThreadPane.tsx`
- [x] 2.4 Re-skin `Composer.tsx` (auto-growing, whitespace-only submission unavailable)
- [x] 2.5 Add theme toggle: light/dark, OS-preference default, persisted override
- [x] 2.6 Add mobile slide-over rail (dismissed by selection, dismiss key, or scrim; overlays mutually exclusive)
- [x] 2.7 Add skip-to-conversation link, live-region announcer, visible focus indicators, reduced-motion support
- [x] 2.8 Verify every pre-existing loading, error, retry, and empty state still functions, and that bottom-anchoring still holds after the scroll container is re-skinned: a realtime arrival does not move a scrolled-up reader, and a submission positions the thread on the new message

## 3. Slice 2 — Conversation creation

- [x] 3.1 Add nullable `pairKey` to the `Conversation` entity (sorted-UUID pair, e.g. `"1111…:2222…"`) with an explicit *table-level* unique constraint (`@Table(uniqueConstraints = [...])`, named `uq_conversation_pair_key`), mirroring the proven `Message` pattern — not a bare `@Column(unique = true)`
- [x] 3.1a Assert at boot that `uq_conversation_pair_key` exists, failing fast if it does not: `ddl-auto: update` is not guaranteed to add a unique constraint to a table that already exists, and without the constraint the race defence in 3.3 silently does nothing
- [x] 3.1b Run 3.1a's check against a database created *before* this change (retained volume), not only against a freshly created schema
- [x] 3.2 Implement `POST /api/conversations` in service/controller: implicit caller, 201 create / 200 existing, 400/401/404 rejections
- [x] 3.3 Handle the concurrent-create race: put the transactional insert in its own Spring bean (mirroring `MessageWriter`) so the catch and re-read genuinely run outside the rolled-back transaction; `saveAndFlush` so the violation surfaces synchronously; catch `DataIntegrityViolationException` at the service boundary and re-read in a fresh transaction, returning 200
- [x] 3.4 Emit `CONVERSATION_CREATED` after commit to every active connection of the other participant only
- [x] 3.4a Extend `api.ts` `request()` to carry a method and JSON body (it is GET-only today), and add `createConversation(userId, participantId)` returning the created-or-existing conversation
- [x] 3.5 Build the `+` dialog: candidate list (directory minus caller and existing partners), exhausted-state copy
- [x] 3.6 Confine focus in the dialog; return focus to the `+` button on dismissal
- [x] 3.7 On success: close, select conversation, move focus to composer, announce; on failure: keep open with reason in words, no rail mutation
- [x] 3.8 Add in-flight duplicate-submission guard per person
- [x] 3.9 Handle incoming `CONVERSATION_CREATED`: add to rail with empty preview without changing the active conversation
- [x] 3.10 Integration test: concurrent creation for the same pair yields exactly one persisted conversation and two successful responses
- [x] 3.11 Integration test: `CONVERSATION_CREATED` reaches the other participant's connections and not the creator's

## 4. Slice 3 — Rail previews

- [x] 4.0 Decide and record the wire form of an empty preview. `spring.jackson.default-property-inclusion: non_null` means a null `lastMessage` is **omitted from the JSON**, not serialised as `null`. Either annotate the conversation DTO so the property is always emitted, or accept omission — then make `docs/openapi.yaml` (1.3) and the frontend types say the same thing, and have the frontend treat absent and null identically *(decision: omission accepted — `ConversationDto.lastMessage: MessageDto?` carries no `@JsonInclude` opt-out, so an empty history is an omitted property, never a literal `null`; recorded in the DTO's KDoc and mirrored in `docs/openapi.yaml`, where `lastMessage` is an optional, non-nullable `Message` (no `nullable: true`, which was OpenAPI 3.0-style anyway) with `x-status: planned` removed. Frontend: type `lastMessage?: Message` and treat absent === null)*
- [x] 4.1 Assemble `Conversation.lastMessage` (latest by `createdAt ASC, id ASC`, empty when there is no history) in listing and creation responses via one `findFirstByConversation_IdOrderByCreatedAtDescIdDesc` per conversation (served by the existing `(conversation_id, created_at, id)` index as a backward scan), and add an `@EntityGraph` on `findByParticipants_Id` for `participants` — the listing already does an N+1 on participants today, and this change makes N grow
- [x] 4.2 Render rail previews from `lastMessage`, with "no messages yet" for empty histories
- [x] 4.3 Apply the pending/failed client-side override over the server preview
- [x] 4.4 Update previews on acknowledgement and new-message events without refetching the listing
- [x] 4.5 Test: a conversation's preview matches the last message of its history under the documented ordering *(repository-level: `MessagePersistenceIntegrationTest` pins `findFirstByConversation_IdOrderByCreatedAtDescIdDesc` against the ASC-ASC history, including a createdAt tie and the empty case; wire-level: `ConversationPreviewApiIntegrationTest` pins the listing preview to the history's tail field-for-field, the omitted-property empty form in 201/200/listing, and the 200 already-existed body matching the listing)*
- [x] 4.6 Frontend test: the pending/failed preview override

## 5. Slice 4 — Presence

- [x] 5.1 Detect the empty↔non-empty edge for a user *inside* the `sessionsByUser.compute*` lambda (covering `register`, `unregister`, and `evict`) by capturing before/after emptiness into a local. Perform **no** repository call, `send`, or `sendToUser` inside the lambda: `ConnectionRegistry.send` evicts on failure and `evict` re-enters `computeIfPresent` on the same map, which `ConcurrentHashMap` forbids (`IllegalStateException: Recursive update`, or a bin-lock deadlock across keys)
- [x] 5.1a Break the registry↔broadcaster constructor cycle explicitly: publish a presence-transition signal (`ApplicationEventPublisher`, or a listener injected via `ObjectProvider`/setter), so the broadcaster can call back into `registry.sendToUser` without a circular bean dependency
- [x] 5.1b Make `evict` resolve the owning user from the registry's own index rather than `session.attributes`, so a session can never be dropped from `sessionsById` while remaining in `sessionsByUser` — today an unresolvable identity leaves that user permanently online
- [x] 5.2 After `compute*` returns, and only if a transition was observed, resolve the scoped partner set and broadcast outside the lock. Compute it in a `@Transactional(readOnly = true)` `PresenceService` using a single id-projection query (`select distinct p.id from Conversation c join c.participants p where …`): `open-in-view` is `false` and transitions fire on a WebSocket thread with no transaction, so touching the lazy `participants` association there throws `LazyInitializationException`
- [x] 5.2a Dispatch every presence broadcast through one single-threaded executor, so snapshots are computed and enqueued in a total order — wholesale replacement is only self-healing if the newest snapshot is also the last one delivered
- [x] 5.3 Send a newly connected socket its scoped presence snapshot as its first event, enqueued on that same executor and ordered before the session becomes eligible for transition broadcasts
- [x] 5.4 Handle `PRESENCE` in the frontend: replace the online set wholesale, never merge
- [x] 5.5 Render presence on rail rows as dot plus word (never colour alone)
- [x] 5.6 Integration test: a user holding two connections is not announced offline when one closes, and is announced offline when the last closes
- [x] 5.7 Test: a connection removed by delivery failure changes that user's announced presence, and the eviction triggered inside a presence broadcast does not deadlock or throw
- [x] 5.8 Test: a delivered presence set contains no user sharing no conversation with the recipient
- [x] 5.9 Frontend test: presence snapshot replacement rather than merge

## 6. Slice 5 — Connection state

- [x] 6.1 Add `reconnectNow()` to `ChatSocket`: cancel scheduled backoff and attempt immediately; no-op while connected, after `terminate()`, **and** while a connection attempt is already in flight (`scheduleReconnect` nulls `retryTimer` before calling `connect()`, so "no timer" does not mean "not attempting")
- [x] 6.1a Make `connect()` detach listeners from and close any prior socket before opening a new one — it currently reassigns `socket` without doing so, which can leave two live sockets, two registry entries for one user (presence reports online after the real one closes), and duplicate `NEW_MESSAGE` frames
- [x] 6.2 Add the always-visible connection pill (word plus dot, never colour alone)
- [x] 6.3 Add the in-thread offline banner, offline composer copy, and waiting-for-connection wording on queued messages
- [x] 6.4 Add the transient recovery confirmation (timer-based withdrawal)
- [x] 6.5 Add `dropConnection()` to `ChatSocket` — closes the underlying socket with a non-1000 code so the normal reconnect path engages and the pending queue survives (`terminate()` is the wrong primitive: it is irreversible and would end the demo rather than show recovery) — and gate only the *UI control* on `import.meta.env.DEV` so it is stripped from production builds
- [x] 6.6 Retain correlated error code and reason against the message; word rejections from the code, not the reason text
- [x] 6.7 Verify queued messages flush under original `clientMessageId`s on restore, each acknowledged exactly once
- [x] 6.8 Add a retry control on rejected messages that re-submits under the original `clientMessageId` and never re-submits automatically (today a failed bubble is deliberately never retried and never removed; `chatSocket` drops the queue entry on a correlated `ERROR`, so re-calling `sendMessage` with the same token is not deduped)
- [x] 6.9 Test: a rejected message is not re-submitted without user action, and manual retry reuses the original client message identifier

## 7. Slice 6 — Info drawer and correlation token

- [x] 7.1 Add `clientMessageId` to the `Message` DTO in REST history and in `NEW_MESSAGE`/`MESSAGE_ACK` payloads. Keep `MessageAck.clientMessageId` at the event level (existing consumers correlate on it) and document in `docs/openapi.yaml` that for `MESSAGE_ACK` it is redundant with `message.clientMessageId`, retained for compatibility
- [x] 7.2 Add the `onCommandSent` instrumentation hook and the bounded session-scoped transport ledger (discard oldest first)
- [x] 7.3 Define the `InspectTarget` union (message vs connection/protocol view)
- [x] 7.4 Add the per-message ⓘ affordance (including own pending messages) and the thread-header inspector control; expose expanded state; distinguish the inspected message
- [x] 7.5 Implement the drawer: docked at ≥980px, overlaid with scrim and focus confinement below; re-present (not close) across the threshold, retaining the inspected message
- [x] 7.6 Return focus to the originating control on close (thread-header control if the originator is gone)
- [x] 7.7 Render the record: transport steps with observed timestamps, ids, correlation token, server/client timestamps, ordering key, content length against the documented maximum, protocol frames per the spec
- [x] 7.8 Implement the honesty rules: unobserved field renders absent; missing transport record states "not observed in this session"; acknowledgement stated as persistence (not delivery/reading), fan-out unverified
- [x] 7.9 Keep the drawer open and live-updating when the inspected message is acknowledged; clear the selection on conversation or identity change
- [x] 7.10 Test: the correlation token survives a reload for both sent and received messages
- [x] 7.11 Frontend tests: inspector focus return; dialog focus confinement and focus return

## 8. Final verification

- [x] 8.1 Run the full backend test suite and frontend test suite
- [x] 8.2 Run `openspec validate add-conversation-creation-presence-inspector --strict` and resolve any findings
- [ ] 8.3 Verify the app under `docker compose up` end-to-end: create a conversation as Alice with Dan, observe Dan's rail update live in a second window, close Bob's windows one at a time and observe that only Alice is notified of the change, drop the connection and watch queued messages flush under original tokens
