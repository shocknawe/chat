---
name: Realtime Messaging MVP
description: The Zühlke purple→cyan glass world, re-anchored from instrumentation to conversation — two people, one thread, server-authoritative.
colors:
  brand-purple: "#985b9c"
  brand-ink: "#834f88"
  grad-purple: "#aa41af"
  grad-violet: "#8a53c0"
  grad-blue: "#3c69c8"
  grad-cyan: "#00a5e6"
  ok: "#088043"
  ok-dark: "#2ec76f"
  info: "#007299"
  info-dark: "#33b5e0"
  warn: "#f2a33c"
  warn-ink: "#8a5200"
  warn-ink-dark: "#f2a33c"
  danger: "#b00020"
  danger-dark: "#ff6b7a"
  bg: "#ebe7f5"
  bg-dark: "#0b0813"
  bg-2: "#f3f0fb"
  bg-2-dark: "#120c1f"
  ink: "#181320"
  ink-dark: "#f3f0ff"
  ink-2: "#524b62"
  ink-2-dark: "#b6afd4"
  ink-3: "#736c88"
  ink-3-dark: "#7d7699"
  surface: "#ffffffa8"
  surface-dark: "#1e16348c"
  surface-2: "#ffffff6b"
  surface-2-dark: "#ffffff08"
  surface-solid: "#ffffff"
  surface-solid-dark: "#171026"
  field: "#ffffffa8"
  field-dark: "#ffffff0c"
  field-focus: "#ffffff"
  field-focus-dark: "#ffffff13"
  stroke: "#985b9c33"
  stroke-dark: "#a082ff29"
  stroke-strong: "#985b9c66"
  stroke-strong-dark: "#a082ff80"
  chip: "#985b9c21"
  chip-dark: "#a082ff24"
  nav-active: "#ffffffe6"
  nav-active-dark: "#ffffff0f"
  bubble-own: "#834f88"
  bubble-own-dark: "#6c3f79"
typography:
  display:
    fontFamily: "AA Zuehlke, Lato, system-ui, sans-serif"
    fontSize: "clamp(22px, 2.4vw, 30px)"
    fontWeight: 700
    lineHeight: 1.1
    letterSpacing: "-0.02em"
  headline:
    fontFamily: "AA Zuehlke, Lato, system-ui, sans-serif"
    fontSize: "20px"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.02em"
  title:
    fontFamily: "AA Zuehlke, Lato, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.01em"
  body:
    fontFamily: "Lato, system-ui, -apple-system, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  label:
    fontFamily: "Lato, system-ui, sans-serif"
    fontSize: "11px"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "0.14em"
  code:
    fontFamily: "JetBrains Mono, ui-monospace, SF Mono, Menlo, Consolas, monospace"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.6
    letterSpacing: "normal"
rounded:
  sm: "12px"
  md: "18px"
  lg: "26px"
  xl: "34px"
  pill: "999px"
spacing:
  xs: "9px"
  sm: "14px"
  md: "18px"
  lg: "24px"
  xl: "26px"
  "2xl": "34px"
  "3xl": "40px"
components:
  button-primary:
    backgroundColor: "{colors.grad-purple}"
    textColor: "#ffffff"
    typography: "{typography.title}"
    rounded: "{rounded.pill}"
    padding: "0 26px"
    height: "50px"
  button-primary-hover:
    backgroundColor: "{colors.grad-cyan}"
    textColor: "#ffffff"
  button-secondary:
    backgroundColor: "{colors.chip}"
    textColor: "{colors.brand-ink}"
    typography: "{typography.title}"
    rounded: "{rounded.pill}"
    padding: "0 26px"
    height: "50px"
  button-ghost:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.brand-ink}"
    typography: "{typography.title}"
    rounded: "{rounded.pill}"
    padding: "0 26px"
    height: "50px"
  card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.lg}"
    padding: "26px"
  app-header:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    height: "64px"
    padding: "0 24px"
  conversation-row:
    backgroundColor: "#00000000"
    textColor: "{colors.ink-2}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    padding: "12px 14px"
  conversation-row-active:
    backgroundColor: "{colors.nav-active}"
    textColor: "{colors.ink}"
  message-other:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "10px 14px"
  message-own:
    backgroundColor: "{colors.bubble-own}"
    textColor: "#ffffff"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "10px 14px"
  message-own-pending:
    backgroundColor: "#834f888c"
    textColor: "#ffffffd9"
  message-own-failed:
    backgroundColor: "#b000201a"
    textColor: "{colors.danger}"
  composer:
    backgroundColor: "{colors.field}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    padding: "14px 15px"
    height: "50px"
  composer-focus:
    backgroundColor: "{colors.field-focus}"
    textColor: "{colors.ink}"
  identity-tile:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.title}"
    rounded: "{rounded.md}"
    padding: "14px 18px"
  connection-banner:
    backgroundColor: "#f2a33c26"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.pill}"
    padding: "8px 16px"
  chip:
    backgroundColor: "{colors.chip}"
    textColor: "{colors.brand-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.pill}"
    padding: "6px 14px"
  status-pill:
    backgroundColor: "{colors.chip}"
    textColor: "{colors.ink-2}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "4px 10px"
