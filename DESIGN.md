---
name: Claude Code · Insights
description: A dual-theme glass-over-gradient system on the Zühlke purple→cyan identity, tuned for glanceable developer insight.
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
  danger: "#b00020"
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
  surface-solid: "#ffffff"
  surface-solid-dark: "#171026"
  stroke: "#985b9c33"
  stroke-dark: "#a082ff29"
typography:
  display:
    fontFamily: "AA Zuehlke, Lato, system-ui, sans-serif"
    fontSize: "clamp(34px, 4.4vw, 52px)"
    fontWeight: 700
    lineHeight: 0.9
    letterSpacing: "-0.03em"
  headline:
    fontFamily: "AA Zuehlke, Lato, system-ui, sans-serif"
    fontSize: "clamp(22px, 2.4vw, 30px)"
    fontWeight: 700
    lineHeight: 1.1
    letterSpacing: "-0.02em"
  title:
    fontFamily: "AA Zuehlke, Lato, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.01em"
  body:
    fontFamily: "Lato, system-ui, -apple-system, sans-serif"
    fontSize: "14px"
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
    fontSize: "13px"
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
    backgroundColor: "{colors.stroke}"
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
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    padding: "0 15px"
    height: "50px"
  input-focus:
    backgroundColor: "{colors.surface-solid}"
    textColor: "{colors.ink}"
  chip:
    backgroundColor: "{colors.stroke}"
    textColor: "{colors.brand-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.pill}"
    padding: "6px 14px"
  nav-item:
    backgroundColor: "#00000000"
    textColor: "{colors.ink-2}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    padding: "10px 14px"
  nav-item-active:
    backgroundColor: "{colors.surface-solid}"
    textColor: "{colors.ink}"
  status-pill:
    backgroundColor: "{colors.stroke}"
    textColor: "{colors.ink-2}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "4px 10px"
  form-label:
    backgroundColor: "#00000000"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    fontSize: "14px"
    fontWeight: 700
  modal:
    backgroundColor: "{colors.surface-solid}"
    textColor: "{colors.ink}"
    rounded: "{rounded.lg}"
    padding: "20px 24px"
  app-header:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    height: "64px"
---

# Design System: Claude Code · Insights

## 1. Overview

**Creative North Star: "The Lucid Cockpit"**

This is a developer's cockpit: every reading is glanceable and instantly trustworthy, while depth, glow, and an ambient purple→cyan field signal a living system humming behind the controls. Translucent glass panes float above that field; the Zühlke brand gradient is the light source, not the wallpaper. The interface is confident and atmospheric — alive, never clinical — but the instant a developer needs to read a number, it goes quiet and exact. Warmth is human, not decorative: "Good afternoon, Jairus," not a marketing pitch.

The system spans four surface families over one shared token base: the **dashboard** (glass bento over the ambient field), the **setup flows** (recessed fields + drenched identity panes), the **marketing site** (full-bleed heroes, promotional buttons, scroll storytelling), and the **documentation** (a three-column doc shell with a monospace/syntax layer). They differ in vocabulary, never in foundation. `form.html` is canonical wherever a shared control diverges.

It explicitly rejects the **generic SaaS dashboard** (hero-metric templates, identical icon-heading-text card grids, flat gray Bootstrap chrome, gradient-text everywhere) and the **corporate/consultancy stiff** register (buttoned-up formality, stock photography, jargon). This is a developer tool with a person on the other side.

**Key Characteristics:**
- Glass over an ambient gradient field — depth from blur, shadow, and a top-edge sheen, never flat fills.
- One chromatic accent (Zühlke purple); the gradient is reserved for identity and data moments.
- Integer type scale (`11·12·13·14·15·16·20`) + fluid `clamp()` display, on two families (AA Zuehlke display, Lato UI) plus a mono layer.
- Both light and dark themes are first-class; every token carries both values.
- One orchestrated arrival on load, then motion gets out of the way.

## 2. Colors

