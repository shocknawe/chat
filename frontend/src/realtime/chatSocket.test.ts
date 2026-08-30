/**
 * OpenSpec task 7.3, area 4: reconnect retry.
 *
 * Drives `createChatSocket`'s real public API against a controllable fake
 * `WebSocket` and Vitest fake timers: unexpected disconnects reconnect on an
 * exponential backoff schedule, unacknowledged commands are retained in
 * memory and retried with the SAME `clientMessageId` after reconnect, and
 * acknowledged commands are never retried.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createChatSocket, type ChatSocket } from './chatSocket'
import type { SendMessageCommand } from './protocol'

/** Minimal, fully controllable stand-in for the browser WebSocket. */
class FakeWebSocket extends EventTarget {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3
  static instances: FakeWebSocket[] = []

  readonly url: string
  readyState: number = FakeWebSocket.CONNECTING
  readonly sent: string[] = []
  closedWithCode: number | undefined

  constructor(url: string) {
    super()
    this.url = url
    FakeWebSocket.instances.push(this)
  }

  send(data: string): void {
    if (this.readyState !== FakeWebSocket.OPEN) {
      throw new Error('FakeWebSocket: send() while not open')
    }
    this.sent.push(data)
  }

  close(code?: number): void {
    this.readyState = FakeWebSocket.CLOSED
    this.closedWithCode = code
    this.dispatchEvent(new Event('close'))
  }

  /** Test control: completes the handshake. */
  simulateOpen(): void {
    this.readyState = FakeWebSocket.OPEN
    this.dispatchEvent(new Event('open'))
  }

  /** Test control: an unexpected drop (server restart, network blip, ...). */
  simulateUnexpectedClose(): void {
    this.readyState = FakeWebSocket.CLOSED
    this.dispatchEvent(new Event('close'))
  }

  /** Test control: delivers an inbound text frame. */
  simulateMessage(data: string): void {
    this.dispatchEvent(new MessageEvent('message', { data }))
  }
}

function lastSentCommand(socket: FakeWebSocket, index = 0): SendMessageCommand {
  return JSON.parse(socket.sent[index] as string) as SendMessageCommand
}

function ackWire(clientMessageId: string): string {
  return JSON.stringify({
    type: 'MESSAGE_ACK',
    clientMessageId,
    message: {
      id: 'server-1',
      conversationId: 'conv-1',
      senderId: 'user-1',
      content: 'hi',
      createdAt: '2026-08-30T12:00:00.000Z',
    },
  })
}

let sockets: ChatSocket[] = []

beforeEach(() => {
  FakeWebSocket.instances = []
  sockets = []
  vi.stubGlobal('WebSocket', FakeWebSocket)
  vi.useFakeTimers()
})

afterEach(() => {
  for (const socket of sockets) socket.terminate()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('reconnect backoff', () => {
  it('reconnects on an exponential schedule after unexpected disconnects, capped at maxDelayMs', async () => {
    const onStateChange = vi.fn()
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, maxDelayMs: 8000, jitterFraction: 0 },
      onStateChange,
    })
    sockets.push(socket)

    expect(FakeWebSocket.instances).toHaveLength(1)
    FakeWebSocket.instances[0]?.simulateOpen()
    expect(socket.getState()).toBe('connected')

    // Unexpected disconnect → 'reconnecting', first retry after baseDelayMs.
    FakeWebSocket.instances[0]?.simulateUnexpectedClose()
    expect(socket.getState()).toBe('reconnecting')
    expect(onStateChange).toHaveBeenCalledWith('reconnecting')

    await vi.advanceTimersByTimeAsync(999)
    expect(FakeWebSocket.instances).toHaveLength(1) // not yet
    await vi.advanceTimersByTimeAsync(1)
    expect(FakeWebSocket.instances).toHaveLength(2) // retry #1 at 1000ms

    // Retry #1 fails immediately (no open) → next delay doubles to 2000ms.
    FakeWebSocket.instances[1]?.simulateUnexpectedClose()
    await vi.advanceTimersByTimeAsync(1999)
    expect(FakeWebSocket.instances).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(FakeWebSocket.instances).toHaveLength(3) // retry #2 at +2000ms

    // Retry #2 fails immediately too → delay doubles again to 4000ms.
    FakeWebSocket.instances[2]?.simulateUnexpectedClose()
    await vi.advanceTimersByTimeAsync(3999)
    expect(FakeWebSocket.instances).toHaveLength(3)
    await vi.advanceTimersByTimeAsync(1)
    expect(FakeWebSocket.instances).toHaveLength(4) // retry #3 at +4000ms

    // Retry #3 fails immediately → would be 8000ms uncapped, but caps at
    // maxDelayMs (8000ms) — still 8000ms here, so exercise the cap boundary
    // with one more failure where uncapped would be 16000ms.
    FakeWebSocket.instances[3]?.simulateUnexpectedClose()
    await vi.advanceTimersByTimeAsync(7999)
    expect(FakeWebSocket.instances).toHaveLength(4)
    await vi.advanceTimersByTimeAsync(1)
    expect(FakeWebSocket.instances).toHaveLength(5) // retry #4 at +8000ms (capped)

    FakeWebSocket.instances[4]?.simulateUnexpectedClose()
    await vi.advanceTimersByTimeAsync(7999)
    expect(FakeWebSocket.instances).toHaveLength(5)
    await vi.advanceTimersByTimeAsync(1)
    expect(FakeWebSocket.instances).toHaveLength(6) // retry #5, still capped at 8000ms

    // A clean open resets the backoff schedule back to baseDelayMs.
    FakeWebSocket.instances[5]?.simulateOpen()
    expect(socket.getState()).toBe('connected')
    FakeWebSocket.instances[5]?.simulateUnexpectedClose()
    await vi.advanceTimersByTimeAsync(999)
    expect(FakeWebSocket.instances).toHaveLength(6)
    await vi.advanceTimersByTimeAsync(1)
    expect(FakeWebSocket.instances).toHaveLength(7) // back to the base delay
  })

  it('never reconnects after terminate() — no dangling retry timer', async () => {
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
    })
    sockets.push(socket)
    FakeWebSocket.instances[0]?.simulateOpen()
    FakeWebSocket.instances[0]?.simulateUnexpectedClose()
    expect(socket.getState()).toBe('reconnecting')

    socket.terminate()
    expect(socket.getState()).toBe('disconnected')

    await vi.advanceTimersByTimeAsync(60_000)
    expect(FakeWebSocket.instances).toHaveLength(1) // no retry ever fired
  })
})

