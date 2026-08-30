/**
 * Window-scoped demo session storage.
 *
 * The selected user id — and, per user, the selected conversation id — are
 * kept in `sessionStorage`, never `localStorage`, so a reload within one
 * browser window restores both while separate windows each hold their own
 * (user-directory spec: "Selection is isolated per browser window"). This is
 * an identity/selection selector for the local demo, not a credential store.
 *
 * Task 5.4: persisting the selected conversation id per user lets a page
 * reload land back in the open conversation, after which TanStack Query
 * re-fetches its history from the server (see App.tsx for the restore flow).
 */

const STORAGE_KEY = 'chat.currentUserId'
const conversationKey = (userId: string): string => `chat.selectedConversationId.${userId}`

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

/**
 * Returns the conversation id stored for this user in this window, or null.
 * Keyed per user so an identity switch can never inherit another user's
 * selection; the value is still validated against the fetched conversation
 * list on restore (App.tsx) before it is trusted.
 */
export function readStoredConversationId(userId: string): string | null {
  try {
    return window.sessionStorage.getItem(conversationKey(userId))
  } catch {
    // sessionStorage unavailable — selection lives in React state only.
    return null
  }
}

export function storeConversationId(userId: string, conversationId: string): void {
  try {
    window.sessionStorage.setItem(conversationKey(userId), conversationId)
  } catch {
    // Non-fatal: React state still carries the selection for this page load.
  }
}

export function clearStoredConversationId(userId: string): void {
  try {
    window.sessionStorage.removeItem(conversationKey(userId))
  } catch {
    // Non-fatal: see above.
  }
}
