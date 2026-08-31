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
      // Task 7.1: the correlation token is required on the message payload.
      clientMessageId,
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

/**
 * Slice 5 (OpenSpec tasks 6.1, 6.1a, 6.5, 6.7): the immediate-reconnect
 * control, the one-live-socket guarantee, the demo connection drop, and the
 * idempotent queue flush on restore. Same technique as the suites above:
 * the real `createChatSocket` against a controllable fake `WebSocket` and
 * Vitest fake timers.
 */
describe('reconnectNow (task 6.1)', () => {
  it('cancels the scheduled backoff and attempts immediately', async () => {
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
    })
    sockets.push(socket)
    FakeWebSocket.instances[0]?.simulateOpen()
    FakeWebSocket.instances[0]?.simulateUnexpectedClose()
    expect(socket.getState()).toBe('reconnecting')

    // WITHOUT reconnectNow, the retry would land at +1000ms; with it, the
    // second dial exists before any time has passed at all.
    expect(FakeWebSocket.instances).toHaveLength(1)
    socket.reconnectNow()
    expect(FakeWebSocket.instances).toHaveLength(2)

    // The cancelled timer is gone: no third dial appears from the dead delay.
    await vi.advanceTimersByTimeAsync(60_000)
    expect(FakeWebSocket.instances).toHaveLength(2)
  })

  it('is a no-op while connected — no additional connection is opened', () => {
    const socket = createChatSocket({ userId: 'user-1', url: 'ws://test/ws' })
    sockets.push(socket)
    FakeWebSocket.instances[0]?.simulateOpen()

    socket.reconnectNow()

    expect(FakeWebSocket.instances).toHaveLength(1)
    expect(socket.getState()).toBe('connected')
  })

  it('is a no-op after terminate() — a terminated client never dials again', () => {
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
    })
    sockets.push(socket)
    FakeWebSocket.instances[0]?.simulateOpen()
    FakeWebSocket.instances[0]?.simulateUnexpectedClose()
    socket.terminate()

    socket.reconnectNow()

    expect(socket.getState()).toBe('disconnected')
    expect(FakeWebSocket.instances).toHaveLength(1)
  })

  it('is a no-op while a connection attempt is already in flight — "no timer" does not mean "not attempting"', async () => {
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
    })
    sockets.push(socket)
    FakeWebSocket.instances[0]?.simulateOpen()
    FakeWebSocket.instances[0]?.simulateUnexpectedClose()

    // The retry timer FIRES: scheduleReconnect nulls retryTimer BEFORE
    // calling connect(), so a second later there is neither a timer nor a
    // completed attempt — an in-flight one. This is precisely the race the
    // explicit in-flight flag exists for.
    await vi.advanceTimersByTimeAsync(1000)
    expect(FakeWebSocket.instances).toHaveLength(2)
    expect(FakeWebSocket.instances[1]?.readyState).toBe(FakeWebSocket.CONNECTING)

    socket.reconnectNow()

    // The in-flight dial is the only one: reconnectNow must never open a
    // SECOND socket that would race it (two registry entries, duplicate
    // frames — task 6.1a's failure mode).
    expect(FakeWebSocket.instances).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(FakeWebSocket.instances).toHaveLength(2)
  })
})

describe('one live socket per client (task 6.1a)', () => {
  it('connect() detaches the prior socket before opening a new one — events on the old socket are neither observed nor re-tried', async () => {
    const onEvent = vi.fn()
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
      onEvent,
    })
    sockets.push(socket)
    const first = FakeWebSocket.instances[0]
    first?.simulateOpen()

    // dropConnection (non-1000 cut) schedules a retry; reconnectNow forces
    // the dial NOW while the first socket object is still handed to us —
    // exactly the "previous socket still attached" case.
    socket.dropConnection()
    expect(FakeWebSocket.instances).toHaveLength(1)
    socket.reconnectNow()
    expect(FakeWebSocket.instances).toHaveLength(2)

    // The NEW socket is the only one being listened to: a frame delivered
    // through the old socket object reaches nothing.
    first?.simulateMessage(ackWire('client-1'))
    first?.simulateOpen()
    first?.simulateUnexpectedClose()

    expect(onEvent).not.toHaveBeenCalled()
    // The orphan's close event did not feed a second reconnect either.
    await vi.advanceTimersByTimeAsync(60_000)
    expect(FakeWebSocket.instances).toHaveLength(2)
    expect(socket.getState()).toBe('reconnecting')
  })
})

describe('dropConnection (task 6.5)', () => {
  it('closes with a NON-1000 code so the normal reconnect path engages, and the pending queue survives', async () => {
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
    })
    sockets.push(socket)
    const first = FakeWebSocket.instances[0]
    first?.simulateOpen()

    socket.sendMessage({ clientMessageId: 'client-1', conversationId: 'conv-1', content: 'queued' })
    expect(first?.sent).toHaveLength(1)

    socket.dropConnection()
    // 4999, not terminate()'s 1000: an abnormal client-side cut.
    expect(first?.closedWithCode).toBe(4999)
    // The queue was NOT cleared — exactly what terminate() would have done.
    expect(socket.pendingCount()).toBe(1)
    // The cut drives the regular path: state and a scheduled retry.
    expect(socket.getState()).toBe('reconnecting')

    await vi.advanceTimersByTimeAsync(1000)
    const second = FakeWebSocket.instances[1]
    second?.simulateOpen()
    expect(second?.sent).toHaveLength(1)
    expect(lastSentCommand(second as FakeWebSocket).clientMessageId).toBe('client-1')
  })

  it('is a no-op after terminate()', () => {
    const socket = createChatSocket({ userId: 'user-1', url: 'ws://test/ws' })
    sockets.push(socket)
    const first = FakeWebSocket.instances[0]
    first?.simulateOpen()
    socket.terminate()

    socket.dropConnection()

    expect(first?.closedWithCode).toBe(1000) // only terminate() closed it
    expect(socket.getState()).toBe('disconnected')
  })
})