A single purple accent and a committed brand gradient, floated over a periwinkle-lilac (light) or deep purple-black (dark) field, with everything else a purple-tinted neutral.

### Primary
- **Zühlke Purple** (`#985b9c`): The one chromatic accent in the UI chrome — focus rings, active borders, selection, checked states. On light glass, text and icons use the darker **Brand Ink** (`#834f88`) for AA legibility.
- **The Identity Gradient** (`linear-gradient(45deg, #aa41af 5%, #3c69c8 60%, #00a5e6 100%)`): purple → blue → cyan, built from stops **Grad Purple** (`#aa41af`), **Grad Violet** (`#8a53c0`), **Grad Blue** (`#3c69c8`), **Grad Cyan** (`#00a5e6`). A committed identity element, not decoration: brand mark, avatar, progress/plan/language bars, chart line and area, the acceptance ring, the ambient background shader, and — as the drenched `--pane-grad` — the wizard brand pane, long-form hero, submit block, marketing hero/closing CTA, and tutorial lesson hero.

### Secondary
- **Semantic Status** — **OK Green** (`#088043` light / `#2ec76f` dark), **Info Blue** (`#007299` light / `#33b5e0` dark), **Warn Amber** (`#f2a33c`), **Danger Red** (`#b00020`). Theme-tuned so small bold text holds AA on glass. Meaning is fixed everywhere: green = done/positive, blue = active/in-progress, purple = in-review, red = negative/rejected.

### Neutral
- **Field** (`#ebe7f5` light / `#0b0813` dark): the page behind the ambient shader. **Field-2** (`#f3f0fb` / `#120c1f`) backs the off-canvas sidebar.
- **Ink ramp** — **Ink** (`#181320` / `#f3f0ff`) primary text, **Ink-2** (`#524b62` / `#b6afd4`) secondary, **Ink-3** (`#736c88` / `#7d7699`) tertiary/muted labels.
- **Glass surfaces** — **Surface** (`rgba(255,255,255,.66)` / `rgba(30,22,52,.55)`) the primary translucent pane; **Surface-Solid** (`#ffffff` / `#171026`) for tooltips and active tabs; **Stroke** (`rgba(152,91,156,.20)` / `rgba(160,130,255,.16)`) the glass border. All neutrals are tinted toward the brand purple, never toward warm-gray.

### Named Rules
**The One Accent Rule.** Zühlke purple is the *only* chromatic accent in UI chrome. Everything non-identity, non-status is a purple-tinted neutral. If a second decorative hue appears in the product UI, it is wrong.

**The Gradient-Is-Identity Rule.** The brand gradient appears only on identity surfaces and data viz — never as a content-card background, never behind body copy. Full-bleed gradient fills are always paired with a multiply-mode dark overlay (`::before`) and a soft radial sheen (`::after`) so foreground text stays legible.

**The Legibility-Wins Rule.** Body text clears ≥4.5:1 against the *actual rendered* glass surface (pane + whatever shows through) in both themes; large/bold text ≥3:1. When atmosphere and contrast conflict, contrast wins — bump ink toward the dark end of the ramp, never lighten "for elegance."

## 3. Typography

**Display Font:** AA Zuehlke (with Lato, system-ui fallback)
**Body Font:** Lato (with system-ui, -apple-system fallback)
**Mono Font:** JetBrains Mono (with ui-monospace, SF Mono, Menlo, Consolas fallback) — code blocks, terminal output, version tags, repo names, doc counters.

**Character:** A single humanist voice across two weights of near-identical families — AA Zuehlke and Lato share DNA, so headings and body feel of one hand, with the display face carrying tighter tracking and heavier weight for numeric and heading confidence. The mono layer is the technical counterpoint, scoped to code and machine output.

