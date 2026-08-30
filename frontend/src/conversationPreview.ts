/**
 * Rail preview wording (OpenSpec tasks 4.2–4.3, spec `conversation-previews`).
 *
 * The rail row summarises the conversation's latest activity from ONE of two
 * sources, in priority order:
 *
 * 1. **Client-side pending/failed state (task 4.3).** WHEN the current user's
 *    NEWEST message in the conversation is awaiting acknowledgement or was
 *    rejected, that state overrides the server preview with the same status
 *    words the thread uses ("Sending…" / "Failed to send").
 * 2. **The server preview (task 4.2).** Otherwise the row reads the
 *    conversation's `lastMessage` content, or "No messages yet" for an empty
 *    history (`lastMessage` absent — which is identical to null per the
 *    task-4.0 decision).
 *
 * The override condition is "newest activity", deliberately simple: the
 * newest pending/failed item for the conversation still counts as the newest
 * activity only while the server preview is NOT newer. A pending item is
 * submitted before the server stamps the corresponding message, so before the
 * ack the pending item wins against the older confirmed tail; once something
 * newer is confirmed server-side (the ack itself, or a received message that
 * supersedes a earlier failed item), the server preview is newer and wins.
 * Timestamps are compared by numeric epoch (the same reasoning
 * `compareMessages` documents); a tie resolves to the override, since the
 * client cannot observe the pending item's server id and an equal-instant own
 * submission is not yet superseded.
 */

import type { Conversation } from './api'
import type { PendingMessage } from './hooks/usePendingMessages'

/** Empty history, or a preview with no content to summarise. */
export const EMPTY_PREVIEW = 'No messages yet'

/** The newest own message is awaiting acknowledgement (task 4.3). */
export const SENDING_PREVIEW = 'Sending…'

/** The newest own message was rejected (task 4.3). */
export const FAILED_PREVIEW = 'Failed to send'

/**
 * The pending/failed item that is the newest activity for `conversationId`,
 * or undefined. The pending store is per-identity (hooked off the shell keyed
 * on `user.id`) and only ever receives the current user's own submissions
 * (`addPending` is called from the shell's submit path alone), so no sender
 * filter is needed — everything in it is "the current user's message".
 */
function latestPendingFor(
  pendingMessages: readonly PendingMessage[],
  conversationId: string,
): PendingMessage | undefined {
  return pendingMessages.filter((m) => m.conversationId === conversationId).at(-1)
}

/**
 * The rail preview text for `conversation`, applying the task-4.3
 * pending/failed override over the task-4.2 server preview.
 */
export function conversationPreview(
  conversation: Pick<Conversation, 'id' | 'lastMessage'>,
  pendingMessages: readonly PendingMessage[],
): string {
  const pending = latestPendingFor(pendingMessages, conversation.id)
  // Task 4.0: absent === null. The backend OMITS the property for an empty
  // history and never emits a literal null, but the helper honours that
  // contract defensively by normalising null to absent.
  const lastMessage = conversation.lastMessage ?? undefined
  const serverPreview =
    lastMessage !== undefined && lastMessage.content !== '' ? lastMessage.content : EMPTY_PREVIEW

  if (pending === undefined) {
    return serverPreview
  }

  // The override stands only while the pending item IS the newest activity: a
  // server-side tail stamped strictly later (the ack itself, or a received
  // message that supersedes an earlier failed item) wins instead. A tie goes
  // to the pending item.
  const serverTailNewer =
    lastMessage !== undefined && Date.parse(lastMessage.createdAt) > Date.parse(pending.createdAt)
  if (serverTailNewer) {
    return serverPreview
  }

  return pending.status === 'failed' ? FAILED_PREVIEW : SENDING_PREVIEW
}