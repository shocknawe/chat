import { useCallback, useState } from 'react'

/**
 * Pending outbound message state (OpenSpec task 6.3).
 *
 * Pending messages live OUTSIDE the TanStack Query messages cache: they have
 * no server id yet, so they cannot participate in the authoritative-message
 * keyed merge until task 6.4 reconciles them by `clientMessageId` (MESSAGE_ACK
 * → `removePending` + cache upsert, ERROR → `markPendingFailed`).
 *
 * Placement: this hook is a plain lifted-state store — its consumer is
 * SignedInShell, the common ancestor of ThreadPane (renders the pending
 * bubbles) and the socket event handlers (mutate it in 6.4). A module-scoped
 * store was rejected: SignedInShell is keyed on `user.id`, so React state
 * resets automatically on identity switch, whereas a singleton would leak
 * one user's pendings into another's window.
 */
export interface PendingMessage {
  /** Correlation/idempotency token; never the server message id. */
  clientMessageId: string
  conversationId: string
  senderId: string
  /** Boundary-trimmed content exactly as sent on the wire (display = validated). */
  content: string
  /** Local wall-clock instant of submission; superseded by the server createdAt on ack. */
  createdAt: string
  status: 'pending' | 'failed'
}

export interface UsePendingMessagesResult {
  pendingMessages: PendingMessage[]
  /** Append a freshly submitted message (FIFO render order preserved). */
  addPending: (message: PendingMessage) => void
  /** Task 6.4: drop the item once its MESSAGE_ACK lands in the message cache. */
  removePending: (clientMessageId: string) => void
  /** Task 6.4: correlate a rejection and flip the item to `failed`. */
  markPendingFailed: (clientMessageId: string) => void
}

export function usePendingMessages(): UsePendingMessagesResult {
  const [pendingMessages, setPendingMessages] = useState<PendingMessage[]>([])

  const addPending = useCallback((message: PendingMessage) => {
    setPendingMessages((prev) => [...prev, message])
  }, [])

  const removePending = useCallback((clientMessageId: string) => {
    setPendingMessages((prev) => prev.filter((m) => m.clientMessageId !== clientMessageId))
  }, [])

  const markPendingFailed = useCallback((clientMessageId: string) => {
    setPendingMessages((prev) =>
      prev.map((m) => (m.clientMessageId === clientMessageId ? { ...m, status: 'failed' } : m)),
    )
  }, [])

  return { pendingMessages, addPending, removePending, markPendingFailed }
}
