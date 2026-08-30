/**
 * OpenSpec task 7.3 — integration coverage for two areas whose real seam is
 * `App.tsx`'s `SignedInShell`, not a smaller unit:
 *
 * - Area 3 (pending → sent/failed reconciliation): `SignedInShell`'s
 *   `handleMessageAck`/`handleRealtimeError` are the only callers of
 *   `usePendingMessages`' `removePending`/`markPendingFailed` in the app, so
 *   the end-to-end MESSAGE_ACK/ERROR → pending-bubble transition is exercised
 *   here (`usePendingMessages.test.tsx` covers the hook's own state machine
 *   in isolation).
 * - Area 5 (missed-history refresh): the reconnect → `invalidateQueries` →
 *   `fetchMergedHistory` wiring lives in `SignedInShell`'s reconnect effect
 *   (NOT in `useChatSocket.ts`, which only mirrors connection state) — this
 *   is that real seam, verified for both "no refetch on the initial connect"
 *   and "a newer WebSocket message survives the merge".
 *
 * The realtime transport is faked at its lowest edge, `createChatSocket`
 * (already covered unit-by-unit in `chatSocket.test.ts`), so `useChatSocket`
 * and all of `SignedInShell`'s real production wiring run untouched.
 */
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import * as api from './api'
import type { Conversation, Message, User } from './api'
import { queryClient } from './queryClient'
import * as realtime from './realtime'
import type { ChatSocket, ChatSocketOptions } from './realtime'

const USER_A: User = { id: 'user-a', displayName: 'Alice' }
const USER_B: User = { id: 'user-b', displayName: 'Bob' }
const CONVERSATION: Conversation = { id: 'conv-1', participants: [USER_A, USER_B] }

function msg(id: string, createdAt: string, overrides: Partial<Message> = {}): Message {
  return {
    id,
    conversationId: CONVERSATION.id,
    senderId: USER_B.id,
    content: `content-${id}`,
    createdAt,
    ...overrides,
  }
}

interface CapturedSocket {
  options: ChatSocketOptions
  sendMessage: ReturnType<typeof vi.fn>
}

let captured: CapturedSocket[] = []

function installFakeChatSocket(): void {
  vi.spyOn(realtime, 'createChatSocket').mockImplementation((options: ChatSocketOptions): ChatSocket => {
    const sendMessage = vi.fn()
    captured.push({ options, sendMessage })
    return {
      sendMessage,
      pendingCount: () => 0,
      getState: () => 'connecting',
      terminate: vi.fn(),
    }
  })
}

async function renderSignedInOnConversation(): Promise<ReturnType<typeof userEvent.setup>> {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: 'Alice' }))
  await user.click(await screen.findByRole('button', { name: 'Bob' }))
  return user
}

beforeEach(() => {
  queryClient.clear()
  window.sessionStorage.clear()
  captured = []
  installFakeChatSocket()
  vi.spyOn(api, 'fetchUsers').mockResolvedValue([USER_A, USER_B])
  vi.spyOn(api, 'fetchConversations').mockResolvedValue([CONVERSATION])
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('pending → sent/failed reconciliation (App-level)', () => {
  it('MESSAGE_ACK removes the pending bubble and renders the authoritative message once', async () => {
    vi.spyOn(api, 'fetchMessages').mockResolvedValue([])
    const user = await renderSignedInOnConversation()

    const textbox = await screen.findByRole('textbox', { name: /message to bob/i })
    await user.type(textbox, 'Hello there')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('Sending…')).toBeInTheDocument()
    expect(screen.getByText('Hello there')).toBeInTheDocument()

    const socket = captured[0]
    expect(socket).toBeDefined()
    const sent = socket!.sendMessage.mock.calls[0]?.[0] as { clientMessageId: string } | undefined
    expect(sent).toBeDefined()

    act(() => {
      socket!.options.onEvent?.({
        type: 'MESSAGE_ACK',
        clientMessageId: sent!.clientMessageId,
        message: msg('server-1', '2026-08-30T12:00:00.000Z', {
          senderId: USER_A.id,
          content: 'Hello there',
        }),
      })
    })

    await waitFor(() => expect(screen.queryByText('Sending…')).not.toBeInTheDocument())
    // Exactly one bubble with this content — no duplicate render.
    expect(screen.getAllByText('Hello there')).toHaveLength(1)
  })

  it('a correlated ERROR marks the pending bubble failed and never removes it', async () => {
    vi.spyOn(api, 'fetchMessages').mockResolvedValue([])
    const user = await renderSignedInOnConversation()

    const textbox = await screen.findByRole('textbox', { name: /message to bob/i })
    await user.type(textbox, 'This will fail')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByText('Sending…')).toBeInTheDocument()

    const socket = captured[0]!
    const sent = socket.sendMessage.mock.calls[0]?.[0] as { clientMessageId: string }

    act(() => {
      socket.options.onEvent?.({
        type: 'ERROR',
        clientMessageId: sent.clientMessageId,
        code: 'PERSISTENCE_ERROR',
        reason: 'boom',
      })
    })

    expect(await screen.findByText('Failed to send')).toBeInTheDocument()
    // The bubble is retained, not removed.
    expect(screen.getByText('This will fail')).toBeInTheDocument()
  })
})

