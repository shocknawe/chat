# Chat frontend

React + TypeScript (strict) + Vite, with TanStack Query for REST server-state
caching. Part of the `add-realtime-messaging-mvp` change — see
`openspec/changes/add-realtime-messaging-mvp/` at the repo root for the full
design and task breakdown.

## Development

```bash
npm install
npm run dev
```

The dev server runs on `http://localhost:5173` and proxies `/api` (REST) and
`/ws` (WebSocket) to the backend at `VITE_BACKEND_ORIGIN`
(default `http://localhost:8080`; see `.env.example`).

## Scripts

- `npm run dev` — start the Vite dev server (with proxy).
- `npm run build` — type-check (`tsc -b`) then production build.
- `npm run preview` — preview the production build locally.
- `npm run lint` — run Oxlint.

## Docker

Run as part of the full stack from the repo root:

```bash
docker compose up
```