---

# Design System: Realtime Messaging MVP

## Overview

**Creative North Star: "The Lit Room"**

Two people are in one softly-lit room. The walls are glass and the light behind them is alive — the Zühlke purple→cyan field breathing away in the background. The furniture is quiet: a rail of conversations, a thin header, a composer at the bottom. You stop noticing all of it within seconds, which is the point. The only object in the room that carries the brand at full voice is the message you just sent. Everything else steps back so two people can talk.

This is an **Operate** surface. The visitor is doing a task — reading what someone said and answering — and scanability, consistency, and honest system feedback outrank expression. The brand does not live in a hero here; it lives in precise details: the exact purple of your own words, the 4px gradient edge on the conversation you have open, the way a pending message resolves. Warmth is human, not decorative.

It inherits the Zühlke world from the "Claude Code · Insights" system wholesale — the identity gradient, the single purple accent, the glass-over-ambient-field material, the integer type scale, the dual-theme commitment — and deliberately drops that system's other three surface families. There is no dashboard, no setup wizard, no marketing site, no documentation shell here. One surface family means the drift that system documented against itself (two button families, two glass recipes, three colliding `.steps`, a conditional `background-clip:text` ban) simply has no way to occur: **there is one recipe for each thing, because there is only one place each thing appears.**

It rejects three things explicitly. The **generic SaaS dashboard** — hero-metric templates, identical icon-heading-text grids, flat gray Bootstrap chrome. The **corporate/consultancy stiff** register — buttoned-up formality, stock photography, jargon. And the **chat-app cliché** — the drop-shadowed bubble with a drawn tail, the fake-3D send button, the pill-shaped "typing…" ellipsis, decorated read-receipt iconography for states this product does not have.

**Key Characteristics:**
- Glass panes over an ambient gradient field; depth from blur, shadow, and a top-edge sheen — never flat fills.
- One chromatic accent (Zühlke purple), spent almost entirely on the user's own messages.
- The gradient is identity and never sits behind body copy — which in a messaging app means it never touches a bubble.
- Integer type scale (`11 · 12 · 13 · 14 · 15 · 16 · 20`), two humanist families, a narrow mono layer.
- Light and dark are equally first-class; every token carries both values.
- Message state — pending, sent, failed, disconnected — is always words first, color second.

## Colors

One purple accent and a committed brand gradient, floated over a periwinkle-lilac (light) or deep purple-black (dark) field, with everything else a purple-tinted neutral.

### Primary

- **Zühlke Purple** (`#985b9c`): the one chromatic accent in the chrome — focus rings, active borders, selection, checked states. On light glass, text and icons step down to the darker **Brand Ink** (`#834f88`) for AA legibility.
- **Brand Ink** (`#834f88` light / `#6c3f79` dark): does double duty here. It is the accent's text-safe form *and* it is the fill of the user's own message bubble — the single most repeated saturated surface in the product. White text on it clears 6.1:1 (light) and 8.0:1 (dark).
- **The Identity Gradient** (`linear-gradient(45deg, #aa41af 5%, #3c69c8 60%, #00a5e6 100%)`): purple → blue → cyan, from stops **Grad Purple** (`#aa41af`), **Grad Violet** (`#8a53c0`), **Grad Blue** (`#3c69c8`), **Grad Cyan** (`#00a5e6`). With the marketing and dashboard families gone, its surfaces reduce to five: the brand mark, user avatars, the 4px active-conversation edge marker, the primary button fill, and the ambient background shader. That shortlist is the whole licence.