### Hierarchy
- **Display** (700, `clamp(34px, 4.4vw, 52px)`, line-height 0.9, `-0.03em`): hero throughput value, marketing hero headlines, KPI figures (`clamp(28px, 3vw, 38px)`). Numeric-forward.
- **Headline** (700, `clamp(22px, 2.4vw, 30px)`, `-0.02em`): page `h1` / greeting.
- **Title** (700, `15–16px`): card titles (`15px`), brand name and section headings (`16px`).
- **Body** (400, `14px`, line-height 1.5): nav items, search, session/repo/language text. Prose caps at 65–75ch.
- **Label** (700, `11px`, `0.14em` tracking, uppercase): nav labels, badges, `kbd`, footnotes.

Numeric values use `font-variant-numeric: tabular-nums` (`.tnum`) so animated figures don't jitter. Unit suffixes (`M tokens`, `$`, `%`) are `em`-relative (`.4em`–`.55em`) so they scale with the parent figure.

### Named Rules
**The Integer-Ramp Rule.** UI font sizes land on the fixed integer ramp `11 · 12 · 13 · 14 · 15 · 16 · 20`; only display headings and KPI/hero values use fluid `clamp()`. No half-pixels — computed `em`-relative unit suffixes (`13.6px`) are the sole sanctioned exception.

**The Display-Stays-Numeric Rule.** AA Zuehlke display carries headings and numeric values only. Never a display font in labels, buttons, or dense UI text — that's a product-UI tell.

## 4. Elevation

Depth is **layered glass, not drawn boxes.** Every surface is a translucent, backdrop-blurred pane lifted off the ambient field by a soft, long, purple-tinted shadow plus a 1px top-edge sheen (`::before`, a white-to-transparent gradient). There is no flat-fill card and no hard 2014-era drop shadow anywhere in the system. If a surface looks like a solid rectangle with a gray box-shadow, the blur is missing and the recipe is wrong.

### Shadow Vocabulary
- **Card lift** (`box-shadow: 0 18px 50px -24px rgba(90,45,120,.40), 0 2px 8px -4px rgba(90,45,120,.20)` light; `0 24px 60px -28px rgba(0,0,0,.8), inset 0 1px 0 rgba(255,255,255,.04)` dark): the default glass pane. Long, soft, tinted — reads as *floating*, not stamped.
- **Shell elevation** (`0 40px 120px -40px rgba(80,40,120,.5), 0 8px 30px -12px rgba(80,40,120,.28)` light): reserved for the single dominant wizard shell, heavier than a card because it's the one surface.
- **Accent glow** (`0 14px 34px -12px var(--glow-1)` → `0 20px 44px -14px` on hover): under gradient-filled primary buttons only.
- **Blur:** `--glass-blur` 22–24px on glass panes.

