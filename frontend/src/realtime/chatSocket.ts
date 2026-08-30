/**
 * Framework-agnostic WebSocket client for the realtime messaging layer.
 *
 * Responsibilities (OpenSpec task 6.1): connect with the selected user,
 * serialize outgoing SEND_MESSAGE commands, parse incoming events with runtime
 * validation, dispatch them to app code via plain option callbacks, reconnect
 * with exponential backoff after unexpected disconnects, retain unacknowledged
 * commands in memory for idempotent retry, and clean up on demand. Slice 5
 * (tasks 6.1, 6.1a, 6.5) adds `reconnectNow()` (immediate dial with strict
 * no-ops), the one-live-socket guarantee in `connect()`, and `dropConnection()`
 * (non-1000 cut whose close event re-engages the reconnect path).
 *
 * Deliberately free of any React (or other framework) import: React wiring in
 * task 6.2 consumes the factory + callbacks below and nothing else.
 *
 * Usage sketch:
 *
 *   const socket = createChatSocket({
 *     userId: currentUser.id,
 *     onEvent: (event: InboundEvent) => { ... update caches / pending state ... },
 *     onStateChange: (state: ConnectionState) => { ... drive the "unavailable" indicator ... },
 *     onError: (error: ChatSocketError) => { ... log ... },
 *   })
 *   socket.sendMessage({ clientMessageId: crypto.randomUUID(), conversationId, content })
 *   ...
 *   socket.terminate()
 */

import {
  parseInboundEvent,
  serializeCommand,
  type InboundEvent,
  type SendMessageCommand,
} from './protocol'

/**
 * Connection lifecycle states exposed to the UI.
 *
 * - `connecting`    — first connection attempt in flight.
 * - `connected`     — WebSocket open; queued unacknowledged commands were (re)sent.
 * - `reconnecting`  — connection lost unexpectedly; a retry is scheduled or in
 *                     flight. This is the input for the "realtime temporarily
 *                     unavailable" indicator (task 6.6).
 * - `disconnected`  — terminal: `terminate()` was called intentionally; the
 *                     client will never reconnect on its own again.
 */
export type ConnectionState = 'connecting' | 'connected' | 'reconnecting' | 'disconnected'

/**
 * Non-fatal errors surfaced to app code. `parse` kind means an inbound frame
 * failed runtime validation and was dropped (the connection stays open —
 * protocol errors are isolated per spec); `transport` kind means the
 * WebSocket itself errored (the browser follows up with a close event, which
 * is what actually drives the reconnect loop).
 */
export interface ChatSocketError {
  kind: 'parse' | 'transport'
  message: string
  /** The offending frame, when `kind` is `parse`. */
  raw?: string
}

/** Backoff policy for reconnect attempts; all fields optional overrides. */
export interface ReconnectConfig {
  /** Delay for the first retry. Default 500 ms. */
  baseDelayMs?: number
  /** Hard cap for any single retry delay (pre-jitter). Default 10_000 ms. */
  maxDelayMs?: number
  /**
   * Symmetric jitter fraction, 0..1. The computed delay is multiplied by
   * (1 + u) where u is uniform in [-jitterFraction, +jitterFraction], so two
   * clients that lose the same connection do not retry in lockstep.
   * Default 0.3 (±30%).
   */
  jitterFraction?: number
}

export interface ChatSocketOptions {
  /**
   * The validated window-scoped user id. Sent as the `?userId=` handshake
   * query parameter (the native WebSocket API cannot set headers; the backend
   * rejects handshakes for unknown users).
   */
  userId: string
  /**
   * WebSocket endpoint override. Default: same-origin `/ws`, with `ws://` /
   * `wss://` chosen from `location.protocol` — which makes the Vite dev
   * server's `/ws` proxy transparent and keeps production behind the same
   * origin working without extra configuration.
   */
  url?: string
  /** Reconnect backoff overrides (see {@link ReconnectConfig}). */
  reconnect?: ReconnectConfig
  /** Dispatched once per validated inbound event (`InboundEvent`). */
  onEvent?: (event: InboundEvent) => void
  /** Dispatched on every connection-state transition. */
  onStateChange?: (state: ConnectionState) => void
  /** Dispatched for malformed inbound frames and transport errors. Never throws. */
  onError?: (error: ChatSocketError) => void
}

