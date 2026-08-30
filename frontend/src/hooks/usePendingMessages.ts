import { useCallback, useState } from 'react'
import type { ErrorCode } from '../realtime'

/**
 * Pending outbound message state (OpenSpec task 6.3, slice 5 tasks 6.6/6.8).
 *
 * Pending messages live OUTSIDE the TanStack Query messages cache: they have
 * no server id yet, so they cannot participate in the authoritative-message
 * keyed merge until 6.4 reconciles them by `clientMessageId` (MESSAGE_ACK →
 * `removePending` + cache upsert, ERROR → `markPendingFailed`).
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
  /**
   * Task 6.6: a correlated rejection RETAINS its wire code and server reason
   * against the message. The UI words the rejection from `errorCode` (see
   * `rejectionWording`); `errorReason` is retained for diagnostics only and
   * is NEVER rendered as product copy (the spec words rejections from the
   * code, not from the reason text).
   */
  errorCode?: ErrorCode
  errorReason?: string
}

export interface PendingRejection {
  code: ErrorCode
  reason: string
}

export interface UsePendingMessagesResult {
  pendingMessages: PendingMessage[]
  /** Append a freshly submitted message (FIFO render order preserved). */
  addPending: (message: PendingMessage) => void
  /** Task 6.4: drop the item once its MESSAGE_ACK lands in the message cache. */
  removePending: (clientMessageId: string) => void
  /**
   * Task 6.4/6.6: correlate a rejection and flip the item to `failed`,
   * retaining the code and reason against it.
   */
  markPendingFailed: (clientMessageId: string, rejection: PendingRejection) => void
  /**
   * Task 6.8: flip a previously rejected item back to the sending state for
   * a MANUAL retry (the only retry path there is — nothing ever re-submits
   * automatically). The retained rejection is cleared: it described the LAST
   * attempt, and until the server answers again the honest state is
   * "pending" with no rejection attached.
   */
  markPendingRetry: (clientMessageId: string) => void
}

export function usePendingMessages(): UsePendingMessagesResult {
  const [pendingMessages, setPendingMessages] = useState<PendingMessage[]>([])

  const addPending = useCallback((message: PendingMessage) => {
    setPendingMessages((prev) => [...prev, message])
  }, [])

  const removePending = useCallback((clientMessageId: string) => {
    setPendingMessages((prev) => prev.filter((m) => m.clientMessageId !== clientMessageId))
  }, [])

  const markPendingFailed = useCallback((clientMessageId: string, rejection: PendingRejection) => {
    setPendingMessages((prev) =>
      prev.map((m) =>
        m.clientMessageId === clientMessageId
          ? { ...m, status: 'failed', errorCode: rejection.code, errorReason: rejection.reason }
          : m,
      ),
    )
  }, [])

  const markPendingRetry = useCallback((clientMessageId: string) => {
    setPendingMessages((prev) =>
      prev.map((m) => {
        if (m.clientMessageId !== clientMessageId) return m
        const { errorCode: _errorCode, errorReason: _errorReason, ...rest } = m
        return { ...rest, status: 'pending' }
      }),
    )
  }, [])

  return { pendingMessages, addPending, removePending, markPendingFailed, markPendingRetry }
}