### Secondary

- **Semantic Status** — **OK Green** (`#088043` light / `#2ec76f` dark), **Info Blue** (`#007299` light / `#33b5e0` dark), **Warn Amber** (`#f2a33c`, as text `#8a5200` light / `#f2a33c` dark), **Danger Red** (`#b00020` light / `#ff6b7a` dark). Meaning is fixed: green = delivered/positive, blue = connected/active, amber = degraded, red = failed/rejected.

  Two of these values are new, and deliberately so. The inherited system left `--warn` and `--danger` theme-invariant, which its own drift list flagged as unfinished; amber fails AA as text on light glass (2.97:1) and `#b00020` fails against the dark field (2.54:1). This product puts both on screen as *text* — a "reconnecting" banner and a "failed to send" bubble — so the theme-tuned text forms `warn-ink` and `danger-dark` close the gap rather than inherit the bug. The original `warn` and `danger` values remain correct for fills, borders, and dots.

### Neutral

- **Field** (`#ebe7f5` light / `#0b0813` dark): the page behind the ambient shader. **Field-2** (`#f3f0fb` / `#120c1f`) backs the off-canvas conversation rail on mobile.
- **Ink ramp** — **Ink** (`#181320` / `#f3f0ff`) message body and names, **Ink-2** (`#524b62` / `#b6afd4`) previews and secondary meta, **Ink-3** (`#736c88` / `#7d7699`) timestamps and muted labels.
- **Glass surfaces** — **Surface** (`rgba(255,255,255,.66)` / `rgba(30,22,52,.55)`) the primary translucent pane, and the fill of the *other* person's message; **Surface-2** (`rgba(255,255,255,.42)` / `rgba(255,255,255,.03)`) recessed hover; **Surface-Solid** (`#ffffff` / `#171026`) for tooltips and the active conversation row; **Stroke** (`rgba(152,91,156,.20)` / `rgba(160,130,255,.16)`) the glass hairline.
- **Field surfaces** — **Field** (`rgba(255,255,255,.66)` / `rgba(255,255,255,.045)`) the composer's background, deliberately flatter than glass so it reads as recessed rather than floating; **Field-Focus** (`#ffffff` / `rgba(255,255,255,.075)`) once focused.

All neutrals are tinted toward the brand purple, never toward warm-gray.

### Named Rules

**The One Accent Rule.** Zühlke purple is the *only* chromatic accent in the chrome. Everything non-identity, non-status is a purple-tinted neutral. A second decorative hue anywhere in the product is wrong.

**The Own-Words Rule.** In the thread, the only saturated fill is the message *you* sent. The other person's messages are neutral glass; the rail, header, and composer are neutral glass. This is the chat form of the One Accent Rule, and it is what makes a fifty-message thread readable: one color means one thing, and that thing is authorship.

**The Gradient-Is-Identity Rule.** The gradient appears on the brand mark, avatars, the active-conversation edge marker, the primary button, and the ambient shader. Nowhere else — and specifically never inside or behind a message bubble, where it would put a moving multi-stop fill under body copy.

**The Legibility-Wins Rule.** Body text clears ≥4.5:1 against the *actual rendered* surface (pane plus whatever shows through) in both themes; large or bold text ≥3:1. When atmosphere and contrast conflict, contrast wins — step ink toward the dark end of the ramp, never lighten "for elegance." Placeholder text in the composer is held to 4.5:1 too.

**The Status-Is-Text Rule.** Connection state, send state, and errors are announced in words. Color is the second signal and never the only one. "Reconnecting…" is the message; the amber is decoration on top of it.

## Typography

**Display Font:** AA Zuehlke (with Lato, system-ui fallback)
**Body Font:** Lato (with system-ui, -apple-system fallback)
**Mono Font:** JetBrains Mono (with ui-monospace, SF Mono, Menlo, Consolas fallback)

