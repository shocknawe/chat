import { useCallback, useState } from 'react'
import {
  EMPTY_TRANSPORT_LEDGER,
  recordCommandQueued,
  recordCommandSent,
  recordInboundEvent,
  type LedgerEvent,
  type TransportLedger,
} from '../inspector/transportLedger'
import type { SendMessageCommand } from '../realtime'

/**
 * Slice 6 (task 7.2): the transport ledger held in React state so the OPEN
 * info drawer live-updates as observations land (spec: "Contents update
 * live"). Session-scoped by placement: the hook's owner is `SignedInShell`,
 * which is keyed on `user.id`, so an identity switch — and a reload — start a
 * fresh, empty ledger, exactly the boundary the drawer states in words.
 *
 * Every mutator stamps the observation with the client's wall clock at call
 * time; the pure functions and the bound/discard-oldest rule live in
 * `inspector/transportLedger.ts`.
 */
export interface UseTransportLedgerResult {
  ledger: TransportLedger
  observeQueued: (command: Omit<SendMessageCommand, 'type'>) => void
  /** The socket stamps the wire-write instant; the ledger keeps THAT time. */
  observeSent: (command: Omit<SendMessageCommand, 'type'>, sentAt: string) => void
  observeEvent: (event: LedgerEvent) => void
}

export function useTransportLedger(): UseTransportLedgerResult {
  const [ledger, setLedger] = useState<TransportLedger>(EMPTY_TRANSPORT_LEDGER)

  const observeQueued = useCallback((command: Omit<SendMessageCommand, 'type'>) => {
    setLedger((prev) => recordCommandQueued(prev, command, new Date().toISOString()))
  }, [])

  const observeSent = useCallback((command: Omit<SendMessageCommand, 'type'>, sentAt: string) => {
    setLedger((prev) => recordCommandSent(prev, command, sentAt))
  }, [])

  const observeEvent = useCallback((event: LedgerEvent) => {
    setLedger((prev) => recordInboundEvent(prev, event, new Date().toISOString()))
  }, [])

  return { ledger, observeQueued, observeSent, observeEvent }
}