export interface ChatSocket {
  /**
   * Task 6.1: cancels any scheduled reconnect delay and attempts the next
   * connection immediately. Deliberate no-ops (spec: "reconnect now" must
   * NEVER open an additional connection):
   *
   * - while connected (nothing to reconnect to),
   * - after `terminate()` (the client is intentionally dead),
   * - while a connection attempt is already in flight — note that a FIRED
   *   retry timer leaves `retryTimer === null` (scheduleReconnect nulls it
   *   before calling `connect`), so this case is tracked by an explicit
   *   in-flight flag, not by timer presence.
   */
  reconnectNow(): void

  /**
   * Task 6.5 (DEV-only demo primitive): severs the underlying connection
   * WITHOUT terminating the client. The socket is closed with a NON-1000
   * code so the browser's close event reaches the normal reconnect path —
   * `terminate()` is the wrong primitive here: it is irreversible, clears
   * the pending queue, and would end the demo instead of demonstrating
   * recovery. After `dropConnection()` the unacknowledged queue survives and
   * flushes on the next successful connection under the original
   * `clientMessageId`s. Never called automatically by app code; production
   * builds contain no control for it (the UI gate is on the CONTROL only —
   * the method itself stays public and is exercised by tests).
   */
  dropConnection(): void

  /**
   * Sends a SEND_MESSAGE command. If the socket is open the command goes out
   * immediately; if it is connecting/reconnecting the command is queued and
   * re-sent with the SAME `clientMessageId` once the connection is restored
   * (server-side idempotency keys on `(senderId, clientMessageId)`). The
   * command stays queued until a MESSAGE_ACK — or a correlated ERROR — for it
   * arrives. Re-submitting a `clientMessageId` that is already tracked is a
   * no-op (deduplication).
   */
  sendMessage(command: Omit<SendMessageCommand, 'type'>): void

  /** Number of commands currently awaiting an ack/error from the server. */
  pendingCount(): number

  /** Current connection state (the same value last delivered to onStateChange). */
  getState(): ConnectionState

  /**
   * Intentionally closes the client: cancels any scheduled reconnect, closes
   * the socket without triggering a retry, clears the unacknowledged queue,
   * and detaches listeners. Safe to call more than once; irreversible (create
   * a new socket via `createChatSocket` to reconnect afterwards).
   */
  terminate(): void
}

/** Tracked queue entry: FIFO-ordered by submission, keyed by clientMessageId. */
interface PendingCommand {
  command: Omit<SendMessageCommand, 'type'>
  wire: string
}

const DEFAULT_BASE_DELAY_MS = 500
const DEFAULT_MAX_DELAY_MS = 10_000
const DEFAULT_JITTER_FRACTION = 0.3

