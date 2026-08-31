/**
 * Slice 6 (task 7.2): the session-scoped transport ledger.
 *
 * Pins the record-keeping rules the info drawer renders from: observations
 * accumulate per message in order, the ledger never fabricates a record for a
 * command it never saw queued, inbound events correlate to the right record,
 * and the bound discards the OLDEST records first.
 */
import { describe, expect, it } from 'vitest'
import {
  EMPTY_TRANSPORT_LEDGER,
  recordCommandQueued,
  recordCommandSent,
  recordForMessage,
  recordForPending,
  recordInboundEvent,
  TRANSPORT_LEDGER_LIMIT,
  type TransportLedger,
} from './transportLedger'
import type { MessageAckEvent, NewMessageEvent, SendMessageCommand } from '../realtime'

const T0 = '2026-08-30T12:00:00.000Z'
const T1 = '2026-08-30T12:00:00.250Z'
const T2 = '2026-08-30T12:00:00.500Z'

function command(clientMessageId: string): Omit<SendMessageCommand, 'type'> {
  return { clientMessageId, conversationId: 'conv-1', content: `content-${clientMessageId}` }
}

function ackEvent(clientMessageId: string, messageId: string): MessageAckEvent {
  return {
    type: 'MESSAGE_ACK',
    clientMessageId,
    message: {
      id: messageId,
      conversationId: 'conv-1',
      senderId: 'user-a',
      clientMessageId,
      content: `content-${clientMessageId}`,
      createdAt: T2,
    },
  }
}

function newMessageEvent(id: string): NewMessageEvent {
  return {
    type: 'NEW_MESSAGE',
    message: {
      id,
      conversationId: 'conv-1',
      senderId: 'user-b',
      clientMessageId: `sender-token-${id}`,
      content: `content-${id}`,
      createdAt: T1,
    },
  }
}

describe('recordCommandQueued / recordCommandSent', () => {
  it('records queueing without fabricating a protocol frame, then records the actual wire write', () => {
    let ledger: TransportLedger = EMPTY_TRANSPORT_LEDGER
    ledger = recordCommandQueued(ledger, command('c-1'), T0)

    const queued = recordForPending(ledger, 'c-1')
    expect(queued?.steps.map((s) => s.kind)).toEqual(['queued'])
    expect(queued?.frames).toHaveLength(0)

    ledger = recordCommandSent(ledger, command('c-1'), T1)

    const record = recordForPending(ledger, 'c-1')
    expect(record).toBeDefined()
    expect(record?.steps.map((s) => s.kind)).toEqual(['queued', 'sent'])
    expect(record?.steps.map((s) => s.observedAt)).toEqual([T0, T1])
    expect(record?.frames).toHaveLength(1)
    expect(record?.frames[0]?.name).toBe('SEND_MESSAGE')
    expect(record?.frames[0]?.payload).toMatchObject({
      type: 'SEND_MESSAGE',
      clientMessageId: 'c-1',
      conversationId: 'conv-1',
    })
  })

  it('a re-send (reconnect flush) appends a second sent step — never merged, never fabricated', () => {
    let ledger: TransportLedger = EMPTY_TRANSPORT_LEDGER
    ledger = recordCommandQueued(ledger, command('c-1'), T0)
    ledger = recordCommandSent(ledger, command('c-1'), T1)
    ledger = recordCommandSent(ledger, command('c-1'), T2)

    expect(recordForPending(ledger, 'c-1')?.steps.map((s) => s.kind)).toEqual([
      'queued',
      'sent',
      'sent',
    ])
    expect(recordForPending(ledger, 'c-1')?.frames.map((frame) => frame.name)).toEqual([
      'SEND_MESSAGE',
      'SEND_MESSAGE',
    ])
    expect(recordForPending(ledger, 'c-1')?.frames.map((frame) => frame.observedAt)).toEqual([
      T1,
      T2,
    ])
  })

  it('ignores a wire write for a command it never saw queued (observations only for known commands)', () => {
    const ledger = recordCommandSent(EMPTY_TRANSPORT_LEDGER, command('stray'), T0)
    expect(ledger.size).toBe(0)
  })
})

