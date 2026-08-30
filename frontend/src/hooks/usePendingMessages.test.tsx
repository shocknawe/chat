/**
 * OpenSpec task 7.3, area 3: pending → sent/failed reconciliation.
 *
 * `usePendingMessages` is the real seam for this reconciliation: `App.tsx`'s
 * MESSAGE_ACK handler calls `removePending` (the bubble is superseded by the
 * authoritative message entering the cache — covered separately in
 * `messagesCache.test.ts`) and its ERROR handler calls `markPendingFailed`.
 * These tests drive the hook itself with `renderHook`/`act`.
 */
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { usePendingMessages, type PendingMessage } from './usePendingMessages'

function pending(clientMessageId: string, overrides: Partial<PendingMessage> = {}): PendingMessage {
  return {
    clientMessageId,
    conversationId: 'conv-1',
    senderId: 'user-1',
    content: `draft-${clientMessageId}`,
    createdAt: '2026-08-30T12:00:00.000Z',
    status: 'pending',
    ...overrides,
  }
}

describe('usePendingMessages', () => {
  it('addPending appends a bubble in FIFO submission order', () => {
    const { result } = renderHook(() => usePendingMessages())

    act(() => result.current.addPending(pending('c1')))
    act(() => result.current.addPending(pending('c2')))

    expect(result.current.pendingMessages.map((m) => m.clientMessageId)).toEqual(['c1', 'c2'])
    expect(result.current.pendingMessages.every((m) => m.status === 'pending')).toBe(true)
  })

  it('removePending drops the bubble keyed by clientMessageId (the MESSAGE_ACK path)', () => {
    const { result } = renderHook(() => usePendingMessages())

    act(() => result.current.addPending(pending('c1')))
    act(() => result.current.addPending(pending('c2')))
    act(() => result.current.removePending('c1'))

    expect(result.current.pendingMessages.map((m) => m.clientMessageId)).toEqual(['c2'])
  })

  it('removePending is a no-op when the id has no match (already reconciled)', () => {
    const { result } = renderHook(() => usePendingMessages())

    act(() => result.current.addPending(pending('c1')))
    act(() => result.current.removePending('does-not-exist'))

    expect(result.current.pendingMessages.map((m) => m.clientMessageId)).toEqual(['c1'])
  })

  it('markPendingFailed flips only the matching bubble to failed, without removing it (the ERROR path)', () => {
    const { result } = renderHook(() => usePendingMessages())

    act(() => result.current.addPending(pending('c1')))
    act(() => result.current.addPending(pending('c2')))
    act(() => result.current.markPendingFailed('c1'))

    expect(result.current.pendingMessages).toEqual([
      pending('c1', { status: 'failed' }),
      pending('c2'),
    ])
  })

  it('markPendingFailed is a harmless no-op when no pending item matches (ack already won the race)', () => {
    const { result } = renderHook(() => usePendingMessages())

    act(() => result.current.addPending(pending('c1')))
    act(() => result.current.removePending('c1')) // simulate the ack landing first
    act(() => result.current.markPendingFailed('c1')) // a later, uncorrelated error retry

    expect(result.current.pendingMessages).toEqual([])
  })

  it('a failed bubble is never retried or auto-removed by the hook itself', () => {
    const { result } = renderHook(() => usePendingMessages())

    act(() => result.current.addPending(pending('c1')))
    act(() => result.current.markPendingFailed('c1'))
    act(() => result.current.markPendingFailed('c1')) // idempotent

    expect(result.current.pendingMessages).toEqual([pending('c1', { status: 'failed' })])
  })
})
