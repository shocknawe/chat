/**
 * OpenSpec task 2.5 — coverage for the app-shell spec's two theme scenarios:
 *
 * - "OS preference is the default": with no stored override, `initialTheme`
 *   resolves from `prefers-color-scheme`.
 * - "Override persists": an explicit toggle is written to `localStorage` and
 *   wins over the OS preference on the next read (i.e. the next reload).
 *
 * `theme.ts` is pure browser-platform logic (storage + `matchMedia` + the
 * document attribute), so the hook that consumes it needs no separate
 * component test.
 */
/// <reference types="node" />
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { applyTheme, initialTheme, readStoredTheme, readSystemTheme, storeTheme } from './theme'

const originalMatchMedia = window.matchMedia

/** Installs a matchMedia stub whose dark preference is caller-controlled. */
function stubPrefersDark(prefersDark: boolean): void {
  window.matchMedia = ((query: string): MediaQueryList =>
    ({
      matches: query === '(prefers-color-scheme: dark)' && prefersDark,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList) as typeof window.matchMedia
}

afterEach(() => {
  window.matchMedia = originalMatchMedia
  window.localStorage.clear()
})

describe('theme default and persistence (task 2.5)', () => {
  it('with no stored override, the OS preference is the default', () => {
    stubPrefersDark(true)
    expect(readSystemTheme()).toBe('dark')
    expect(initialTheme()).toBe('dark')

    stubPrefersDark(false)
    expect(initialTheme()).toBe('light')
  })

  it('an explicit override persists and beats the OS preference', () => {
    stubPrefersDark(false)
    expect(initialTheme()).toBe('light')

    storeTheme('dark')
    expect(readStoredTheme()).toBe('dark')
    expect(initialTheme()).toBe('dark')

    // And the reverse direction, so a toggle back is equally durable.
    storeTheme('light')
    expect(initialTheme()).toBe('light')
  })

  it('a corrupted stored value is ignored rather than honoured', () => {
    window.localStorage.setItem('chat.theme', '.sepia')
    expect(readStoredTheme()).toBeNull()
  })

  it('applyTheme writes the token attribute and the native color-scheme', () => {
    applyTheme('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.style.colorScheme).toBe('dark')
    expect(document.body.dataset.theme).toBe('dark')
  })

  /**
   * Contract test for the defect that originally shipped this file: the
   * stylesheet's per-component DARK rules target `body[data-theme='dark']`
   * (the approved one-shot's form), while only the token block flips on the
   * root. If that CSS convention ever changes, this test fails and forces
   * `applyTheme` (and this expectation) to be updated with it — instead of
   * dark mode silently rendering near-white text on white surfaces.
   */
  it("the stylesheet's dark surface rules target an element applyTheme actually tags", async () => {
    // The stylesheet is read from disk rather than imported: vitest's
    // `css: false` pipeline stubs CSS modules (and even `?raw` variants) to an
    // empty string, which would make this assertion vacuous.
    const css = await readFile(resolve(process.cwd(), 'src/index.css'), 'utf8')
    expect(css).toMatch(/body\[data-theme='dark'\]/)

    // And the attribute really is where the selectors expect it after a
    // toggle, not merely on `documentElement`.
    applyTheme('dark')
    const surface = document.createElement('div')
    surface.className = 'thread-pane'
    document.body.appendChild(surface)
    expect(surface.matches("body[data-theme='dark'] .thread-pane")).toBe(true)
    surface.remove()
  })
})