/** Resolves the default same-origin `/ws` endpoint for the current user. */
function defaultSocketUrl(userId: string): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws?userId=${encodeURIComponent(userId)}`
}

function withUserParam(url: string, userId: string): string {
  const separator = url.includes('?') ? '&' : '?'
  return `${url}${separator}userId=${encodeURIComponent(userId)}`
}

/**
 * Creates a chat WebSocket client and immediately begins connecting.
 * See the module doc comment for the full behaviour contract.
 */
export function createChatSocket(options: ChatSocketOptions): ChatSocket {
  const reconnect = options.reconnect ?? {}
  const baseDelayMs = reconnect.baseDelayMs ?? DEFAULT_BASE_DELAY_MS
  const maxDelayMs = reconnect.maxDelayMs ?? DEFAULT_MAX_DELAY_MS
  const jitterFraction = reconnect.jitterFraction ?? DEFAULT_JITTER_FRACTION

  const url = options.url !== undefined ? withUserParam(options.url, options.userId) : defaultSocketUrl(options.userId)

  let state: ConnectionState = 'connecting'
  let socket: WebSocket | null = null
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let retryAttempt = 0
  let terminated = false
  /**
   * A connection attempt is in flight: from the moment `connect()` is
   * entered until that socket resolves (open or close). This — not the
   * retry timer — is the authoritative "already attempting" signal for
   * `reconnectNow()`: `scheduleReconnect` nulls `retryTimer` BEFORE it calls
   * `connect()`, so a fired timer means "no timer", never "not attempting"
   * (task 6.1). Every path that ends an attempt (open, close, synchronous
   * constructor throw) resets it.
   */
  let attemptInFlight = false

  /** FIFO of commands not yet acknowledged (or definitively rejected). */
  const pendingById = new Map<string, PendingCommand>()

  function setState(next: ConnectionState): void {
    if (state === next) return
    state = next
    options.onStateChange?.(next)
  }

  function computeDelayMs(): number {
    const capped = Math.min(maxDelayMs, baseDelayMs * 2 ** retryAttempt)
    const jitter = 1 + (Math.random() * 2 - 1) * jitterFraction
    return Math.max(0, Math.round(capped * jitter))
  }

  function scheduleReconnect(): void {
    if (terminated || retryTimer !== null) return
    setState('reconnecting')
    retryTimer = setTimeout(() => {
      retryTimer = null
      retryAttempt += 1
      if (!terminated) connect()
    }, computeDelayMs())
  }

  function flushPending(): void {
    if (socket === null || socket.readyState !== WebSocket.OPEN) return
    // Map preserves insertion order → FIFO re-send of unacknowledged commands.
    for (const entry of pendingById.values()) {
      try {
        socket.send(entry.wire)
      } catch {
        // A failed send here means the socket is dying; the imminent close
        // event will schedule a reconnect and we flush again afterwards.
        return
      }
    }
  }

  /**
   * Detaches listeners from and closes `target`, then forgets it. Both
   * `connect()` (task 6.1a: opening must first RELEASE a previous socket, or
   * a client can hold two live connections — two registry entries for one
   * user, presence reporting online after the real one closes, and duplicate
   * `NEW_MESSAGE` frames) and `terminate()` share this one path so neither
   * ever leaves an orphaned, still-listening socket behind.
   *
   * `code` is the close code for a socket that is still open/connecting;
   * callers pass 1000 (an intentional client-side release — the detachment
   * before the close means the resulting close event can never schedule a
   * second reconnect) or, for `dropConnection()`, nothing: that one detaches
   * NOTHING precisely because its close event is the reconnect trigger.
   */
  function releaseSocket(target: WebSocket, code: number | undefined): void {
    detachListeners(target)
    if (
      code !== undefined &&
      (target.readyState === WebSocket.OPEN || target.readyState === WebSocket.CONNECTING)
    ) {
      try {
        target.close(code)
      } catch {
        // An already-dead socket cannot be closed again; it is detached, so
        // nothing can observe the failure.
      }
    }
  }

  function onMessage(frame: MessageEvent<string>): void {
    if (typeof frame.data !== 'string') return
    const event = parseInboundEvent(frame.data)
    if (event === null) {
      options.onError?.({
        kind: 'parse',
        message: 'Discarding malformed inbound WebSocket frame',
        raw: frame.data,
      })
      return
    }

    // The queue tracks commands without a definitive server response. A
    // MESSAGE_ACK confirms persistence; a correlated ERROR is a final
    // rejection — either way the command must not be retried on reconnect.
    if (event.type === 'MESSAGE_ACK') {
      pendingById.delete(event.clientMessageId)
    } else if (event.type === 'ERROR' && event.clientMessageId !== undefined) {
      pendingById.delete(event.clientMessageId)
    }

    options.onEvent?.(event)
  }

  function onOpen(): void {
    attemptInFlight = false // this attempt resolved successfully
    retryAttempt = 0 // clean successful connect resets the backoff schedule
    setState('connected')
    // (Re)send every still-unacknowledged command with its unchanged
    // clientMessageId; server idempotency absorbs any that were persisted
    // while their ack was lost.
    flushPending()
  }

  function onClose(): void {
    attemptInFlight = false // this attempt resolved (successfully or not)
    if (terminated) return
    // Any unexpected close — dropped connection, backend restart, handshake
    // rejection (which surfaces as a failed upgrade then close), or a
    // `dropConnection()` cut — retries on the backoff schedule. There is no
    // attempt cap for the demo, and the exponential cap plus jitter prevents
    // a handshake-reject hot-loop.
    scheduleReconnect()
  }

  function onError(): void {
    // The browser always follows an error with a close event; reconnect
    // scheduling happens in onClose. This purely informs app-side logging.
    options.onError?.({ kind: 'transport', message: 'WebSocket transport error' })
  }

  function detachListeners(target: WebSocket): void {
    target.removeEventListener('open', onOpen)
    target.removeEventListener('message', onMessage as EventListener)
    target.removeEventListener('close', onClose)
    target.removeEventListener('error', onError)
  }

  function connect(): void {
    // Task 6.1: never a second attempt on top of an in-flight one, and never
    // one after `terminate()` — the "no timer" state alone does not prove a
    // fired timer has not already opened an attempt (see `attemptInFlight`).
    if (terminated || attemptInFlight) return
    attemptInFlight = true
    // Task 6.1a: release ANY previous socket before opening the next one.
    // The detachment (not the close code) is what prevents the old socket's
    // close event from also feeding the reconnect loop.
    if (socket !== null) {
      const previous = socket
      socket = null
      releaseSocket(previous, 1000)
    }
    let next: WebSocket
    try {
      next = new WebSocket(url)
    } catch {
      // e.g. malformed override URL — treat like a failed connection attempt
      // and fall into the regular backoff loop.
      attemptInFlight = false
      options.onError?.({ kind: 'transport', message: 'Could not open WebSocket connection' })
      scheduleReconnect()
      return
    }
    socket = next
    next.addEventListener('open', onOpen)
    next.addEventListener('message', onMessage as EventListener)
    next.addEventListener('close', onClose)
    next.addEventListener('error', onError)
  }

  // Begin connecting immediately; the initial 'connecting' state is
  // observable via getState() and the first transition reaches onStateChange.
  connect()

  return {
    reconnectNow(): void {
      if (terminated) return // irreversible: a terminated client never dials again
      if (state === 'connected') return // nothing to reconnect — no-op
      // Cancel the scheduled delay so the next attempt is immediate. When an
      // attempt is already in flight there is no timer to cancel (a fired
      // timer nulls itself before dialling), so this is a harmless no-op in
      // exactly the case the in-flight check below rejects.
      if (retryTimer !== null) {
        clearTimeout(retryTimer)
        retryTimer = null
      }
      if (attemptInFlight) return // already attempting: a second dial would race the first
      connect()
    },

    dropConnection(): void {
      if (terminated || socket === null) return
      // Listeners stay attached ON PURPOSE: the close event is what engages
      // the normal reconnect path (task 6.5 — the pending queue must survive
      // so waiting messages flush on restore).
      if (
        socket.readyState === WebSocket.OPEN ||
        socket.readyState === WebSocket.CONNECTING
      ) {
        // 4999: the highest app-defined close code — an ABNORMAL, client-side
        // cut. A 1000 would read as an intentional final close and is exactly
        // what `terminate()` uses; any non-1000 code drives the same
        // client-side reconnect loop, and the backend unregisters every close
        // code uniformly in `afterConnectionClosed`.
        socket.close(4999)
      }
    },

    sendMessage(command: Omit<SendMessageCommand, 'type'>): void {
      if (terminated) return
      if (pendingById.has(command.clientMessageId)) return // app re-submit: dedupe
      pendingById.set(command.clientMessageId, {
        command,
        wire: serializeCommand(command),
      })
      flushPending()
    },

    pendingCount(): number {
      return pendingById.size
    },

    getState(): ConnectionState {
      return state
    },

    terminate(): void {
      if (terminated) return
      terminated = true
      if (retryTimer !== null) {
        clearTimeout(retryTimer)
        retryTimer = null
      }
      if (socket !== null) {
        const previous = socket
        socket = null
        // 1000 = normal closure; intentional, so the detached listeners can
        // never schedule a reconnect from the resulting close event.
        releaseSocket(previous, 1000)
      }
      pendingById.clear()
      setState('disconnected')
    },
  }
}
