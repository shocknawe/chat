/**
 * The single authoritative-message upsert path (task 6.4), plus the REST
 * history merge (task 6.7).
 *
 * EVERY server message that arrives outside a full history fetch — via
 * `MESSAGE_ACK` (6.4) or `NEW_MESSAGE` (6.5) — enters the cache through
 * {@link upsertAuthoritativeMessage}, which also advances the conversation's
 * rail preview through {@link patchConversationPreview} (task 4.4). History
 * fetches commit through
 * {@link fetchMergedHistory}, which reconciles the REST response against
 * whatever realtime upserts landed while the request was in flight. One
 * ordering invariant ({@link compareMessages}), one dedupe key: the
 * server-assigned message id.
 */

import { fetchMessages, type Conversation, type Message } from './api'
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
 * Slice 3 (task 4.4): advances the rail preview of ONE conversation to
 * `message` — no listing refetch, no selection change. Called for both
 * acknowledgement (MESSAGE_ACK) and receipt (NEW_MESSAGE) of an authoritative
 * message.
 *
 * Tail invariant (spec "preview matches history tail"): the stored
 * `lastMessage` is only replaced when the incoming message is AT or AFTER the
 * current tail under the history ordering, so an out-of-order or duplicate
 * delivery can never roll the preview backwards.
 *
 * Unknown conversation ids are deliberately ignored (a no-op, not an add):
 * conversations only ever enter the rail through the listing, `POST
 * /api/conversations`, or CONVERSATION_CREATED (task 3.9) — a MESSAGE event
 * alone carries no participants, and a row with no label would be invented.
 * The `userId` argument is explicit (mirrors the query key), so an event
 * handled under one identity can never write into another's cache.
 */
export function patchConversationPreview(userId: string, message: Message): void {
  queryClient.setQueryData<Conversation[]>(['conversations', userId], (existing) => {
    if (existing === undefined) return existing
    const index = existing.findIndex((c) => c.id === message.conversationId)
    if (index === -1) return existing
    const current = existing[index]
    if (current === undefined) return existing
    // `?? undefined` honours the task-4.0 absent === null contract for cache
    // data that arrived through paths without the protocol parser.
    const currentTail = current.lastMessage ?? undefined
    if (currentTail !== undefined && compareMessages(currentTail, message) > 0) {
      return existing
    }
    return existing.map((c) => (c.id === message.conversationId ? { ...c, lastMessage: message } : c))
  })
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
 *
 * Task 4.4 companion: the merged history's tail ALSO advances the
 * conversation's rail preview — a commit that lands messages the realtime
 * path never announced here (a reconnect recovery fetch, or a first visit to
 * a conversation whose history moved while absent) would otherwise leave a
 * stale preview behind. The tail-guarded patch keeps "preview equals the last
 * message of its history" true across every delivery path, and it is still a
 * cache patch: the listing is never refetched for it.
 */
export async function fetchMergedHistory(userId: string, conversationId: string): Promise<Message[]> {
  const serverHistory = await fetchMessages(userId, conversationId)
  const currentCached = queryClient.getQueryData<Message[]>(['messages', userId, conversationId])
  const merged = mergeHistoryWithCache(serverHistory, currentCached)
  const tail = merged.at(-1)
  if (tail !== undefined) {
    patchConversationPreview(userId, tail)
  }
  return merged
}
