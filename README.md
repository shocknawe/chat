# Realtime Messaging MVP

A working, fully local real-time 1-to-1 messaging app — "a simple web-based WhatsApp
or Telegram." Pick a seeded demo identity, open a conversation with another user, and
send: messages persist, arrive over a WebSocket, and survive a reload for both windows.

## Demo

<video src="https://github.com/shocknawe/chat/raw/main/docs/screencap.mp4" controls muted width="720"></video>

Two windows, two seeded users, one conversation — messages land in both instantly and
survive a reload. If the player above doesn't load, watch it here:
[`docs/screencap.mp4`](docs/screencap.mp4).

## Stack

- **Frontend** — React + TypeScript (Vite), [TanStack Query](https://tanstack.com/query)
  for REST state, the browser's native WebSocket API for realtime.
- **Backend** — Kotlin + Spring Boot (web, websocket, data-jpa, actuator).
- **Database** — PostgreSQL 16.
- **Orchestration** — Docker Compose, entirely local.

## Quick start

Requires Docker (with Compose).

```bash
docker compose up --build
```

Then open **http://localhost:5173**. The backend serves on `:8080`
(`/actuator/health`) and Postgres on `:5432`.

To see the core loop: open two browser windows, pick two different seeded users, open
the conversation, and send. The message appears immediately in both windows and remains
after a reload.

### Custom ports

The Compose ports are overridable via environment variables — useful for running an
isolated second stack alongside the default one (the `chat-db-data` volume is shared):

```bash
FRONTEND_PORT=15173 BACKEND_PORT=18080 POSTGRES_PORT=15432 docker compose up
```

## Local development

Run the services outside Compose for a faster inner loop.

**Frontend** (`frontend/`):

```bash
npm install
npm run dev      # Vite dev server
npm test         # Vitest
npm run lint     # oxlint
```

**Backend** (`backend/`):

```bash
./gradlew bootRun   # requires a running Postgres
./gradlew test      # JUnit + Testcontainers
```

## Project layout

| Path                  | What's there |
| --------------------- | ------------ |
| `frontend/`           | React + TypeScript client |
| `backend/`            | Kotlin + Spring Boot service |
| `docs/openapi.yaml`   | REST API contract |
| `openspec/`           | Spec-driven change history (proposals, specs, tasks) |
| `docker-compose.yml`  | Local three-service stack |
| `PRODUCT.md`, `DESIGN.md`, `requirements.md` | Product, design, and requirements context |

## API

The REST contract is documented in [`docs/openapi.yaml`](docs/openapi.yaml). Realtime
messaging (send, acknowledgements, presence) runs over the WebSocket layer described in
the OpenSpec change specs under `openspec/`.