describe('queue flush on restore (task 6.7)', () => {
  it('flushes queued commands under their ORIGINAL clientMessageIds, each acknowledged exactly once', async () => {
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
    })
    sockets.push(socket)
    const first = FakeWebSocket.instances[0]
    first?.simulateOpen()

    socket.sendMessage({ clientMessageId: 'client-1', conversationId: 'conv-1', content: 'first' })
    socket.sendMessage({ clientMessageId: 'client-2', conversationId: 'conv-1', content: 'second' })
    // Drop before either ack arrives: both are still queued.
    expect(socket.pendingCount()).toBe(2)
    first?.simulateUnexpectedClose()

    await vi.advanceTimersByTimeAsync(1000)
    const second = FakeWebSocket.instances[1]
    second?.simulateOpen()

    // FIFO order, original tokens, unchanged content.
    expect(second?.sent).toHaveLength(2)
    expect(JSON.parse(second?.sent[0] as string)).toMatchObject({
      clientMessageId: 'client-1',
      content: 'first',
    })
    expect(JSON.parse(second?.sent[1] as string)).toMatchObject({
      clientMessageId: 'client-2',
      content: 'second',
    })

    // Each is acknowledged exactly once.
    second?.simulateMessage(ackWire('client-1'))
    second?.simulateMessage(ackWire('client-2'))
    expect(socket.pendingCount()).toBe(0)

    // A DUPLICATE ack for an already-removed token cannot resurrect anything.
    second?.simulateMessage(ackWire('client-1'))
    expect(socket.pendingCount()).toBe(0)

    // And the next reconnect has nothing to re-send — no second ack of anything.
    second?.simulateUnexpectedClose()
    await vi.advanceTimersByTimeAsync(1000)
    FakeWebSocket.instances[2]?.simulateOpen()
    expect(FakeWebSocket.instances[2]?.sent).toHaveLength(0)
  })
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

/**
 * Slice 6 (OpenSpec task 7.2): the `onCommandSent` instrumentation hook —
 * one dispatch per SEND_MESSAGE frame actually WRITTEN to an open socket,
 * feeding the info drawer's session-scoped transport ledger.
 */
describe('onCommandSent instrumentation (task 7.2)', () => {
  it('fires once per immediate wire write, with the command and a timestamp', () => {
    const onCommandSent = vi.fn()
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      onCommandSent,
    })
    sockets.push(socket)
    FakeWebSocket.instances[0]?.simulateOpen()

    socket.sendMessage({ clientMessageId: 'client-1', conversationId: 'conv-1', content: 'hello' })

    expect(onCommandSent).toHaveBeenCalledTimes(1)
    const [command, sentAt] = onCommandSent.mock.calls[0] as [
      Omit<SendMessageCommand, 'type'>,
      string,
    ]
    expect(command).toEqual({ clientMessageId: 'client-1', conversationId: 'conv-1', content: 'hello' })
    // The stamp is an observed instant, never absent.
    expect(Number.isNaN(Date.parse(sentAt))).toBe(false)
  })

  it('does NOT fire while queued offline, and fires again for the reconnect flush (each re-send is its own observation)', async () => {
    const onCommandSent = vi.fn()
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
      onCommandSent,
    })
    sockets.push(socket)

    // Submitted while CONNECTING: queued, nothing written, no observation.
    socket.sendMessage({ clientMessageId: 'client-1', conversationId: 'conv-1', content: 'queued' })
    expect(onCommandSent).not.toHaveBeenCalled()

    // First connection writes it once.
    FakeWebSocket.instances[0]?.simulateOpen()
    expect(onCommandSent).toHaveBeenCalledTimes(1)

    // An unexpected drop and reconnect RE-SENDS under the same token — a
    // second, separate observation (the ledger keeps both, honestly).
    FakeWebSocket.instances[0]?.simulateUnexpectedClose()
    await vi.advanceTimersByTimeAsync(1000)
    FakeWebSocket.instances[1]?.simulateOpen()
    expect(onCommandSent).toHaveBeenCalledTimes(2)
    const [resentCommand] = onCommandSent.mock.calls[1] as [Omit<SendMessageCommand, 'type'>]
    expect(resentCommand.clientMessageId).toBe('client-1')
  })

  it('never fires for an acknowledge command on a later reconnect (acked commands are not re-sent)', async () => {
    const onCommandSent = vi.fn()
    const socket = createChatSocket({
      userId: 'user-1',
      url: 'ws://test/ws',
      reconnect: { baseDelayMs: 1000, jitterFraction: 0 },
      onCommandSent,
    })
    sockets.push(socket)
    FakeWebSocket.instances[0]?.simulateOpen()

    socket.sendMessage({ clientMessageId: 'client-1', conversationId: 'conv-1', content: 'hello' })
    FakeWebSocket.instances[0]?.simulateMessage(ackWire('client-1'))
    expect(onCommandSent).toHaveBeenCalledTimes(1)

    FakeWebSocket.instances[0]?.simulateUnexpectedClose()
    await vi.advanceTimersByTimeAsync(1000)
    FakeWebSocket.instances[1]?.simulateOpen()
    expect(onCommandSent).toHaveBeenCalledTimes(1) // nothing re-sent, nothing re-observed
  })
})
