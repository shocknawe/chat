/**
 * Slice 6 (tasks 7.2–7.3): the session-scoped transport ledger and the
 * inspector's target model.
 *
 * The ledger records what THIS window actually observed on the wire — nothing
 * more. It is fed at the socket's instrumentation boundaries: submissions
 * (`recordCommandQueued`, called by the submit path), the new `onCommandSent`
 * hook (`recordCommandSent`, fired at the moment a frame is written to an open
 * socket), and validated inbound events (`recordInboundEvent`). It is:
 *
 * - **Session-scoped**: it lives in `SignedInShell` state; a reload — or an
 *   identity switch, which remounts the shell — discards it. The drawer states
 *   this honestly ("not observed in this session") instead of hiding it
 *   (design.md decision 7, "two kinds of absence").
 * - **Bounded**: at most {@link TRANSPORT_LEDGER_LIMIT} records; when a new
 *   record would exceed the bound the OLDEST record is discarded first (Map
 *   insertion order; spec `message-inspector`, "Ledger is bounded").
 *
 * Keys: an own outbound command is keyed `out:<clientMessageId>` (it has no
 * server id until its ack); a received message is keyed `in:<messageId>`. The
 * token is sender-scoped, so it can never key another participant's message.
 * Lookup from an inspected authoritative message tries both
 * ({@link recordForMessage}): the inbound record of a received message, then
 * its own command's record (which survives as the message's transport history
 * after the ack).
 *
 * Everything here is framework-free; `hooks/useTransportLedger.ts` is the thin
 * React binding holding the map in state so the open drawer live-updates.
 */

import type { Message } from '../api'
import type {
  ErrorEvent,
  MessageAckEvent,
  NewMessageEvent,
  SendMessageCommand,
} from '../realtime'

/** Hard cap on retained transport records; oldest are discarded first. */
export const TRANSPORT_LEDGER_LIMIT = 100

/** What the ledger watched happen to a message, in order of possibility. */
export type TransportStepKind = 'queued' | 'sent' | 'acknowledged' | 'rejected' | 'delivered'

/**
 * One observed transport step. `observedAt` is the client's wall clock at the
 * moment of observation — an ISO-8601 instant, always present: a step only
 * exists once it was observed, so a fabricated timestamp is structurally
 * impossible.
 */
export interface TransportStep {
  kind: TransportStepKind
  observedAt: string
  /**
   * Correlated ERROR code for a `rejected` step — diagnostic data (the
   * inspector is exactly the place `rejectionWording.ts` earmarks for it).
   */
  code?: string
  /** Human-readable server detail of a `rejected` step, retained for the inspector. */
  reason?: string
}

/** A realtime protocol frame this session actually sent or received. */
export interface RecordedFrame {
  name: 'SEND_MESSAGE' | 'MESSAGE_ACK' | 'NEW_MESSAGE' | 'ERROR'
  /** The parsed payload exactly as it crossed the wire (shapes per docs/openapi.yaml). */
  payload: unknown
  observedAt: string
}

/** The transport history of one message, as observed by this window. */
export interface TransportRecord {
  conversationId: string
  content: string
  /** Filled once an authoritative message payload was observed (ack/receipt). */
  messageId?: string
  senderId?: string
  /** The sender-scoped correlation token (own commands: our own; received: the sender's). */
  clientMessageId?: string
  steps: TransportStep[]
  frames: RecordedFrame[]
}

export type TransportLedger = ReadonlyMap<string, TransportRecord>

export const EMPTY_TRANSPORT_LEDGER: TransportLedger = new Map()

function outboundKey(clientMessageId: string): string {
  return `out:${clientMessageId}`
}

function inboundKey(messageId: string): string {
  return `in:${messageId}`
}

/**
 * Enforces the bound, discarding OLDEST first (Map iteration is insertion
 * order). Updating an existing record keeps its original position — recency
 * of observation is not recency of the record.
 */
function withBound(ledger: Map<string, TransportRecord>): TransportLedger {
  while (ledger.size > TRANSPORT_LEDGER_LIMIT) {
    const oldest = ledger.keys().next().value
    if (oldest === undefined) break
    ledger.delete(oldest)
  }
  return ledger
}

function put(
  ledger: TransportLedger,
  key: string,
  update: (existing: TransportRecord | undefined) => TransportRecord,
): TransportLedger {
  const next = new Map(ledger)
  next.set(key, update(next.get(key)))
  return withBound(next)
}

/**
 * An own command entering the send pipeline (the optimistic bubble's birth).
 * Called by the submit path before/around `socket.sendMessage` — the queue
 * observation, not the wire write (that is {@link recordCommandSent}).
 */
export function recordCommandQueued(
  ledger: TransportLedger,
  command: Omit<SendMessageCommand, 'type'>,
  observedAt: string,
): TransportLedger {
  return put(ledger, outboundKey(command.clientMessageId), (existing) => ({
    // A re-queued token (manual retry, task 6.8) appends to the SAME record:
    // the command's identity fields are immutable per token.
    conversationId: command.conversationId,
    content: command.content,
    clientMessageId: command.clientMessageId,
    messageId: existing?.messageId,
    senderId: existing?.senderId,
    steps: [...(existing?.steps ?? []), { kind: 'queued' as const, observedAt }],
    // Queueing only proves that this window holds the command. It is not a
    // protocol-frame observation: `recordCommandSent` appends SEND_MESSAGE
    // only after the browser accepts an actual socket write.
    frames: existing?.frames ?? [],
  }))
}

