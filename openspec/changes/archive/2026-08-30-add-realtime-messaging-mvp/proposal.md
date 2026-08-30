## Why

There is no way for two users to exchange private messages in real time within the product. This change delivers a working, fully local full-stack MVP that lets two users open a conversation, send a message, persist it durably, and deliver it to the recipient over WebSocket in real time — proving the core messaging loop end to end.

## What Changes

- Introduce a Dockerized, self-contained stack (React/TypeScript frontend, Kotlin/Spring Boot backend, PostgreSQL) runnable via a single `docker compose up`, with no external SaaS dependencies.
- Add a REST surface for queryable/historical state: available users, a user's conversations, and a conversation's message history — with participant-based authorization.
- Add an application-owned WebSocket realtime layer: connection lifecycle, a per-user connection registry, an explicit idempotent `SEND_MESSAGE` command protocol, server-authoritative message creation, per-connection acknowledgements/errors, and fan-out of new-message events to participants' active sessions.
- Add durable message persistence in PostgreSQL with server-generated identifiers and timestamps, referential integrity, restart retention, and deterministic chronological ordering.
- Add a frontend that selects a current user, lists conversations, renders chronological history, manages the WebSocket connection outside of components, shows optimistic pending/sent/failed message states, and reconnects on unexpected disconnect.
- Explicitly exclude production extensions: no RabbitMQ, Redis, STOMP, Spring Messaging broker, horizontal scaling, presence, read receipts, typing indicators, or file uploads.

## Capabilities

### New Capabilities
- `user-directory`: Exposes the fixed set of MVP users and lets the frontend establish a current application user identity.
- `conversations`: REST retrieval of a user's conversations and a conversation's persisted message history, gated by participant authorization and returned in deterministic chronological order.
- `realtime-messaging`: Application-owned WebSocket protocol covering connection lifecycle, per-user/per-session connection registry, `SEND_MESSAGE` command validation, server-authoritative message creation, acknowledgements/errors to the originator, and new-message delivery to participants' active connections.
- `message-persistence`: Durable PostgreSQL storage of messages with server-generated id and timestamp, referential integrity to conversations and participants, restart retention, at-most-once logical creation, and deterministic secondary ordering.

### Modified Capabilities
<!-- None. This is a greenfield MVP with no existing specs. -->

## Impact

- **New services**: `frontend/` (React + TypeScript + TanStack Query + native WebSocket), `backend/` (Kotlin + Spring Boot: MVC REST, Spring WebSocket transport, Spring Security request/connection identity binding, Jackson, JPA/Hibernate).
- **New infrastructure**: `docker-compose.yml` orchestrating frontend, backend, and PostgreSQL; database schema/seed for `User`, `Conversation`, `Message`.
- **APIs**: New REST endpoints (users, conversations, conversation history) and a WebSocket endpoint implementing the custom realtime protocol.
- **Dependencies**: Spring Boot, Spring Web/WebSocket/Security/Data JPA, Jackson, PostgreSQL driver; React, TypeScript, TanStack Query, build tooling.
- **Out of scope**: No message brokers, no OAuth/OIDC/JWT provider, no multi-node scaling.