**Character:** a single humanist voice across two near-identical families. AA Zuehlke and Lato share DNA, so a name in the thread header and the message beneath it feel written by one hand, with the display face carrying tighter tracking and heavier weight where a person's name needs presence. The mono layer is the technical counterpoint and is scoped hard: connection diagnostics and any machine identifier the UI surfaces. If nothing in the build needs it, it does not ship.

### Hierarchy

- **Display** (700, `clamp(22px, 2.4vw, 30px)`, line-height 1.1, `-0.02em`): the empty-state headline and the identity-selection greeting. One per view at most.
- **Headline** (700, `20px`, `-0.02em`): the conversation partner's name in the thread header. The one place a person's name is given weight.
- **Title** (700, `16px`): brand wordmark, conversation-row names, section headings.
- **Body** (400, `15px`, line-height 1.5): message content, composer text, conversation previews. Message text sits at 15px rather than the system's 14px UI default — it is the thing being read, not chrome.
- **Label** (700, `11px`, `0.14em`, uppercase): date separators, status pills, `kbd`.
- **Meta** (400, `12px`, `--ink-3`): timestamps and send-state text. Timestamps carry `font-variant-numeric: tabular-nums` so a column of them does not shimmer.

### Named Rules

**The Integer-Ramp Rule.** UI font sizes land on the fixed integer ramp `11 · 12 · 13 · 14 · 15 · 16 · 20`; only the display headline uses fluid `clamp()`. No half-pixels.

**The Display-Is-For-People Rule.** The AA Zuehlke display face carries names and the empty-state headline — nothing else. Never in labels, buttons, message body, timestamps, or dense UI text. In the inherited system this rule protected numeric figures; here it protects people's names, and the prohibition is identical.

**The Message-Reads-First Rule.** Nothing in a bubble competes with the message. Timestamp and send state sit outside or below the bubble at 12px `--ink-3`, never inline at body weight, and never as an icon whose meaning is not also written.

## Layout

**App shell.** `grid-template-columns: 300px minmax(0, 1fr)` — a sticky full-height conversation rail beside the thread column. The rail runs wider than the inherited system's 264px sidebar because a conversation row carries an avatar, a name, a message preview, and a timestamp on one line. Rail internals: `22px` horizontal padding, `24px` between the identity block and the list.

**Thread column.** A vertical stack of three parts: a sticky `64px` glass header (partner name + connection state), the scrolling message region, and a sticky composer. The message region caps its content at `860px` and centers it, so a wide monitor does not stretch a conversation across 2000px. Individual bubbles cap at `min(72ch, 68%)` of that column — wide enough for a paragraph, narrow enough that the left/right authorship read never becomes ambiguous.

**Thread rhythm.** `24px 34px` region padding on desktop, `18px` between messages from different senders, `4px` between consecutive messages from the same sender. That two-value gap is what produces visual grouping without drawing a single grouping container. Date separators take `26px` of air above and below. The composer is separated from the last message by `18px` and closed above by a `1px --stroke` hairline.

**Scroll behavior.** The thread is anchored to the bottom. New messages arriving while the user is at the bottom scroll into view; new messages arriving while the user has scrolled up must not yank them — surface a quiet "new message" affordance instead and let them choose.

**Responsive ladder** (structural, not fluid — inherited unchanged):

| Breakpoint | Behavior |
|---|---|
| `>1180px` | Full two-column shell, 300px rail. |
| `≤920px` | Rail becomes a fixed off-canvas drawer (`left:-300px` → `.open`) over `--bg-2`, opened by a header menu button. Thread takes the full width. |
| `≤720px` | Thread padding drops to `18px`; bubbles cap at `82%`; the composer goes edge-to-edge. |
| `≤560px` | Header condenses to back-arrow + name; the connection banner becomes a full-width strip rather than a floating pill. |

**Density.** One density. There is no compact mode, and there is no setting for it.

## Elevation & Depth

Depth is **layered glass, not drawn boxes.** Every *pane* is a translucent, backdrop-blurred surface lifted off the ambient field by a soft, long, purple-tinted shadow plus a 1px top-edge sheen (`::before`, white-to-transparent gradient). There is no flat-fill card and no hard 2014-era drop shadow anywhere in the system. If a surface looks like a solid rectangle with a gray box-shadow, the blur is missing and the recipe is wrong.