describe('unacknowledged command retry', () => {
  it('retains an unacknowledged command and retries it with the SAME clientMessageId after reconnect', async () => {
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
    })
    sockets.push(socket)
    const first = FakeWebSocket.instances[0]
    first?.simulateOpen()

    socket.sendMessage({ clientMessageId: 'client-1', conversationId: 'conv-1', content: 'hello' })
    expect(first?.sent).toHaveLength(1)
    expect(lastSentCommand(first as FakeWebSocket).clientMessageId).toBe('client-1')
    expect(socket.pendingCount()).toBe(1)

    // Connection drops before an ack arrives.
    first?.simulateUnexpectedClose()
    await vi.advanceTimersByTimeAsync(1000)

    const second = FakeWebSocket.instances[1]
    expect(second).toBeDefined()
    second?.simulateOpen() // onOpen flushes the still-pending queue

    expect(second?.sent).toHaveLength(1)
    const retried = lastSentCommand(second as FakeWebSocket)
    expect(retried.clientMessageId).toBe('client-1') // SAME id, not a new one
    expect(retried.conversationId).toBe('conv-1')
    expect(retried.content).toBe('hello')
    expect(socket.pendingCount()).toBe(1) // still unacknowledged
  })

  it('stops retrying a command once its MESSAGE_ACK arrives', async () => {
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
    })
    sockets.push(socket)
    const first = FakeWebSocket.instances[0]
    first?.simulateOpen()

    socket.sendMessage({ clientMessageId: 'client-1', conversationId: 'conv-1', content: 'hello' })
    expect(socket.pendingCount()).toBe(1)

    // Acknowledge before the disconnect.
    first?.simulateMessage(ackWire('client-1'))
    expect(socket.pendingCount()).toBe(0)

    first?.simulateUnexpectedClose()
    await vi.advanceTimersByTimeAsync(1000)

    const second = FakeWebSocket.instances[1]
    second?.simulateOpen()

    // Nothing left to retry: the acknowledged command must not resend.
    expect(second?.sent).toHaveLength(0)
    expect(socket.pendingCount()).toBe(0)
  })

  it('re-submitting an already-tracked clientMessageId is a no-op (dedupe)', () => {
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
    })
    sockets.push(socket)
    const first = FakeWebSocket.instances[0]
    first?.simulateOpen()

    socket.sendMessage({ clientMessageId: 'client-1', conversationId: 'conv-1', content: 'hello' })
    socket.sendMessage({ clientMessageId: 'client-1', conversationId: 'conv-1', content: 'a different draft' })

    expect(socket.pendingCount()).toBe(1)
    expect(first?.sent).toHaveLength(1)
    expect(lastSentCommand(first as FakeWebSocket).content).toBe('hello')
  })
})
