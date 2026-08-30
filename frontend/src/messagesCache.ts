/**
 * The single authoritative-message upsert path (task 6.4), plus the REST
 * history merge (task 6.7).
 *
 * EVERY server message that arrives outside a full history fetch — via
 * `MESSAGE_ACK` (6.4) or `NEW_MESSAGE` (6.5) — enters the cache through
 * {@link upsertAuthoritativeMessage}. History fetches commit through
 * {@link fetchMergedHistory}, which reconciles the REST response against
 * whatever realtime upserts landed while the request was in flight. One
 * ordering invariant ({@link compareMessages}), one dedupe key: the
 * server-assigned message id.
 */

import { fetchMessages, type Message } from './api'
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

/**
 * Task 6.7 history/realtime reconciliation. A REST history response is a
 * snapshot from BEFORE the fetch resolved; a realtime upsert (6.4 ack, 6.5
 * NEW_MESSAGE) can have landed in the cache AFTER the request was issued.
 * Blindly committing the response would erase that newer message, so the
 * response is unioned with the current cache instead of overwriting it.
 *
 * Merge invariant: the result contains each server id EXACTLY once, sorted by
 * (createdAt, id) via {@link compareMessages}; on an id collision the SERVER
 * version wins — it is authoritative and supersedes whatever the realtime
 * event carried. Messages present only in the cache (the mid-flight arrivals
 * this merge exists to protect) are kept: the MVP has no message deletion, so
 * no cache entry can be one the server intentionally removed.
 *
 * Known transient (WP8 QA — do NOT report as a bug): pending bubbles are
 * keyed by `clientMessageId` and REST history rows carry no
 * `clientMessageId`, so a refetched history row for a not-yet-acked OWN
 * message renders alongside its pending bubble until the MESSAGE_ACK (or
 * duplicate-ack on retry) arrives and `removePending` drops the bubble. This
 * cannot be eliminated without a DTO correlation field; the window is
 * bounded by the ack round-trip.
 */
export function mergeHistoryWithCache(
  serverHistory: Message[],
  currentCached: Message[] | undefined,
): Message[] {
  const byId = new Map<string, Message>()
  for (const message of currentCached ?? []) {
    byId.set(message.id, message)
  }
  // Server entries are applied SECOND so they win any id collision.
  for (const message of serverHistory) {
    byId.set(message.id, message)
  }
  return [...byId.values()].sort(compareMessages)
}

/**
 * The history `queryFn` (task 6.7): fetch REST history, read whatever the
 * cache holds NOW, and return the merge — TanStack Query then commits the
 * merged list as the query data, so the commit itself can never duplicate or
 * erase a mid-flight realtime message.
 *
 * Why commit-through-return instead of `select` or `onSuccess`: `select` has
 * no access to the previous cache value, and a post-resolve `setQueryData`
 * would double-commit. Returning the merged data makes the query's own
 * commit the single write; `getQueryData` reads the cache synchronously in
 * the microtask chain after the fetch resolves, so no WebSocket event (a
 * macrotask) can land between the read and the commit. Any realtime message
 * arriving at the same instant applies AFTER the commit through the keyed
 * upsert, which dedupes by server id — no duplication either way.
 *
 * The `userId` argument is explicit (mirrors the query key), so the merge can
 * never read or write another identity's cache partition.
 */
export async function fetchMergedHistory(userId: string, conversationId: string): Promise<Message[]> {
  const serverHistory = await fetchMessages(userId, conversationId)
  const currentCached = queryClient.getQueryData<Message[]>(['messages', userId, conversationId])
  return mergeHistoryWithCache(serverHistory, currentCached)
}