Message bubbles are the deliberate exception, and it matters. A thread is dozens of small repeating surfaces; giving each one a card-lift shadow turns a conversation into gravel. Bubbles sit *in* the room rather than floating above it.

### Shadow Vocabulary

- **Pane lift** (`box-shadow: 0 18px 50px -24px rgba(90,45,120,.40), 0 2px 8px -4px rgba(90,45,120,.20)` light; `0 24px 60px -28px rgba(0,0,0,.8), inset 0 1px 0 rgba(255,255,255,.04)` dark): the conversation rail, the thread header, the composer, the identity-selection card. Long, soft, tinted — reads as *floating*, not stamped.
- **Bubble rest** (`box-shadow: 0 1px 2px -1px rgba(90,45,120,.18)` light; `none` dark, hairline only): a whisper. Enough to separate a glass bubble from the field behind it, not enough to accumulate.
- **Accent glow** (`0 14px 34px -12px var(--glow-1)` → `0 20px 44px -14px` on hover): under the gradient-filled primary button only.
- **Blur:** `--glass-blur` 22px light / 24px dark on panes. Bubbles use 14px — lighter, because they are smaller and there are many.

### Named Rules

**The Glass-Recipe Rule.** One recipe, no exceptions: `background: var(--surface)` + `backdrop-filter: blur(22px)` + `var(--card-shadow)` + `border: 1px solid var(--stroke)` + top-edge sheen `::before`. The inherited system carried this as an aspiration because four surface families had each redefined it; with one family there is nothing to reconcile. Do not reintroduce a per-component override.

**The Thread-Is-Flat Rule.** Panes float; bubbles do not. A message bubble carries a hairline and at most the Bubble-rest whisper. The moment bubbles get the pane shadow, repetition destroys the hierarchy that shadow was supposed to create.

## Shapes

The form language is soft, symmetric, and generous. Radii step `12 · 18 · 26 · 34 · 999px`: `sm` (12px) for conversation rows and the composer, `md` (18px) for message bubbles, `lg` (26px) for the rail and identity cards, `xl` (34px) reserved for a single dominant surface if one ever appears, `pill` for buttons, chips, status pills, badges, and avatars. Avatars are circles; the brand mark is an 11px-radius gradient tile.

Borders are 1px `--stroke` hairlines. There are no dividers drawn as full-width rules inside a pane where spacing can do the work instead, and there are no colored edge stripes — with exactly one exception, below.

### Named Rules

**The One Asymmetric Corner Rule.** The message bubble is the only shape permitted an asymmetric radius: `18px` on three corners and `6px` on the corner nearest its author's edge — bottom-right for your own messages, bottom-left for theirs. Only the *last* bubble in a consecutive group takes it; the ones above stay fully rounded, which is what makes a group read as one utterance. No drawn tails, no triangles, no pseudo-element beaks. This silhouette is the product's signature and it is not reused on any other component.

**The One Edge Marker Rule.** A colored left or right edge stripe is banned as decoration. The single sanctioned use is the 4px `--brand-grad` marker on the active conversation row — an active-state indicator, inherited unchanged from the system's active-nav rule.

## Components

Every interactive component ships all states — default, hover, focus, active, disabled — with a visible, non-color-only focus indicator (`box-shadow: 0 0 0 4px var(--chip)`, or the global `:focus-visible` 2px `--brand` outline at 2px offset). Half a state set is a bug.

### Buttons

One `.btn` family. The inherited system's split between a marketing `.btn` (50px) and a wizard `.fbtn` (48px) does not exist here; there is one height and one set of modifiers.

- **Shape:** full pill (999px), 50px tall, `15px`/700 label, `9px` icon gap, `18px` icons.
- **Primary:** gradient fill (`--brand-grad`) with a reverse-gradient `::before` that cross-fades in on hover; lifts `-2px`; accent-glow shadow. The label sits in an inner `<span>` above the `::before`. Padding `0 26px`.
- **Secondary:** the tonal middle — `--chip` fill, `--brand-ink` label, hairline `--stroke-2` border. Hover strengthens tint and border and lifts `-2px`.
- **Ghost:** glass `--surface`, `--brand-ink` label, `--stroke` border. The quietest bordered action.
- **Emphasis ramp:** primary → secondary → ghost → text. One primary per view.
- **Send button:** an icon-only 44px pill inside the composer, gradient-filled, disabled (reduced opacity, no accent saturation, `aria-disabled`) whenever the composer is empty or whitespace-only. Disabled is the *design* expression of the requirement that empty messages cannot be submitted — but it is never the only guard.

