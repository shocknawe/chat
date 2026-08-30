/**
 * WebSocket application protocol — client side.
 *
 * This module mirrors the backend wire contract
 * (`backend/.../ws/protocol/InboundCommand.kt` and `OutboundEvent.kt`) EXACTLY:
 * a discriminated union on the JSON `type` property.
 *
 *   client -> server: SEND_MESSAGE { clientMessageId, conversationId, content }
 *   server -> client: MESSAGE_ACK { clientMessageId, message }
 *                     NEW_MESSAGE { message }
 *                     ERROR { clientMessageId?, code, reason }
 *                     PRESENCE { online: uuid[] }
 *
 * Server-authoritative messages (design.md): `clientMessageId` is only a
 * correlation/idempotency token; the `message` payload carried by MESSAGE_ACK
 * and NEW_MESSAGE is the authoritative shape `{ id, conversationId, senderId,
 * content, createdAt }` — identical to the REST DTO defined in `../api.ts`.
 *
 * There is deliberately no `senderId` field on the outbound command: the
 * backend derives the sender from the validated connection identity bound at
 * the `?userId=` handshake and ignores any client-supplied sender field.
 */

import type { Conversation, Message } from '../api'

/** Client -> server command to create a message. */
export interface SendMessageCommand {
  type: 'SEND_MESSAGE'
  /** Client-generated UUID correlation/idempotency token (never the message id). */
  clientMessageId: string
  conversationId: string
  content: string
}

/** Union of every command this client can send (only SEND_MESSAGE for the MVP). */
export type OutboundCommand = SendMessageCommand

/** Sent to the originating connection after the message transaction commits. */
export interface MessageAckEvent {
  type: 'MESSAGE_ACK'
  clientMessageId: string
  /** The authoritative message (server-generated id and createdAt). */
  message: Message
}

/** Sent to every other active connection of the conversation participants. */
export interface NewMessageEvent {
  type: 'NEW_MESSAGE'
  message: Message
}

/** Correlated protocol/validation/persistence error. */
export interface ErrorEvent {
  type: 'ERROR'
  /** Present when the failing command carried one; absent for unparseable frames. */
  clientMessageId?: string
  /** Stable machine-readable code — branch on this, never on `reason`. */
  code: ErrorCode
  /** Human-readable detail for logging/debugging only. */
  reason: string
}

/**
 * Sent to every active connection of the OTHER participant after
 * `POST /api/conversations` commits a new conversation — never to the creator,
 * who already holds the REST response, and never for the 200 (already-existed)
 * case. The embedded conversation reuses the REST `Conversation` shape and has
 * an empty history (task 3.9 renders it with an empty rail preview).
 */
export interface ConversationCreatedEvent {
  type: 'CONVERSATION_CREATED'
  conversation: Conversation
}

/**
 * Sent as a socket's FIRST event after the handshake (its current snapshot),
 * and again on every online/offline transition of one of the recipient's
 * conversation partners. `online` is the WHOLESALE, full scoped set of online
 * user ids — a complete replacement of whatever the client held before, never
 * a delta and never merged, and never containing the recipient themself. The
 * empty array is meaningful and always serialised: none of the recipient's
 * conversation partners is online.
 */
export interface PresenceEvent {
  type: 'PRESENCE'
  online: string[]
}

/** Union of every event the server can send. */
export type InboundEvent =
  | MessageAckEvent
  | NewMessageEvent
  | ErrorEvent
  | ConversationCreatedEvent
  | PresenceEvent

/**
 * Stable, machine-readable ERROR codes (mirror of the backend `ErrorCodes`
 * object). The frontend must branch on these and never on human-readable
 * `reason` text.
 */
export const ERROR_CODES = {
  INVALID_COMMAND: 'INVALID_COMMAND',
  INVALID_CONTENT: 'INVALID_CONTENT',
  CONVERSATION_NOT_FOUND: 'CONVERSATION_NOT_FOUND',
  FORBIDDEN: 'FORBIDDEN',
  CLIENT_MESSAGE_ID_CONFLICT: 'CLIENT_MESSAGE_ID_CONFLICT',
  PERSISTENCE_ERROR: 'PERSISTENCE_ERROR',
} as const

export type ErrorCode = (typeof ERROR_CODES)[keyof typeof ERROR_CODES]

const ERROR_CODE_SET: ReadonlySet<string> = new Set(Object.values(ERROR_CODES))