describe('recordInboundEvent', () => {
  it('MESSAGE_ACK completes the command record with its server identity and frame', () => {
    let ledger: TransportLedger = EMPTY_TRANSPORT_LEDGER
    ledger = recordCommandQueued(ledger, command('c-1'), T0)
    ledger = recordInboundEvent(ledger, ackEvent('c-1', 'm-1'), T2)

    const record = recordForPending(ledger, 'c-1')
    expect(record?.steps.map((s) => s.kind)).toEqual(['queued', 'acknowledged'])
    expect(record?.messageId).toBe('m-1')
    expect(record?.frames.at(-1)?.name).toBe('MESSAGE_ACK')
  })

  it('an ack for a command this session never queued still creates an honest record (the ack WAS observed)', () => {
    const ledger = recordInboundEvent(EMPTY_TRANSPORT_LEDGER, ackEvent('c-9', 'm-9'), T0)
    const record = recordForPending(ledger, 'c-9')
    expect(record?.steps.map((s) => s.kind)).toEqual(['acknowledged'])
  })

  it('NEW_MESSAGE records delivery keyed by the server id, keeping the sender’s token as data', () => {
    let ledger: TransportLedger = EMPTY_TRANSPORT_LEDGER
    const event = newMessageEvent('m-7')
    ledger = recordInboundEvent(ledger, event, T1)

    const record = recordForMessage(ledger, event.message)
    expect(record).toBeDefined()
    expect(record?.messageId).toBe('m-7')
    expect(record?.clientMessageId).toBe('sender-token-m-7') // the SENDER's token
    expect(record?.steps.map((s) => s.kind)).toEqual(['delivered'])
    expect(record?.frames[0]?.name).toBe('NEW_MESSAGE')
  })

  it('a correlated ERROR appends a rejected step with the code; an uncorrelated one records nothing', () => {
    let ledger: TransportLedger = EMPTY_TRANSPORT_LEDGER
    ledger = recordCommandQueued(ledger, command('c-1'), T0)
    ledger = recordInboundEvent(
      ledger,
      {
        type: 'ERROR',
        clientMessageId: 'c-1',
        code: 'PERSISTENCE_ERROR',
        reason: 'could not store',
      },
      T1,
    )
    const record = recordForPending(ledger, 'c-1')
    expect(record?.steps.at(-1)).toMatchObject({
      kind: 'rejected',
      code: 'PERSISTENCE_ERROR',
      reason: 'could not store',
    })

    const untouched = recordInboundEvent(
      ledger,
      { type: 'ERROR', code: 'INVALID_COMMAND', reason: 'bad frame' },
      T2,
    )
    expect(untouched).toBe(ledger) // no record named, nothing recorded
  })
})

describe('recordForMessage lookup', () => {
  it('finds an own acked message through its command record (the token ships on the message)', () => {
    let ledger: TransportLedger = EMPTY_TRANSPORT_LEDGER
    ledger = recordCommandQueued(ledger, command('c-1'), T0)
    const ack = ackEvent('c-1', 'm-1')
    ledger = recordInboundEvent(ledger, ack, T2)

    expect(recordForMessage(ledger, ack.message)?.steps.map((s) => s.kind)).toEqual([
      'queued',
      'acknowledged',
    ])
  })

  it('returns undefined for a message this session never observed (the drawer states that in words)', () => {
    const message = newMessageEvent('m-unseen').message
    expect(recordForMessage(EMPTY_TRANSPORT_LEDGER, message)).toBeUndefined()
  })
})

describe('the bound', () => {
  it('discards the OLDEST records first once the limit is exceeded', () => {
    let ledger: TransportLedger = EMPTY_TRANSPORT_LEDGER
    const total = TRANSPORT_LEDGER_LIMIT + 2
    for (let i = 0; i < total; i += 1) {
      ledger = recordCommandQueued(ledger, command(`c-${i}`), T0)
    }
    expect(ledger.size).toBe(TRANSPORT_LEDGER_LIMIT)
    // The two oldest are gone; the newest survive.
    expect(recordForPending(ledger, 'c-0')).toBeUndefined()
    expect(recordForPending(ledger, 'c-1')).toBeUndefined()
    expect(recordForPending(ledger, 'c-2')).toBeDefined()
    expect(recordForPending(ledger, `c-${total - 1}`)).toBeDefined()
  })
})
