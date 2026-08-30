/**
 * OpenSpec task 7.3, area 2: authoritative-id deduplication.
 *
 * Covers `upsertAuthoritativeMessage` (same server id observed twice renders
 * once; insertion order is (createdAt, id), independent of arrival order) and
 * `mergeHistoryWithCache` (union by id, server wins a collision, mid-flight
 * cache-only messages are preserved, result is sorted). Both route through
 * the shared `queryClient` singleton, so it is cleared before every test.
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Conversation, Message } from './api'
import * as api from './api'
import {
  fetchMergedHistory,
  mergeHistoryWithCache,
  patchConversationPreview,
  upsertAuthoritativeMessage,
} from './messagesCache'
import { queryClient } from './queryClient'

const USER_ID = 'user-1'
const CONVERSATION_ID = 'conv-1'

function msg(id: string, createdAt: string, overrides: Partial<Message> = {}): Message {
  return {
    id,
    conversationId: CONVERSATION_ID,
    senderId: USER_ID,
    content: `content-${id}`,
    createdAt,
    ...overrides,
  }
}

function cached(): Message[] | undefined {
  return queryClient.getQueryData<Message[]>(['messages', USER_ID, CONVERSATION_ID])
}

afterEach(() => {
  queryClient.clear()
  vi.restoreAllMocks()
})

describe('upsertAuthoritativeMessage', () => {
  it('the same server id observed twice renders exactly once', () => {
    const first = msg('m1', '2026-08-30T12:00:00.000Z', { content: 'v1' })
    const again = msg('m1', '2026-08-30T12:00:00.000Z', { content: 'v1' })

    upsertAuthoritativeMessage(USER_ID, first)
    upsertAuthoritativeMessage(USER_ID, again)

    const list = cached()
    expect(list).toHaveLength(1)
    expect(list?.[0]).toEqual(again)
  })

  it('replaces the existing entry in place when the same id is re-observed', () => {
    // Server messages are immutable in the MVP, but the upsert still replaces
    // by reference so a later delivery of the identical id is never appended.
    const a = msg('m1', '2026-08-30T12:00:00.000Z')
    const b = msg('m2', '2026-08-30T12:00:01.000Z')
    upsertAuthoritativeMessage(USER_ID, a)
    upsertAuthoritativeMessage(USER_ID, b)
    upsertAuthoritativeMessage(USER_ID, a)

    const list = cached()
    expect(list).toHaveLength(2)
    expect(list?.map((m) => m.id)).toEqual(['m1', 'm2'])
  })

  it('inserts in (createdAt, id) ascending order regardless of arrival order', () => {
    const early = msg('m1', '2026-08-30T12:00:00.000Z')
    const mid = msg('m2', '2026-08-30T12:00:01.000Z')
    const late = msg('m3', '2026-08-30T12:00:02.000Z')

    // Arrive out of chronological order: late, early, mid.
    upsertAuthoritativeMessage(USER_ID, late)
    upsertAuthoritativeMessage(USER_ID, early)
    upsertAuthoritativeMessage(USER_ID, mid)

    expect(cached()?.map((m) => m.id)).toEqual(['m1', 'm2', 'm3'])
  })

  it('partitions strictly by userId — one identity can never write into another’s cache', () => {
    const message = msg('m1', '2026-08-30T12:00:00.000Z')
    upsertAuthoritativeMessage('user-a', message)
    upsertAuthoritativeMessage('user-b', message)

    expect(queryClient.getQueryData(['messages', 'user-a', CONVERSATION_ID])).toEqual([message])
    expect(queryClient.getQueryData(['messages', 'user-b', CONVERSATION_ID])).toEqual([message])
  })
})

describe('mergeHistoryWithCache', () => {
  it('unions by id — each server id appears exactly once', () => {
    const a = msg('m1', '2026-08-30T12:00:00.000Z')
    const b = msg('m2', '2026-08-30T12:00:01.000Z')
    const merged = mergeHistoryWithCache([a, b], [a])
    expect(merged.map((m) => m.id)).toEqual(['m1', 'm2'])
  })

  it('the server version wins an id collision over the cached version', () => {
    const staleCached = msg('m1', '2026-08-30T12:00:00.000Z', { content: 'stale' })
    const serverVersion = msg('m1', '2026-08-30T12:00:00.000Z', { content: 'authoritative' })
    const merged = mergeHistoryWithCache([serverVersion], [staleCached])
    expect(merged).toEqual([serverVersion])
  })

  it('preserves mid-flight cache-only messages absent from the server response', () => {
    const historyMessage = msg('m1', '2026-08-30T12:00:00.000Z')
    const midFlightRealtimeMessage = msg('m2', '2026-08-30T12:00:05.000Z')
    // Simulates: history fetch resolved with only m1, but a NEW_MESSAGE (m2)
    // upsert landed in the cache while the request was in flight.
    const merged = mergeHistoryWithCache([historyMessage], [historyMessage, midFlightRealtimeMessage])
    expect(merged.map((m) => m.id)).toEqual(['m1', 'm2'])
  })

  it('sorts the result by (createdAt, id) regardless of input order', () => {
    const a = msg('m1', '2026-08-30T12:00:00.000Z')
    const b = msg('m2', '2026-08-30T12:00:01.000Z')
    const c = msg('m3', '2026-08-30T12:00:02.000Z')
    const merged = mergeHistoryWithCache([c, a], [b])
    expect(merged.map((m) => m.id)).toEqual(['m1', 'm2', 'm3'])
  })

  it('treats an undefined cache (no prior query data) as empty', () => {
    const a = msg('m1', '2026-08-30T12:00:00.000Z')
    expect(mergeHistoryWithCache([a], undefined)).toEqual([a])
  })
})

describe('fetchMergedHistory', () => {
  it('merges the REST response with whatever the cache holds at resolution time', async () => {
    const historyMessage = msg('m1', '2026-08-30T12:00:00.000Z')
    vi.spyOn(api, 'fetchMessages').mockResolvedValue([historyMessage])

    // A realtime upsert lands in the cache before the history fetch resolves.
    const midFlightMessage = msg('m2', '2026-08-30T12:00:05.000Z')
    upsertAuthoritativeMessage(USER_ID, midFlightMessage)

    const result = await fetchMergedHistory(USER_ID, CONVERSATION_ID)
    expect(result.map((m) => m.id)).toEqual(['m1', 'm2'])
  })
})

/**
 * OpenSpec task 4.4 (spec `conversation-previews`, "previews update without a
 * listing refetch"): the conversations-array patch that advances ONE
 * conversation's rail preview on MESSAGE_ACK / NEW_MESSAGE.
 */
