/**
 * OpenSpec tasks 4.2–4.3 (spec `conversation-previews`): the rail preview
 * wording rules, unit-level.
 *
 * The App-level suite (`App.test.tsx`) drives the same rules end-to-end
 * through the real rail; these pin the pure `conversationPreview` decision
 * table, including the task-4.0 `absent === null` contract and the "newest
 * activity" nuance of the task-4.3 override.
 */
import { describe, expect, it } from 'vitest'
import type { Conversation, Message } from './api'
import {
  conversationPreview,
  EMPTY_PREVIEW,
  FAILED_PREVIEW,
  SENDING_PREVIEW,
} from './conversationPreview'
import type { PendingMessage } from './hooks/usePendingMessages'

const CONVERSATION_ID = 'conv-1'

function message(createdAt: string, overrides: Partial<Message> = {}): Message {
  return {
    id: `m-${createdAt}`,
    conversationId: CONVERSATION_ID,
    senderId: 'user-b',
    content: 'Latest from Bob',
    createdAt,
    ...overrides,
  }
}

function pending(overrides: Partial<PendingMessage> = {}): PendingMessage {
  return {
    clientMessageId: 'c-1',
    conversationId: CONVERSATION_ID,
    senderId: 'user-a',
    content: 'Hello there',
    createdAt: '2026-08-30T12:00:00.000Z',
    status: 'pending',
    ...overrides,
  }
}

function conversation(lastMessage?: Message): Conversation {
  // The object literal omits the key entirely for the empty case — the exact
  // wire form the backend emits (task 4.0: omission, never a literal null).
  return { id: CONVERSATION_ID, participants: [], ...(lastMessage !== undefined ? { lastMessage } : {}) }
}

describe('conversationPreview — server preview (task 4.2)', () => {
  it('summarises the server-provided preview content', () => {
    expect(conversationPreview(conversation(message('2026-08-30T11:00:00.000Z')), [])).toBe(
      'Latest from Bob',
    )
  })

  it('states "No messages yet" for an empty history', () => {
    expect(conversationPreview(conversation(), [])).toBe(EMPTY_PREVIEW)
    expect(conversationPreview(conversation(), [])).toBe('No messages yet')
  })

  it('treats an absent preview and an explicitly-undefined preview identically (task 4.0)', () => {
    const withKey = conversation(message('2026-08-30T11:00:00.000Z'))
    const cleared: Conversation = { ...withKey, lastMessage: undefined }
    expect(conversationPreview(cleared, [])).toBe(conversationPreview(conversation(), []))
    expect(conversationPreview(cleared, [])).toBe(EMPTY_PREVIEW)
  })

  it('treats a defensive literal null like the absent form (absent === null)', () => {
    // The backend's `non_null` setting guarantees this never appears on the
    // wire; the helper still honours the "absent and null identically" rule.
    const nullPreview = { ...conversation(), lastMessage: null } as unknown as Conversation
    expect(conversationPreview(nullPreview, [])).toBe(EMPTY_PREVIEW)
  })

  it('falls back to the empty state for an empty preview content', () => {
    expect(conversationPreview(conversation(message('2026-08-30T11:00:00.000Z', { content: '' })), [])).toBe(
      EMPTY_PREVIEW,
    )
  })
})

describe('conversationPreview — pending/failed override (task 4.3)', () => {
  it('reads "Sending…" over the server preview while the newest own message awaits acknowledgement', () => {
    const preview = conversationPreview(
      // The server tail predates the submission (the confirmed message BEFORE
      // the one now in flight).
      conversation(message('2026-08-30T11:00:00.000Z')),
      [pending({ createdAt: '2026-08-30T12:00:00.000Z' })],
    )
    expect(preview).toBe(SENDING_PREVIEW)
    expect(preview).not.toBe('Latest from Bob')
  })

  it('reads "Failed to send" over the server preview when the newest own message was rejected', () => {
    expect(
      conversationPreview(conversation(message('2026-08-30T11:00:00.000Z')), [
        pending({ status: 'failed', createdAt: '2026-08-30T12:00:00.000Z' }),
      ]),
    ).toBe(FAILED_PREVIEW)
  })

  it('overrides even when the conversation has no server preview at all (empty history + first message)', () => {
    expect(conversationPreview(conversation(), [pending()])).toBe(SENDING_PREVIEW)
    expect(conversationPreview(conversation(), [pending({ status: 'failed' })])).toBe(FAILED_PREVIEW)
  })

  it('keeps the server preview once something newer is confirmed server-side (superseded pending)', () => {
    // The failed item is no longer the newest activity: a received message
    // stamped after the submission is the tail now.
    expect(
      conversationPreview(conversation(message('2026-08-30T12:00:01.000Z')), [
        pending({ status: 'failed', createdAt: '2026-08-30T12:00:00.000Z' }),
      ]),
    ).toBe('Latest from Bob')
  })

  it('resolves a tie in favour of the override (the submission is not yet superseded)', () => {
    expect(
      conversationPreview(conversation(message('2026-08-30T12:00:00.000Z')), [pending()]),
    ).toBe(SENDING_PREVIEW)
  })

  it('only considers the given conversation’s pending slice', () => {
    expect(
      conversationPreview(conversation(), [
        pending({ conversationId: 'conv-other', createdAt: '2026-08-30T11:00:00.000Z' }),
      ]),
    ).toBe(EMPTY_PREVIEW)
  })

  it('uses the SUBMISSION-order newest pending item, not the first', () => {
    expect(
      conversationPreview(conversation(), [
        pending({ status: 'failed', clientMessageId: 'c-1' }),
        pending({ status: 'pending', clientMessageId: 'c-2', createdAt: '2026-08-30T12:00:01.000Z' }),
      ]),
    ).toBe(SENDING_PREVIEW)
  })
})