/** Serializes an outbound SEND_MESSAGE command to its wire form. */
export function serializeCommand(command: Omit<SendMessageCommand, 'type'>): string {
  const wire: SendMessageCommand = {
    type: 'SEND_MESSAGE',
    clientMessageId: command.clientMessageId,
    conversationId: command.conversationId,
    content: command.content,
  }
  return JSON.stringify(wire)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isMessage(value: unknown): value is Message {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    typeof value.conversationId === 'string' &&
    typeof value.senderId === 'string' &&
    typeof value.content === 'string' &&
    typeof value.createdAt === 'string'
  )
}

/** Mirrors the REST `User`/`Conversation` DTOs shared with `../api.ts`. */
function isUser(value: unknown): value is Conversation['participants'][number] {
  return isRecord(value) && typeof value.id === 'string' && typeof value.displayName === 'string'
}

/**
 * Mirrors the REST `User`/`Conversation` DTOs shared with `../api.ts`.
 * Slice 3 (task 4.0): `lastMessage` is optional and — per the backend's global
 * `non_null` Jackson inclusion — is OMITTED for an empty history, never
 * serialised as a literal `null`. A defensive `null` is still accepted here
 * (absent === null) and normalised to absent by the CONVERSATION_CREATED arms
 * below, so the parsed event matches the frontend's `lastMessage?: Message`
 * contract exactly.
 */
function isConversation(value: unknown): value is Conversation {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    Array.isArray(value.participants) &&
    value.participants.every(isUser) &&
    (value.lastMessage === undefined || value.lastMessage === null || isMessage(value.lastMessage))
  )
}

/**
 * Parses and validates a raw inbound WebSocket text frame.
 *
 * Returns the narrowed `InboundEvent` on success, or `null` when the payload
 * is malformed (bad JSON, unknown/missing `type`, missing/wrongly-typed
 * fields). Callers must treat `null` as a protocol error to surface via their
 * error callback — parsing here never throws.
 */
export function parseInboundEvent(raw: string): InboundEvent | null {
  let json: unknown
  try {
    json = JSON.parse(raw)
  } catch {
    return null
  }
  if (!isRecord(json) || typeof json.type !== 'string') {
    return null
  }

  switch (json.type) {
    case 'MESSAGE_ACK': {
      if (typeof json.clientMessageId !== 'string' || !isMessage(json.message)) {
        return null
      }
      const event: MessageAckEvent = {
        type: 'MESSAGE_ACK',
        clientMessageId: json.clientMessageId,
        message: json.message,
      }
      return event
    }
    case 'NEW_MESSAGE': {
      if (!isMessage(json.message)) {
        return null
      }
      const event: NewMessageEvent = { type: 'NEW_MESSAGE', message: json.message }
      return event
    }
    case 'ERROR': {
      if (
        typeof json.code !== 'string' ||
        !ERROR_CODE_SET.has(json.code) ||
        typeof json.reason !== 'string' ||
        (json.clientMessageId !== undefined && typeof json.clientMessageId !== 'string')
      ) {
        return null
      }
      const event: ErrorEvent = {
        type: 'ERROR',
        code: json.code as ErrorCode,
        reason: json.reason,
        ...(typeof json.clientMessageId === 'string'
          ? { clientMessageId: json.clientMessageId }
          : {}),
      }
      return event
    }
    case 'CONVERSATION_CREATED': {
      if (!isConversation(json.conversation)) {
        return null
      }
      // Normalise (see isConversation): an empty history is an ABSENT
      // `lastMessage` — a defensive literal null is coerced to absence so
      // consumers only ever see `lastMessage?: Message`.
      const { lastMessage, ...conversation } = json.conversation
      const event: ConversationCreatedEvent = {
        type: 'CONVERSATION_CREATED',
        conversation:
          lastMessage === undefined || lastMessage === null
            ? conversation
            : { ...conversation, lastMessage },
      }
      return event
    }
    case 'PRESENCE': {
      // The empty array IS valid (a fully offline partner set) and required —
      // the backend always serialises it, so a missing `online` is malformed.
      if (!Array.isArray(json.online) || !json.online.every((id) => typeof id === 'string')) {
        return null
      }
      const event: PresenceEvent = { type: 'PRESENCE', online: json.online }
      return event
    }
    default:
      return null
  }
}
