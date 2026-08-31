/**
 * Correlated-rejection wording (OpenSpec task 6.6, spec `connection-state`:
 * "word the rejection from the code, not the reason text").
 *
 * A correlated `ERROR` frame carries BOTH a stable machine-readable `code`
 * and a human-readable `reason`. The reason is a server-side debug string
 * (`docs/openapi.yaml`: "Human-readable detail for logging/debugging only");
 * wording the UI from it would leak implementation detail into the product
 * and change every time the backend rephrased a message. So the rejection's
 * user-facing words come from this fixed table over the wire codes
 * (`ERROR_CODES`), and the raw `reason` is retained against the pending
 * message only as diagnostic data (inspector/debug territory, slice 6).
 *
 * Adding a backend error code means adding a row here AND to
 * `ERROR_CODES`' parse set — a code that arrives but has no row falls to
 * `unrecognisedRejectionWording` rather than to the reason text.
 */

import { ERROR_CODES, type ErrorCode } from './realtime'

/** Fixed words per wire code. Wording states what failed, never server internals. */
export const REJECTION_WORDING: Readonly<Record<ErrorCode, string>> = {
  [ERROR_CODES.INVALID_COMMAND]: 'The server did not understand this message.',
  [ERROR_CODES.INVALID_CONTENT]: 'This message content was rejected.',
  [ERROR_CODES.CONVERSATION_NOT_FOUND]: 'This conversation no longer exists.',
  [ERROR_CODES.FORBIDDEN]: 'You cannot send messages in this conversation.',
  [ERROR_CODES.CLIENT_MESSAGE_ID_CONFLICT]: 'This message was already submitted.',
  [ERROR_CODES.PERSISTENCE_ERROR]: 'This message could not be saved.',
}

/** Status words shown for a rejected bubble (task 2.3's word-first rule). */
export const REJECTED_STATUS = 'Failed to send'

/**
 * Fallback for a code with no table row (an `ERROR_CODES` entry someone
 * removed the wording for, or a future code accepted by a lenient parse):
 * still words FROM THE CODE — the code itself is the stable thing.
 */
export function unrecognisedRejectionWording(code: string): string {
  return `This message was rejected (${code}).`
}

/** The words shown for a rejected message, derived only from the code. */
export function rejectionWording(code: string): string {
  const wording = (REJECTION_WORDING as Readonly<Record<string, string>>)[code]
  return wording ?? unrecognisedRejectionWording(code)
}