/**
 * The frame left this window on an open socket (`onCommandSent`, task 7.2).
 * Fires again for every reconnect flush — each re-send is its own honest step.
 * No-op for a command we never saw queued (a foreign caller): the ledger
 * records observations only for known commands.
 */
export function recordCommandSent(
  ledger: TransportLedger,
  command: Omit<SendMessageCommand, 'type'>,
  observedAt: string,
): TransportLedger {
  const key = outboundKey(command.clientMessageId)
  const existing = ledger.get(key)
  if (existing === undefined) return ledger
  return withBound(
    new Map(ledger).set(key, {
      ...existing,
      steps: [...existing.steps, { kind: 'sent' as const, observedAt }],
      frames: [
        ...existing.frames,
        {
          name: 'SEND_MESSAGE' as const,
          payload: { type: 'SEND_MESSAGE', ...command },
          observedAt,
        },
      ],
    }),
  )
}

/** Inbound events the ledger can correlate to a message. */
export type LedgerEvent = MessageAckEvent | NewMessageEvent | ErrorEvent

/**
 * A validated inbound event, folded into the message's record:
 *
 * - `MESSAGE_ACK` — persistence observed; fills the record's server identity.
 *   An ack for a command this session never queued still creates a record:
 *   the ack itself WAS observed, so its steps are honest.
 * - `ERROR` (correlated only) — a final rejection observed for the command.
 *   Uncorrelated errors name no message and are not recorded here.
 * - `NEW_MESSAGE` — delivery of a message TO this window observed, keyed by
 *   its server id; the sender's token ships on the message itself (task 7.1),
 *   so the record keeps it as data rather than as key.
 */
export function recordInboundEvent(
  ledger: TransportLedger,
  event: LedgerEvent,
  observedAt: string,
): TransportLedger {
  switch (event.type) {
    case 'MESSAGE_ACK': {
      const key = outboundKey(event.clientMessageId)
      return put(ledger, key, (existing) => ({
        conversationId: event.message.conversationId,
        content: event.message.content,
        messageId: event.message.id,
        senderId: event.message.senderId,
        clientMessageId: event.clientMessageId,
        steps: [...(existing?.steps ?? []), { kind: 'acknowledged' as const, observedAt }],
        frames: [
          ...(existing?.frames ?? []),
          { name: 'MESSAGE_ACK' as const, payload: event, observedAt },
        ],
      }))
    }
    case 'NEW_MESSAGE': {
      const key = inboundKey(event.message.id)
      return put(ledger, key, (existing) => ({
        conversationId: event.message.conversationId,
        content: event.message.content,
        messageId: event.message.id,
        senderId: event.message.senderId,
        clientMessageId: event.message.clientMessageId,
        steps: [...(existing?.steps ?? []), { kind: 'delivered' as const, observedAt }],
        frames: [
          ...(existing?.frames ?? []),
          { name: 'NEW_MESSAGE' as const, payload: event, observedAt },
        ],
      }))
    }
    case 'ERROR': {
      if (event.clientMessageId === undefined) return ledger // names no message
      const key = outboundKey(event.clientMessageId)
      return put(ledger, key, (existing) => {
        const record: TransportRecord =
          existing ?? {
            conversationId: '',
            content: '',
            clientMessageId: event.clientMessageId,
            steps: [],
            frames: [],
          }
        return {
          ...record,
          steps: [
            ...record.steps,
            { kind: 'rejected' as const, observedAt, code: event.code, reason: event.reason },
          ],
          frames: [...record.frames, { name: 'ERROR' as const, payload: event, observedAt }],
        }
      })
    }
    default:
      return ledger
  }
}

/**
 * The transport record for an inspected AUTHORITATIVE message: its inbound
 * delivery record first (received messages), then its own command's record
 * (own messages — the record the ack completed), else `undefined`, which the
 * drawer states as "not observed in this session" (never dashed-out steps).
 */
export function recordForMessage(
  ledger: TransportLedger,
  message: Message,
): TransportRecord | undefined {
  return ledger.get(inboundKey(message.id)) ?? ledger.get(outboundKey(message.clientMessageId))
}

/** The transport record for an inspected PENDING message (no server id yet). */
export function recordForPending(
  ledger: TransportLedger,
  clientMessageId: string,
): TransportRecord | undefined {
  return ledger.get(outboundKey(clientMessageId))
}

/* ── InspectTarget (task 7.3) ─────────────────────────────────────────── */

/**
 * How an inspected message is identified. An authoritative message is keyed by
 * its server id; an own PENDING message has no server id and is keyed by its
 * correlation token until the ack arrives — at which point the shell re-keys
 * the open inspection to the server identity WITHOUT closing the drawer
 * (spec: "Acknowledgement does not close the inspector").
 */
export type InspectMessageKey =
  | { by: 'id'; messageId: string }
  | { by: 'clientMessageId'; clientMessageId: string }

/**
 * What the info drawer presents (task 7.3):
 *
 * - `message` — one message's record: transport steps, identifiers,
 *   correlation token, timestamps, ordering key, content length, wire frames.
 * - `connection` — no message selected: connection and protocol facts (the
 *   thread-header control's view).
 */
export type InspectTarget =
  | { kind: 'message'; key: InspectMessageKey }
  | { kind: 'connection' }

export function sameMessageKey(a: InspectMessageKey, b: InspectMessageKey): boolean {
  if (a.by === 'id' && b.by === 'id') return a.messageId === b.messageId
  if (a.by === 'clientMessageId' && b.by === 'clientMessageId') {
    return a.clientMessageId === b.clientMessageId
  }
  return false
}