describe('missed-history refresh after reconnect', () => {
  it('does NOT refetch on the FIRST connected transition (initial load already owns it)', async () => {
    const fetchMessagesSpy = vi.spyOn(api, 'fetchMessages').mockResolvedValue([msg('m1', '2026-08-30T12:00:00.000Z')])
    await renderSignedInOnConversation()
    await screen.findByText('content-m1')
    expect(fetchMessagesSpy).toHaveBeenCalledTimes(1)

    const socket = captured[0]!
    act(() => {
      socket.options.onStateChange?.('connected')
    })

    // Still just the one, initial-mount fetch.
    expect(fetchMessagesSpy).toHaveBeenCalledTimes(1)
  })

  it('refetches on reconnect and the merge cannot erase or duplicate a newer WebSocket message', async () => {
    const fetchMessagesSpy = vi.spyOn(api, 'fetchMessages')
    fetchMessagesSpy.mockResolvedValueOnce([msg('m1', '2026-08-30T12:00:00.000Z')])

    let resolveReconnectFetch: ((messages: Message[]) => void) | undefined
    fetchMessagesSpy.mockImplementationOnce(
      () =>
        new Promise<Message[]>((resolve) => {
          resolveReconnectFetch = resolve
        }),
    )

    await renderSignedInOnConversation()
    await screen.findByText('content-m1')

    const socket = captured[0]!
    // Establish the shell's FIRST connected transition (excluded from refetch).
    act(() => {
      socket.options.onStateChange?.('connected')
    })
    expect(fetchMessagesSpy).toHaveBeenCalledTimes(1)

    // Unexpected disconnect, then reconnect: this is what triggers the
    // invalidate → refetch (task 6.6's recovery mechanism).
    act(() => {
      socket.options.onStateChange?.('reconnecting')
    })
    act(() => {
      socket.options.onStateChange?.('connected')
    })

    await waitFor(() => expect(fetchMessagesSpy).toHaveBeenCalledTimes(2))
    expect(resolveReconnectFetch).toBeDefined()

    // While the refetch is still in flight, a NEW_MESSAGE lands via the
    // socket — this must survive the eventual merge commit even though the
    // in-flight server response snapshot predates it and will not include it.
    const midFlightMessage = msg('m2', '2026-08-30T12:00:05.000Z')
    act(() => {
      socket.options.onEvent?.({ type: 'NEW_MESSAGE', message: midFlightMessage })
    })
    expect(await screen.findByText('content-m2')).toBeInTheDocument()

    // The server's reconnect-triggered response reports history missed while
    // disconnected (m3) but — realistically for an in-flight snapshot — not
    // yet m2, which arrived after the server read.
    const missedMessage = msg('m3', '2026-08-30T12:00:10.000Z')
    await act(async () => {
      resolveReconnectFetch?.([msg('m1', '2026-08-30T12:00:00.000Z'), missedMessage])
      await Promise.resolve()
    })

    // All three render, each exactly once, in chronological order — the
    // merge neither erased the mid-flight m2 nor duplicated m1/m3.
    await waitFor(() => expect(screen.getByText('content-m3')).toBeInTheDocument())
    expect(screen.getAllByText(/^content-m/)).toHaveLength(3)
    const order = screen.getAllByText(/^content-m/).map((el) => el.textContent)
    expect(order).toEqual(['content-m1', 'content-m2', 'content-m3'])
  })
})