### Chips

- **Style:** `--chip` background (purple tint at .13), `--brand-ink` text, pill radius, no border.
- **State:** selected chips take a `--brand` border and a stronger tint.

### Cards / Containers

- **Corner Style:** `--r-lg` (26px).
- **Background:** glass `--surface`; never the gradient.
- **Shadow:** Pane lift, plus the top-edge sheen `::before`.
- **Internal Padding:** `26px`; header→body gap `18px`.
- Never nest a card inside a card.

### Inputs / Composer

The composer is the product's primary control and gets the system's canonical `.control` recipe, grown into a multiline field.

- **Style:** `--r-sm` (12px), `--field` background (flatter than glass, so it reads recessed), `15px` text, inner padding `14px 15px` — identical top/bottom/left/right whether one line or five. `min-height: 50px`, auto-grows on input to a `160px` cap, then scrolls.
- **Focus:** `border-color: var(--brand)` + `background: var(--field-focus)` + `box-shadow: 0 0 0 4px var(--chip)`. Never `outline: none` without this ring replacing it.
- **Submit:** Enter sends, Shift+Enter newlines. The send button is the discoverable equivalent, not an alternative path with different validation.
- **Error:** on a rejected send, the composer border takes `--danger` and gets one elastic shake (`elastic.out(1,.4)`, ~8px) — reusing the system's single sanctioned validation gesture rather than inventing a new one. The message itself appears in the failed bubble, not as a toast.
- **Disabled:** while the WebSocket is down the composer stays *enabled* and focusable; what changes is the send affordance and the banner. Locking someone out of typing a message they have already composed is worse than queuing it.

### Navigation — the conversation rail

The conversation list *is* the navigation, so it inherits the nav-item contract exactly.

- **Row:** `14px`/400, `--r-sm`, `12px 14px` padding. Avatar (34px circle, gradient) → name (`16px`/700 `--ink`) over preview (`13px` `--ink-2`, single line, ellipsised) → timestamp (`12px` `--ink-3`, top-right).
- **Default:** `--ink-2`, transparent. **Hover:** `--ink`, `--surface-2`. **Active:** `--ink`, `--nav-active` background, pane shadow, and the 4px `--brand-grad` left-edge marker.
- **Unread:** the row name goes `--ink`/700 and a `--brand` dot sits at the right. Weight and a dot, not a colored row — a colored row would break the One Accent Rule and collide with the active state.
- **Mobile:** collapses off-canvas at `--z-overlay` (60) over `--bg-2`.

### Signature — The Thread

The product's identity, and the one pattern worth getting exactly right.

A single scrolling column of authored utterances over the ambient field. Your messages align right and are filled `--bubble-own` with white text; theirs align left as neutral glass with `--ink` text. Consecutive messages from one sender group at `4px`, separated from the next sender by `18px`, and only the last bubble in a group takes the asymmetric corner. The sender's avatar appears once per group, aligned to the group's last bubble. Date separators are centered `11px`/700 uppercase `--ink-3` labels on a hairline.

Send state lives *outside* the bubble, below-right, at `12px` `--ink-3`, and it is always a word:

| State | Bubble | Meta line |
|---|---|---|
| **Pending** | `--bubble-own` at 55% opacity, text at 85% | "Sending…" |
| **Sent** | full `--bubble-own` | the timestamp |
| **Failed** | danger-tinted fill, `--danger` / `--danger-dark` text, `--danger` hairline | "Not sent · **Retry**" — the retry is a real focusable button |

That table is the whole point of the surface. The product's central claim is that the client never treats its own message as authoritative until the server says so, and this is where a reviewer sees that claim rendered rather than asserted. Pending must be visibly distinct from sent at a glance and without color vision.