### Named Rules
**The Glass-Recipe Rule (canonical).** One glass recipe: `background: var(--surface)` + `backdrop-filter: blur(22px)` + `var(--card-shadow)` + `border: 1px solid var(--stroke)` + top-edge sheen `::before`. Per-context overrides are allowed only where justified (the dominant wizard shell); the dashboard, setup, marketing, and docs panes must otherwise resolve to identical `--surface` / `--card-shadow` / `--glass-blur` values. *(Reconciliation target — today these are redefined per-file; see Don'ts.)*

## 5. Components

Every interactive component ships all states — default, hover, focus, active, disabled — with a visible, non-color-only focus indicator. Half a state set is a bug.

### Buttons
- **Shape:** full pill (`--r-pill`, 999px), 50px tall, `15px`/700 label, `9px` icon gap, icon `18px`.
- **Primary:** gradient fill (`--brand-grad`) with a reverse-gradient `::before` that cross-fades in on hover; lifts `-2px`; accent-glow shadow. The label sits in an inner `<span>` above the `::before`. Padding `0 26px`.
- **Secondary (v2):** the tonal middle ground — `--chip` tint fill, `--brand-ink` label, hairline `--stroke-2` border. Hover strengthens the tint (`--nav-active`) and border and lifts `-2px`. For the supporting action beside a primary — *no longer a synonym for ghost*. In MUI: `variant="secondary"`.
- **Ghost:** glass `--surface` background, `--brand-ink` label, `--stroke` border. The quietest bordered action.
- **Emphasis ramp (v2):** primary (gradient) → secondary (tonal) → ghost (outlined) → text. One primary per view; the others carry everything else.
- **Canonical family (target):** one `.btn` family with size and context modifiers — *not* the current split between marketing `.btn` (50px) and wizard `.fbtn` (48px). New work builds on `.btn`.

### Chips
- **Style:** `--chip` background (purple tint at .13), `--brand-ink` text, pill radius, no border. Used for multi-select filters and icon-tile backings.
- **State:** selected chips take a `--brand` border and stronger tint; single-select segmented groups use `role="tablist"`.

### Cards / Containers
- **Corner Style:** `--r-lg` (26px); large marketing/wizard surfaces step up to `--r-xl` (34px).
- **Background:** glass `--surface`; never the gradient.
- **Shadow Strategy:** the Card-lift shadow from Elevation, plus the top-edge sheen `::before`. Never nest a card inside a card.
- **Internal Padding:** `26px` (`--xl` spacing); header→body gap `18px`.

### Inputs / Fields (`.control` — canonical)
- **Style:** `50px` tall, `--r-sm` (12px), `--field` background — deliberately *flatter* than glass `--surface` so fields read as recessed, not floating. `15px` text.
- **Focus:** `border-color: var(--brand)` + `background: var(--field-focus)` + `box-shadow: 0 0 0 4px var(--chip)` (a soft purple ring). No `outline:none` without this ring replacing it.
- **Error:** `--danger` border + a sanctioned one-shot elastic shake on failed submit. **Disabled:** reduced opacity, no accent saturation.

### Form anatomy (v2 — `FormField` / `FormRow` / `FormActions`)
One anatomy for every plain form field, in this order and nothing else:
- **Label** — external, above the control: `14px`/700 in `--ink`, required marked with a `--danger` asterisk. It tints `--brand-ink` while its control has focus and `--danger` in the error state. *No floating labels in product forms* — the floating variant stays scoped to legacy in-field `TextField`s.
- **Control** — the canonical `.control` recipe (above). Multiline textareas share the *exact* inner padding of a single-line field (`14px 15px`) — the doubled-padding textarea is fixed and must not return.
- **Helper line** — one line under the control: a muted hint (`--ink-3`) or the error message (`--danger`); the error message *is* the error state trigger, and it replaces the hint rather than stacking. Label ↔ control ↔ helper are wired with ids for the screen reader.
- **Layout** — `FormRow` pairs fields side by side from the `sm` breakpoint and stacks on phones; `FormActions` closes the form with a right-aligned button row, primary action last (full-width, primary on top, on phones).

### Modals (v2 — `Modal`)
An opaque glass pane (`--surface-solid`, `--r-lg`, `--stroke` border, shell shadow) over the dimmed, blurred scrim (`rgba(20,12,32,.5)` + 4px backdrop blur).
- **Sizes:** **small** `420px` (confirmations, forced choices) · **medium** `600px` (the default) · **large** `780px` (dense content) · **fullscreen** (viewport takeover, radius 0, slides up from the bottom, content column capped at `920px`).
- **Anatomy:** title row (display 700 `18px`) with an optional quiet description and the ✕ at the right → content → action bar, primary action last. Omitting `onClose` makes it a forced-choice modal: no ✕, no backdrop/Escape dismiss.
- Prefer the house `Modal` over a raw `Dialog` — the sizes and the title/✕/actions frame are the standard.

### App header (v2 — `AppHeader`)
The default page chrome: a sticky glass bar (`64px`, `--surface` + `blur(22px)`, closed by a 1px `--stroke` bottom hairline) at `z: appBar`, content capped at `1180px`.
- **Left:** the gradient brand tile (34px, `--r ~10px`, accent glow) + wordmark title, with an optional overline subtitle. A custom `logo` slot may replace it.
- **Middle:** optional nav links (hidden under `md`).
- **Right:** action slots (e.g. the color-mode toggle), then the signed-in user — gradient-initials avatar + name (name hides on `xs`) opening the account menu (name/caption header, items, hairline before the destructive "Sign out").

### Navigation
- **Style:** `14px`/400 items, `--r-sm`. **Active** takes `--ink` text, `--nav-active` background, card shadow, and a 4px gradient left-edge marker (`::before`, `--brand-grad`) — this is the *one* sanctioned use of a left-edge accent, and it's an active-state marker, not a decorative card stripe. Mobile collapses the sidebar off-canvas at `--z-overlay`.

### Signature — Glass bento + gradient data-viz
The dashboard's identity: a bento grid of glass cards (24px gap, 124px row min-height) whose data-viz — sparklines, the acceptance ring, throughput chart line/area, plan/language/tool bars — is drawn in the brand gradient. This is where the gradient earns its keep as information, and the single most important pattern to keep consistent across any embedded "mini-dashboard" (marketing preview frames must reuse it, not re-derive it).

## 6. Do's and Don'ts

### Do:
- **Do** float glass over the ambient field: `--surface` + `backdrop-filter: blur(22px)` + `--card-shadow` + `--stroke` border + top-edge sheen. One canonical recipe.
- **Do** keep Zühlke purple (`#985b9c`) as the only chromatic accent in chrome; everything else is a purple-tinted neutral.
- **Do** reserve the gradient for identity surfaces and data viz, always over a multiply overlay + radial sheen so text stays legible.
- **Do** verify ≥4.5:1 body / ≥3:1 large text against the *rendered* glass in both themes; hold placeholder text to 4.5:1 too.
- **Do** ship every interactive state with a visible focus ring (`box-shadow: 0 0 0 4px var(--chip)`), and give every animation a `prefers-reduced-motion` end-state fallback.
- **Do** build new controls on the canonical primitives: `.control` inputs, `.opt-row` option rows, one `.btn` family, one documented z-scale.
- **Do** build plain forms from the v2 anatomy — `FormField` (label → control → hint/error) in `FormRow`s, closed by `FormActions` — and pick from the four-step button ramp (primary → secondary → ghost → text) instead of inventing in-between emphasis.
- **Do** reach for the house `Modal` (small/medium/large/fullscreen) and `AppHeader` before composing raw `Dialog`/`AppBar` chrome.

### Don't:
- **Don't** build a **generic SaaS dashboard**: no hero-metric template (big number / small label / gradient accent, repeated), no identical icon-heading-text card grids, no flat gray Bootstrap/Tailwind-default chrome.
- **Don't** drift into the **corporate/consultancy stiff** register — no buttoned-up formality, stock photography, or jargon. Warm and human first.
- **Don't** use `background-clip: text` gradient text in product UI (dashboard, forms) — it fails contrast. It is licensed *only* for hero-scale marketing display type on the landing pages.
- **Don't** use a `border-left`/`border-right` colored stripe as a card or callout accent. The only sanctioned left-edge marker is the 4px gradient *active-nav* indicator.
- **Don't** use the gradient as a content-card background, and never nest a card inside a card.
- **Don't** ship flat fills with hard gray drop shadows — if it looks like a 2014 app, the blur is missing and the shadow is too dark.
- **Don't** float labels inside product-form fields, stack a hint under an error, or let a textarea's inner padding drift from the single-line `14px 15px` — the v2 field anatomy is the contract.
- **Don't** perpetuate the known drift: **one** glass recipe (not redefined per file), **one** z-index scale (not `--z-nav` at 30/40/100 across surfaces), **one** button family (not `.btn` vs `.fbtn`), **one** `--pane-grad` value with a real dark-theme variant, **one** meaning per class name (rename the two `.sec-head`s and three `.steps`). `form.html` wins where controls diverge.