describe('patchConversationPreview', () => {
  function listedConversations(conversations: Conversation[]): void {
    queryClient.setQueryData<Conversation[]>(['conversations', USER_ID], conversations)
  }

  function listed(): Conversation[] | undefined {
    return queryClient.getQueryData<Conversation[]>(['conversations', USER_ID])
  }

  it('sets the named conversation’s lastMessage in place, leaving siblings untouched', () => {
    const other: Conversation = { id: 'conv-2', participants: [] }
    listedConversations([
      { id: CONVERSATION_ID, participants: [] },
      other,
    ])

    patchConversationPreview(USER_ID, msg('m1', '2026-08-30T12:00:00.000Z'))

    const conversations = listed()
    expect(conversations?.[0]?.lastMessage?.id).toBe('m1')
    expect(conversations?.[1]).toEqual(other)
  })

  it('is a no-op for a conversation id the rail does not list', () => {
    const listedConversationsSnapshot: Conversation[] = [{ id: CONVERSATION_ID, participants: [] }]
    listedConversations(listedConversationsSnapshot)

    patchConversationPreview(
      USER_ID,
      msg('m1', '2026-08-30T12:00:00.000Z', { conversationId: 'conv-unknown' }),
    )

    expect(listed()).toEqual(listedConversationsSnapshot)
  })

  it('never rolls the preview backwards under the (createdAt, id) ordering (duplicate/out-of-order delivery)', () => {
    const tail = msg('m2', '2026-08-30T12:00:05.000Z')
    listedConversations([{ id: CONVERSATION_ID, participants: [], lastMessage: tail }])

    const older = msg('m1', '2026-08-30T12:00:00.000Z')
    patchConversationPreview(USER_ID, older)
    expect(listed()?.[0]?.lastMessage).toEqual(tail)
    // The same tail re-delivered is an equal-order replacement: harmless.
    patchConversationPreview(USER_ID, tail)
    expect(listed()?.[0]?.lastMessage).toEqual(tail)
  })
})
