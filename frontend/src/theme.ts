/**
 * Theme (task 2.5): light and dark forms, defaulting to the OS preference,
 * with an explicit user override persisted in `localStorage` (deliberately
 * NOT `sessionStorage` — a theme choice is a durable device preference, not
 * a per-window demo identity like `identity.ts`'s selections).
 *
 * The active theme is applied as `data-theme` on the root element. The CSS
 * cascade (`index.css`) is layered so this attribute always wins over the
 * `prefers-color-scheme` media query once JavaScript has run, while the
 * media query still governs the brief pre-hydration paint and any
 * environment where storage is unavailable.
 */

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'chat.theme'

function isTheme(value: string | null): value is Theme {
  return value === 'light' || value === 'dark'
}

/** The user's explicit override, or null if none has been chosen yet. */
export function readStoredTheme(): Theme | null {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY)
    return isTheme(value) ? value : null
  } catch {
    // Storage unavailable (blocked, private mode quota, etc.) — the caller
    // falls back to the OS preference for this page load.
    return null
  }
}

export function storeTheme(theme: Theme): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, theme)
  } catch {
    // Non-fatal: the choice still applies to React state for this page load.
  }
}

/** The operating system's current preference, read once at call time. */
export function readSystemTheme(): Theme {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

/** The theme to render on first paint: an explicit override, else the OS default. */
export function initialTheme(): Theme {
  return readStoredTheme() ?? readSystemTheme()
}

/**
 * Applies the theme to the document so the CSS custom-property layer
 * (`index.css`) picks it up, and mirrors it into the native `color-scheme`
 * so browser-drawn UI (scrollbars, form controls) matches too.
 *
 * The attribute is written to BOTH `documentElement` and `body`: the token
 * block flips on the root, but the per-component dark rules (the surfaces and
 * inks that are hard-coded literals in their light forms) follow the approved
 * one-shot's `body[data-theme='dark']` selector form, so the body attribute is
 * load-bearing. Dropping either write leaves a half-darkened shell — the root
 * flipped, the surfaces still light. Guarded for `body` because this can run
 * before the DOM is fully parsed.
 */
export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme
  document.documentElement.style.colorScheme = theme
  if (document.body !== null) {
    document.body.dataset.theme = theme
  }
}
