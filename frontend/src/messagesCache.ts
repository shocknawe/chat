/**
 * The single authoritative-message upsert path (task 6.4).
 *
 * EVERY server message that arrives outside a full history fetch — today via
 * `MESSAGE_ACK`, later via `NEW_MESSAGE` (6.5) and the history merge (6.7) —
 * enters the cache through {@link upsertAuthoritativeMessage}. One entry
 * point, one ordering invariant ({@link compareMessages}), one dedupe key:
 * the server-assigned message id.
 */

import type { Message } from './api'
import { compareMessages } from './messageOrder'
import { queryClient } from './queryClient'

/**
 * Inserts-or-replaces `message` in the `['messages', userId, conversationId]`
 * query data, keyed by the server-assigned `message.id`, keeping the list in
 * `(createdAt, id)` ascending order.
 *
 * - Idempotent: an id already present is replaced in place (server messages
 *   are immutable, so the resulting list is unchanged), never appended twice.
 *   This is what makes "the same authoritative message observed through more
 *   than one REST or WebSocket path renders exactly once" hold.
 * - Position: existing entries are scanned for the first one that sorts AFTER
 *   the incoming message and the message is spliced in there, so callers never
 *   worry about arrival order.
 * - A `userId` partitioning argument is explicit (mirrors the query key), so
 *   an event handled under one identity can never write into another's cache.
 */
export function upsertAuthoritativeMessage(userId: string, message: Message): void {
  queryClient.setQueryData<Message[]>(
    ['messages', userId, message.conversationId],
    (prev) => {
      const list = prev ?? []
      const existingIndex = list.findIndex((m) => m.id === message.id)
      if (existingIndex !== -1) {
        // Replace in place: position is keyed by (createdAt, id) and both are
        // immutable per server id, so the order is already correct.
        return list.map((m) => (m.id === message.id ? message : m))
      }
      const insertAt = list.findIndex((m) => compareMessages(m, message) > 0)
      return insertAt === -1
        ? [...list, message]
        : [...list.slice(0, insertAt), message, ...list.slice(insertAt)]
    },
  )
}