### Connection state

A pill banner beneath the thread header, `--r-pill`, warn-tinted fill with `--ink` text and a `--warn` dot. It says what is happening and what is being done about it — "Realtime messaging unavailable · reconnecting…" — and it carries `role="status"` with `aria-live="polite"` so it is announced, not merely displayed. It slides down over 300ms and dismisses itself on reconnect with a brief `--ok` "Reconnected" state before fading. It is never a red full-bleed error bar; the app still works, history still loads, and the design should say so.

### Motion

`--ease: cubic-bezier(.22, 1, .36, 1)` (ease-out-quint). Durations: micro-feedback `.2s`, hover/transform `.3s`, banner `.3s`, ambient shader continuous.

- **Message arrival:** 200ms fade plus an 8px rise from the composer side. No scale, no bounce, no spring.
- **Pending → sent:** a 200ms opacity crossfade on the fill and a text swap in the meta line. Nothing moves; the bubble must not resize or reposition when it confirms, or the thread jumps under the reader.
- **Failed:** the one sanctioned elastic shake, once. Never repeated, never on retry.
- `prefers-reduced-motion` is fully honored: arrivals resolve instantly to their end state, the banner appears without sliding, the ambient shader is disabled and falls back to a static triple-radial-gradient blur, and the shake is skipped entirely.

## Do's and Don'ts

### Do:

- **Do** float glass over the ambient field with the one recipe: `--surface` + `backdrop-filter: blur(22px)` + `--card-shadow` + `--stroke` border + top-edge sheen.
- **Do** keep Zühlke purple (`#985b9c`) as the only chromatic accent, and spend its saturated form on the user's own messages and nothing else.
- **Do** reserve the gradient for the five identity surfaces — brand mark, avatar, active-conversation marker, primary button, ambient shader.
- **Do** verify ≥4.5:1 body / ≥3:1 large text against the *rendered* surface in both themes, including composer placeholder text.
- **Do** use the theme-tuned text forms `warn-ink` (`#8a5200` light) and `danger-dark` (`#ff6b7a` dark) whenever a status color carries words.
- **Do** write every state in words — "Sending…", "Not sent", "Reconnecting…" — and let color be the second signal.
- **Do** group consecutive messages at `4px` and separate senders at `18px`; let spacing do the grouping instead of a container.
- **Do** give the failed state a real, focusable Retry button inside the thread.
- **Do** ship every interactive state with a visible focus ring (`0 0 0 4px var(--chip)`), and give every animation a `prefers-reduced-motion` end-state.
- **Do** announce connection loss with `role="status"` / `aria-live="polite"`.

### Don't:

- **Don't** put the gradient — or any fill that is not `--bubble-own` or `--surface` — behind message text.
- **Don't** give message bubbles the pane shadow. Repetition is what kills elevation; bubbles get a hairline and a whisper.
- **Don't** draw a bubble tail, beak, or triangle. The asymmetric 6px corner on the group's last bubble is the entire device.
- **Don't** let a bubble resize, reposition, or re-order when it goes from pending to sent. Confirmation must be visually silent below the meta line.
- **Don't** auto-scroll a reader who has scrolled up. Offer a "new message" affordance and let them decide.
- **Don't** use `background-clip: text` gradient text anywhere. The inherited system licensed it for marketing hero type; this product has no marketing surface, so the ban is unconditional again.
- **Don't** use a colored left/right edge stripe as decoration. The 4px gradient active-conversation marker is the only sanctioned one.
- **Don't** signal unread with a colored row fill — it collides with the active state and breaks the One Accent Rule. Weight plus a `--brand` dot.
- **Don't** disable the composer when the socket drops. Change the send affordance and raise the banner; never take away text someone is mid-way through writing.
- **Don't** convey send state through an icon alone — no bare checkmarks, double-ticks, or clock glyphs without a word beside them.
- **Don't** build a **generic SaaS dashboard** (hero-metric templates, identical icon-heading-text grids, flat gray chrome) or drift into the **corporate/consultancy stiff** register.
- **Don't** reintroduce the inherited system's drift: one glass recipe, one button family, one z-scale, one meaning per name. With a single surface family there is no excuse for a second of anything.
