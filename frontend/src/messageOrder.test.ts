/**
 * OpenSpec task 7.3, area 2 (ordering underpinning):
 * `compareMessages` — numeric epoch compare on `createdAt` with an `id`
 * tiebreak, ascending. Covers the variable-width-fractional-seconds case a
 * lexical compare would misorder.
 */
import { describe, expect, it } from 'vitest'
import type { Message } from './api'
import { compareMessages } from './messageOrder'

function msg(id: string, createdAt: string): Message {
  return { id, conversationId: 'conv-1', senderId: 'user-1', content: id, createdAt }
}

describe('compareMessages', () => {
  it('orders strictly by numeric epoch when timestamps differ', () => {
    const earlier = msg('a', '2026-08-30T12:00:00.000Z')
    const later = msg('b', '2026-08-30T12:00:01.000Z')
    expect(compareMessages(earlier, later)).toBeLessThan(0)
    expect(compareMessages(later, earlier)).toBeGreaterThan(0)
  })

  it('breaks ties on id (localeCompare) when timestamps are numerically equal', () => {
    const a = msg('a', '2026-08-30T12:00:00.000Z')
    const b = msg('b', '2026-08-30T12:00:00.000Z')
    expect(compareMessages(a, b)).toBeLessThan(0)
    expect(compareMessages(b, a)).toBeGreaterThan(0)
    expect(compareMessages(a, a)).toBe(0)
  })

  it('compares numerically, not lexically, when a trimmed all-zero fraction meets a fractional one', () => {
    // Jackson trims an all-zero fractional part entirely, so an exactly-on-
    // the-second instant serializes WITHOUT a '.' at all. A raw string
    // compare then puts it AFTER any fractional-second instant in the same
    // second, because '.' (0x2E) sorts below 'Z' (0x5A) — a real misorder a
    // lexical compare would produce and the numeric epoch compare must not.
    const onTheSecond = msg('a', '2026-08-30T12:00:12Z') // 12.000s, no fraction
    const oneMsLater = msg('b', '2026-08-30T12:00:12.001Z') // 12.001s

    // Sanity check: plain string comparison gets this backwards.
    expect(onTheSecond.createdAt < oneMsLater.createdAt).toBe(false)

    // The real comparator gets it right: onTheSecond is chronologically first.
    expect(compareMessages(onTheSecond, oneMsLater)).toBeLessThan(0)
    expect(compareMessages(oneMsLater, onTheSecond)).toBeGreaterThan(0)
  })
})
