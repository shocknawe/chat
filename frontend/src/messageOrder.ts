import type { Message } from './api'

/**
 * The ONE message-ordering invariant (task 6.4; also the banked F1 follow-up
 * from the 5.3 review): numeric epoch compare on `createdAt` with an `id`
 * tiebreak, ascending.
 *
 * Why numeric: Jackson emits variable-width fractional seconds for ISO-8601
 * instants (trailing zeros are trimmed: `...:12.5Z` vs `...:12.45Z`), so a
 * lexical compare can misorder two messages within the same second. Both the
 * TanStack messages cache (`messagesCache.ts` upsert) and ThreadPane's
 * defensive render sort route through this comparator, so cache order and
 * render order can never disagree.
 */
export function compareMessages(a: Message, b: Message): number {
  const byTime = Date.parse(a.createdAt) - Date.parse(b.createdAt)
  return byTime !== 0 ? byTime : a.id.localeCompare(b.id)
}
