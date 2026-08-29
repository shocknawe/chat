/**
 * Window-scoped demo identity storage.
 *
 * The selected user id is kept in `sessionStorage` — never `localStorage` —
 * so a reload within one browser window keeps the identity while separate
 * windows each hold their own (user-directory spec: "Selection is isolated
 * per browser window"). This is an identity selector for the local demo,
 * not a credential store.
 */

const STORAGE_KEY = 'chat.currentUserId'

/** Returns the stored user id for this window, or null when none is set. */
export function readStoredUserId(): string | null {
  try {
    return window.sessionStorage.getItem(STORAGE_KEY)
  } catch {
    // sessionStorage unavailable (e.g. blocked storage) — identity simply
    // falls back to React state for the lifetime of the page.
    return null
  }
}

export function storeUserId(userId: string): void {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, userId)
  } catch {
    // Non-fatal: React state still carries the selection for this page load.
  }
}

export function clearStoredUserId(): void {
  try {
    window.sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Non-fatal: see above.
  }
}
