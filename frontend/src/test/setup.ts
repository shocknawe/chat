// Vitest global setup (loaded once per test file via `test.setupFiles`).
// The `/vitest` subpath extends `expect` with the jest-dom matchers and
// ships types for them — no manual `expect.extend` needed.
import '@testing-library/jest-dom/vitest'

// jsdom does not implement `window.matchMedia` at all (it is simply
// `undefined`, not a stub) — real browsers always have it. This is a
// default, inert fallback (`matches: false`, no-op listener registration)
// so every component that reads a media query (theme default, compact
// viewport, reduced motion) can render under jsdom without throwing.
// Individual tests that need to *control* a query's result (e.g. "OS prefers
// dark", "viewport is compact") override `window.matchMedia` themselves.
if (typeof window.matchMedia !== 'function') {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList
}

// This vitest build exposes `sessionStorage` but leaves `localStorage`
// undefined: Node's built-in localStorage (which vitest delegates to) requires
// a `--localstorage-file` flag to exist. Real browsers always have it. This is
// a minimal in-memory shim, only installed when missing, so code that treats
// `localStorage` as a durable store (task 2.5's theme override) can be tested
// against the same semantics — writes are visible to later reads, including
// across a simulated reload.
if (typeof window.localStorage === 'undefined') {
  const data = new Map<string, string>()
  const shim: Storage = {
    get length(): number {
      return data.size
    },
    clear: () => data.clear(),
    getItem: (key: string): string | null => data.get(key) ?? null,
    key: (index: number): string | null => Array.from(data.keys())[index] ?? null,
    removeItem: (key: string): void => {
      data.delete(key)
    },
    setItem: (key: string, value: string): void => {
      data.set(key, String(value))
    },
  }
  Object.defineProperty(window, 'localStorage', { value: shim, writable: true })
}
