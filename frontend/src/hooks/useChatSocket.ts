/**
 * React binding for the realtime chat socket (OpenSpec task 6.2).
 *
 * The socket module itself (`../realtime`) stays React-free; this hook is the
 * React-idiomatic home for its lifecycle:
 *
 * - The connection exists exactly while a current user is established: the
 *   effect below depends on `userId`, so it creates one socket per identity
 *   and `terminate()`s it on user switch or unmount (the cleanup runs before
 *   the next effect under React's ordering guarantees, so no duplicate live
 *   sockets per user are ever possible — and `SignedInShell` is additionally
 *   keyed on the user id, remounting wholesale on a switch).
 * - React StrictMode double-mount is safe: the first mount creates socket A,
 *   the simulated unmount runs cleanup → `terminate()` detaches listeners and
 *   closes synchronously so A can never reconnect, then the second mount
 *   creates socket B. No leak; the first terminate cannot interfere with B
 *   because termination is synchronous and irreversible per socket instance.
 * - Connection state is mirrored into React state for the availability
 *   indicator in task 6.6.
 *
 * Inbound events are routed through a thin typed dispatch
 * ({@link dispatchRealtimeEvent}) keyed on the event discriminator, so tasks
 * 6.4/6.5 subscribe by registering the specific callbacks they consume rather
 * than re-switching on `InboundEvent` themselves.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  createChatSocket,
  type ChatSocket,
  type ConnectionState,
  type ErrorEvent,
  type InboundEvent,
  type MessageAckEvent,
  type NewMessageEvent,
  type SendMessageCommand,
} from '../realtime'

/**
 * Per-type callbacks for inbound realtime events. The hook owns the
 * connection and routes every validated event of the current user's socket
 * here; tasks 6.4/6.5 supply the cache-mutating implementations. All
 * callbacks are optional — an event with no registered consumer is a no-op.
 */
export interface RealtimeEventHandlers {
  /** Authoritative acknowledgement correlating a pending outbound command. */
  onMessageAck?: (event: MessageAckEvent) => void
  /** Authoritative message for a conversation, from any participant session. */
  onNewMessage?: (event: NewMessageEvent) => void
  /** Correlated protocol/validation/persistence rejection. */
  onRealtimeError?: (event: ErrorEvent) => void
}

export interface UseChatSocketResult {
  /** Latest connection lifecycle state (drives the task-6.6 indicator). */
  connectionState: ConnectionState
  /**
   * Typed passthrough to the socket's SEND_MESSAGE submission (queueing,
   * idempotent reconnect retries, and dedupe live behind the hook). Callers
   * (task 6.3's composer) supply the `clientMessageId`.
   */
  sendMessage: (command: Omit<SendMessageCommand, 'type'>) => void
  /** Number of outbound commands still awaiting ack/error. */
  pendingCount: () => number
}

/** Routes a validated inbound event to the typed callback for its variant. */
function dispatchRealtimeEvent(handlers: RealtimeEventHandlers, event: InboundEvent): void {
  switch (event.type) {
    case 'MESSAGE_ACK':
      handlers.onMessageAck?.(event)
      break
    case 'NEW_MESSAGE':
      handlers.onNewMessage?.(event)
      break
    case 'ERROR':
      handlers.onRealtimeError?.(event)
      break
  }
}

/**
 * Establishes the chat WebSocket for `userId` and keeps it alive for exactly
 * as long as that identity is the current user. Re-mounting with a different
 * `userId` (or unmounting) terminates the previous socket before the new one
 * connects.
 */
export function useChatSocket(
  userId: string,
  handlers: RealtimeEventHandlers = {},
): UseChatSocketResult {
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting')

  // The socket instance is deliberately NOT React state: it is an imperative
  // resource owned by the effect. A ref also keeps `sendMessage`/`pendingCount`
  // referentially stable across reconnects and re-renders.
  const socketRef = useRef<ChatSocket | null>(null)

  // Handlers may be re-created by the caller on every render; routing through
  // a ref keeps them current without ever tearing down the connection.
  const handlersRef = useRef<RealtimeEventHandlers>(handlers)
  useEffect(() => {
    handlersRef.current = handlers
  }, [handlers])

  useEffect(() => {
    const socket = createChatSocket({
      userId,
      // Every event arriving on this connection is associated with this hook's
      // `userId` — the identity validated at the handshake — and routed on.
      onEvent: (event) => dispatchRealtimeEvent(handlersRef.current, event),
      onStateChange: setConnectionState,
      // Parse/transport errors are non-fatal (the socket reconnects on its
      // own); log for now, richer surfacing can land with 6.4–6.6.
      onError: (error) => {
        console.error(`[chat] WebSocket ${error.kind} error for user ${userId}: ${error.message}`)
      },
    })
    socketRef.current = socket

    return () => {
      socketRef.current = null
      socket.terminate()
    }
  }, [userId])

  const sendMessage = useCallback((command: Omit<SendMessageCommand, 'type'>) => {
    socketRef.current?.sendMessage(command)
  }, [])

  const pendingCount = useCallback(() => socketRef.current?.pendingCount() ?? 0, [])

  return { connectionState, sendMessage, pendingCount }
}
