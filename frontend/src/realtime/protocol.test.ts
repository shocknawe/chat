/**
 * OpenSpec task 7.3, area 1: protocol parsing.
 *
 * Covers `parseInboundEvent` (valid MESSAGE_ACK / NEW_MESSAGE / ERROR frames,
 * and every malformed-frame rejection path) and `serializeCommand` (wire
 * shape of the one outbound command).
 */
import { describe, expect, it } from 'vitest'
import type { Message } from '../api'
import { ERROR_CODES, parseInboundEvent, serializeCommand } from './protocol'

const message: Message = {
  id: 'msg-1',
  conversationId: 'conv-1',
  senderId: 'user-1',
  content: 'hello',
  createdAt: '2026-08-30T12:00:00.000Z',
}

describe('serializeCommand', () => {
  it('emits a SEND_MESSAGE wire frame with exactly the correlation/content fields', () => {
    const wire = serializeCommand({
      clientMessageId: 'client-1',
      conversationId: 'conv-1',
      content: 'hi there',
    })
    const parsed: unknown = JSON.parse(wire)
    expect(parsed).toEqual({
      type: 'SEND_MESSAGE',
      clientMessageId: 'client-1',
      conversationId: 'conv-1',
      content: 'hi there',
    })
  })

  it('never emits a senderId — the server derives it from the handshake identity', () => {
    const wire = serializeCommand({
      clientMessageId: 'client-1',
      conversationId: 'conv-1',
      content: 'hi there',
    })
    expect(Object.keys(JSON.parse(wire) as object)).not.toContain('senderId')
    expect(wire).not.toContain('senderId')
  })
})

describe('parseInboundEvent — MESSAGE_ACK', () => {
  it('parses a valid frame', () => {
    const raw = JSON.stringify({ type: 'MESSAGE_ACK', clientMessageId: 'client-1', message })
    expect(parseInboundEvent(raw)).toEqual({
      type: 'MESSAGE_ACK',
      clientMessageId: 'client-1',
      message,
    })
  })

  it('rejects a missing clientMessageId', () => {
    const raw = JSON.stringify({ type: 'MESSAGE_ACK', message })
    expect(parseInboundEvent(raw)).toBeNull()
  })

  it('rejects a wrongly-typed clientMessageId', () => {
    const raw = JSON.stringify({ type: 'MESSAGE_ACK', clientMessageId: 42, message })
    expect(parseInboundEvent(raw)).toBeNull()
  })

  it('rejects a malformed message payload', () => {
    const raw = JSON.stringify({
      type: 'MESSAGE_ACK',
      clientMessageId: 'client-1',
      message: { ...message, createdAt: undefined },
    })
    expect(parseInboundEvent(raw)).toBeNull()
  })
})

describe('parseInboundEvent — NEW_MESSAGE', () => {
  it('parses a valid frame', () => {
    const raw = JSON.stringify({ type: 'NEW_MESSAGE', message })
    expect(parseInboundEvent(raw)).toEqual({ type: 'NEW_MESSAGE', message })
  })

  it('rejects a missing message field', () => {
    const raw = JSON.stringify({ type: 'NEW_MESSAGE' })
    expect(parseInboundEvent(raw)).toBeNull()
  })

  it('rejects a message payload missing a required field', () => {
    const { senderId: _senderId, ...withoutSenderId } = message
    const raw = JSON.stringify({ type: 'NEW_MESSAGE', message: withoutSenderId })
    expect(parseInboundEvent(raw)).toBeNull()
  })
})

describe('parseInboundEvent — ERROR', () => {
  it('parses a valid frame WITH a clientMessageId', () => {
    const raw = JSON.stringify({
      type: 'ERROR',
      clientMessageId: 'client-1',
      code: ERROR_CODES.INVALID_CONTENT,
      reason: 'Content must not be blank',
    })
    expect(parseInboundEvent(raw)).toEqual({
      type: 'ERROR',
      clientMessageId: 'client-1',
      code: ERROR_CODES.INVALID_CONTENT,
      reason: 'Content must not be blank',
    })
  })

  it('parses a valid frame WITHOUT a clientMessageId (unparseable-frame errors)', () => {
    const raw = JSON.stringify({
      type: 'ERROR',
      code: ERROR_CODES.INVALID_COMMAND,
      reason: 'Could not parse command',
    })
    const parsed = parseInboundEvent(raw)
    expect(parsed).toEqual({
      type: 'ERROR',
      code: ERROR_CODES.INVALID_COMMAND,
      reason: 'Could not parse command',
    })
    expect(parsed && 'clientMessageId' in parsed).toBe(false)
  })

  it('rejects an unknown error code', () => {
    const raw = JSON.stringify({ type: 'ERROR', code: 'SOMETHING_MADE_UP', reason: 'nope' })
    expect(parseInboundEvent(raw)).toBeNull()
  })

  it('rejects a missing reason', () => {
    const raw = JSON.stringify({ type: 'ERROR', code: ERROR_CODES.FORBIDDEN })
    expect(parseInboundEvent(raw)).toBeNull()
  })

  it('rejects a wrongly-typed clientMessageId', () => {
    const raw = JSON.stringify({
      type: 'ERROR',
      clientMessageId: 123,
      code: ERROR_CODES.FORBIDDEN,
      reason: 'nope',
    })
    expect(parseInboundEvent(raw)).toBeNull()
  })
})

describe('parseInboundEvent — malformed/unknown frames', () => {
  it('returns null for invalid JSON', () => {
    expect(parseInboundEvent('{not json')).toBeNull()
  })

  it('returns null for JSON that is not an object', () => {
    expect(parseInboundEvent('42')).toBeNull()
    expect(parseInboundEvent('"a string"')).toBeNull()
    expect(parseInboundEvent('null')).toBeNull()
  })

  it('returns null for a missing type', () => {
    expect(parseInboundEvent(JSON.stringify({ message }))).toBeNull()
  })

  it('returns null for an unknown type', () => {
    expect(parseInboundEvent(JSON.stringify({ type: 'PING' }))).toBeNull()
  })
})
