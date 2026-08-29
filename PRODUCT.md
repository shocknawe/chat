# Product

<!-- impeccable:product-schema 1 -->

Design context only. The engineering spec lives in `plan.md` and `openspec/changes/add-realtime-messaging-mvp`.

## Platform

web

## Stack

React + TypeScript. TanStack Query for REST state, the browser's native WebSocket API for realtime. Kotlin + Spring Boot behind it, PostgreSQL, all of it under Docker Compose and fully local. Decided by the user in `plan.md`.

Product name: **undecided.** Working title "Realtime Messaging MVP". Don't invent one — ask.

## Users

**The assessment panel is the audience.** This is a Senior Full Stack Engineer take-home; the reviewer runs it, reads it, then interviews the author. Their read of craft and reasoning is the success metric.

**The in-product user** is a person holding a 1-to-1 conversation with one other person — "a simple web based WhatsApp or Telegram". In the MVP they're a seeded demo identity picked from a list, not an account-holder.

Where the two pull apart, the panel wins. The interface has to genuinely work, and it has to read as evidence.

## Product Purpose

A working, wholly local real-time 1-to-1 messaging app. Success is one demonstrable loop: start with Compose → open two windows → pick two users → open the conversation → send → it persists, arrives over WebSocket, and appears immediately for both → reload either window and the history is still there.

The UI's job is to make every step of that loop legible without narration.

## Positioning

**The server is the only author of a message.** Identity comes from the authenticated connection, the id and timestamp are generated server-side, and persistence happens before any success is reported.

That has a direct design consequence and it is the one engineering fact that matters here: the client holds a real three-state lifecycle — pending → confirmed, or pending → failed — and the interface must render that honestly rather than showing an optimistic local echo as truth. It is the product's central claim, and the thread is where a reviewer sees it.

## Operating Context

- **Two browser windows on one machine**, side by side, is the real usage scene. Design for it. The same user may also be signed in twice, and both sessions receive events.
- **A live interview follows**, where the author makes changes with no AI assistance. Everything shipped must be modifiable by a person under observation.
- **Time budget is 1–2 hours**, more optional. Prioritisation is part of what's assessed — scope cuts are stated, not hidden.

## Capabilities and Constraints

Four things exist on screen: **identity selection** (pick a seeded user), the **conversation rail** (this user's conversations), the **thread** (history in chronological order), and the **composer**.

States the interface must carry:

- A sent message: **pending** → **sent**, or **failed** with a retry.
- Empty or whitespace-only content is blocked before it's sent.
- New messages arrive live in the open thread, and in *other* conversations without stealing the user's place.
- The WebSocket can drop. It reconnects on its own, and while it's down the UI says realtime is unavailable — history and reading still work.
- A message sent to someone with no live connection still lands; they see it in history later.

Deliberately absent, and not to be designed around: presence, read receipts, typing indicators, file uploads, avatars-as-photos, accounts, settings. Authentication is minimal by design.

**Terminology:** conversation · participant · pending / sent / failed · reconnecting.

**Undecided:** product name, number and names of seeded users, whether conversations are pre-seeded or created on demand.

## Brand Commitments

The **Zühlke purple→cyan identity is binding**, confirmed by the user. `DESIGN.md` is its authority, sourced from `.context/attachments/020YJP/design-system.md`.

Binding: the identity gradient (`#aa41af` → `#3c69c8` → `#00a5e6`), Zühlke purple `#985b9c` as the single chromatic accent, glass over an ambient field, AA Zuehlke / Lato / JetBrains Mono, and first-class light **and** dark themes.

Not carried over: that system's dashboard, wizard, marketing, and documentation surface families. This product ships one surface family.

**Voice:** warm and human, addressed to a person. Not consultancy formality, not marketing pitch. The source system's own example is the register — "Good afternoon, Jairus," not a value proposition.

## Evidence on Hand

`requirements.md` / `requirements.pdf` (the brief), `plan.md` (architecture + full requirement set), and the design system in `.context/attachments/`.

**Nothing else is real.** No users, no usage data, no testimonials, no case studies, no benchmarks, no uptime or scale figures, no deployment, no pricing. Seeded identities are fixtures, not people. The brief explicitly says production readiness is not the goal — so no screen should imply it.

## Product Principles

1. **The server is the only author.** The client renders truth, or renders that it's still waiting. Never a hope as a fact.
2. **The conversation is the product.** Chrome recedes; two people talking is the only thing on screen that earns emphasis.
3. **Say what happened.** Pending, failed, disconnected — all announced in words. The design never leaves the user guessing what the system is doing.
4. **Legible enough to change live.** Explicitness beats abstraction, in the interface and the code behind it.
5. **Name the edge you didn't handle.** Unhandled cases are fine; unnoticed ones aren't.

## Accessibility & Inclusion

No requirement beyond what `DESIGN.md` already enforces: WCAG AA on the rendered surface in both themes, visible non-color-only focus, `prefers-reduced-motion` end-states, ≥44px targets, status in text rather than color alone.

Two product-side consequences: the composer is the primary control and must be fully keyboard-operable, and connection loss must be *announced* to assistive technology, not just shown as a colored bar.
