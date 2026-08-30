import { Fragment, useEffect, useMemo, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { type Conversation, type Message, type User } from '../api'
import { conversationLabel } from './ConversationList'
import { Composer } from './Composer'
import type { PendingMessage } from '../hooks/usePendingMessages'
import { initials } from '../initials'
import { compareMessages } from '../messageOrder'
import { fetchMergedHistory } from '../messagesCache'

/**
 * Thread pane (task 5.3) — the selected conversation's message history.
 *
 * Data: TanStack Query keyed `['messages', userId, conversationId]`, so an
 * identity switch partitions the cache and a stale id can never leak across
 * users. The query is enabled only while a conversation is selected.
 *
 * Ordering: the backend returns history in deterministic chronological order
 * (createdAt ASC, id ASC). The defensive `useMemo` sort below re-applies that
 * exact invariant via the shared `compareMessages` comparator (numeric epoch
 * compare + id tiebreak — lexical compare misorders Jackson's variable-width
 * fractional seconds), so render order and the task-6.4 cache upsert order
 * can never disagree.
 *
 * Scroll: the thread is anchored to the bottom (DESIGN.md), but realtime
 * appends (task 6.5) must not yank a reader who scrolled up in the history.
 * The scroll effect distinguishes the cases:
 *
 * - Conversation switch or first data arrival: unconditional snap to the
 *   latest message — an initial load owns the viewport.
 * - The reader's own pending append: unconditional — a just-sent message
 *   should snap into view.
 * - Any other append (realtime NEW_MESSAGE): scroll only if the reader was
 *   already within ~100px of the bottom BEFORE the content grew. A
 *   scrolled-up reader keeps their place; a stronger "new messages"
 *   affordance is WP8 visual-design territory, so this is intentionally
 *   the simple near-bottom rule.
 *
 * Positioning is instant rather than animated, so it cannot fight the reader
 * or surprise anyone under reduced motion.
 */

/** Distance from the bottom (px) within which a realtime append still auto-scrolls. */
const NEAR_BOTTOM_THRESHOLD_PX = 100

/** Consecutive messages from one sender, in arrival order. */
interface MessageGroup {
  senderId: string
  messages: Message[]
}

function groupBySender(sorted: Message[]): MessageGroup[] {
  const groups: MessageGroup[] = []
  for (const message of sorted) {
    const last = groups.at(-1)
    if (last !== undefined && last.senderId === message.senderId) {
      last.messages.push(message)
    } else {
      groups.push({ senderId: message.senderId, messages: [message] })
    }
  }
  return groups
}

const timestampFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
})

interface ThreadPaneProps {
  currentUser: User
  conversation: Conversation | null
  /**
   * All of the current user's optimistic outbound items (task 6.3); this pane
   * renders the slice belonging to `conversation`. Lives outside the TanStack
   * cache until 6.4 reconciles each item by `clientMessageId`.
   */
  pendingMessages: PendingMessage[]
  /** Submits a boundary-trimmed, non-empty draft for `conversation`. */
  onSendMessage: (content: string) => void
}

export function ThreadPane({ currentUser, conversation, pendingMessages, onSendMessage }: ThreadPaneProps) {
  // Task 6.7: history lands through the REST↔realtime MERGE, not a blind
  // overwrite. `fetchMergedHistory` unions the response with whatever ack /
  // NEW_MESSAGE upserts landed in this cache entry while the fetch was in
  // flight, so a mid-flight realtime message is neither erased by the commit
  // nor duplicated. Dedupe/ordering invariants live in
  // `mergeHistoryWithCache`; the `useMemo` sort below stays as the final
  // render guard.
  const messagesQuery = useQuery({
    queryKey: ['messages', currentUser.id, conversation?.id ?? ''],
    queryFn: () => fetchMergedHistory(currentUser.id, conversation?.id ?? ''),
    enabled: conversation !== null,
  })

  // Per-conversation slice of the shared pending store, in submission order.
  const threadPending = useMemo(
    () => pendingMessages.filter((m) => m.conversationId === conversation?.id),
    [pendingMessages, conversation?.id],
  )

  const messages = useMemo(() => {
    if (messagesQuery.data === undefined) return undefined
    return [...messagesQuery.data].sort(compareMessages)
  }, [messagesQuery.data])

  const groups = useMemo(() => (messages === undefined ? undefined : groupBySender(messages)), [messages])

  const scrollRef = useRef<HTMLDivElement | null>(null)
  const messageCount = messages?.length
  const conversationId = conversation?.id
  const pendingCount = threadPending.length

  // Bottom-anchor tracking. `nearBottomRef` is maintained by the scroll
  // handler, so the append effect below knows where the reader was BEFORE the
  // arriving message grew the list — computing the distance inside the effect
  // itself would already include the new content's height.
  const nearBottomRef = useRef(true)
  const prevConversationIdRef = useRef<string | undefined>(undefined)
  const prevPendingCountRef = useRef(0)

  const handleScroll = () => {
    const region = scrollRef.current
    if (region !== null) {
      nearBottomRef.current =
        region.scrollHeight - region.scrollTop - region.clientHeight < NEAR_BOTTOM_THRESHOLD_PX
    }
  }

  // Bottom anchor. Runs after the DOM commit, so scrollHeight includes the
  // newly rendered list. See the module header for the per-case policy.
  useEffect(() => {
    const region = scrollRef.current
    const conversationChanged = prevConversationIdRef.current !== conversationId
    prevConversationIdRef.current = conversationId
    const ownPendingAppended = pendingCount > prevPendingCountRef.current
    prevPendingCountRef.current = pendingCount

    // A switch re-anchors the thread: reset near-bottom so the first data
    // arrival on the new conversation snaps like an initial load even if the
    // reader had scrolled up in the previous one.
    if (conversationChanged) {
      nearBottomRef.current = true
    }

    if (
      region !== null &&
      (messageCount !== undefined || pendingCount > 0) &&
      (conversationChanged || ownPendingAppended || nearBottomRef.current)
    ) {
      region.scrollTop = region.scrollHeight
    }
  }, [conversationId, messageCount, pendingCount])

  const resolveSender = (senderId: string): string =>
    conversation?.participants.find((p) => p.id === senderId)?.displayName ?? 'Someone'

  if (conversation === null) {
    return (
      <section className="card thread-pane" aria-labelledby="thread-title">
        <div className="thread-empty">
          <h1 id="thread-title" className="title">
            No conversation selected
          </h1>
          <p className="meta">Pick a conversation on the left to start reading.</p>
        </div>
      </section>
    )
  }

  return (
    <section className="card thread-pane" aria-labelledby="thread-title">
      <header className="thread-header">
        <h1 id="thread-title" className="title">
          {conversationLabel(conversation, currentUser.id)}
        </h1>
      </header>
      <div className="thread-scroll" ref={scrollRef} onScroll={handleScroll}>
        {messagesQuery.isPending && (
          <div className="thread-region">
            <p role="status" className="meta thread-status">
              Loading the conversation…
            </p>
            <div className="message-list" aria-hidden="true">
              <div className="message-skeleton" />
              <div className="message-skeleton message-skeleton--own" />
              <div className="message-skeleton" />
            </div>
          </div>
        )}

        {messagesQuery.error !== null && (
          <div className="thread-region thread-region--center">
            <div className="thread-error" role="alert">
              <p className="thread-error-title">We couldn't load this conversation.</p>
              <p className="meta thread-error-detail">{messagesQuery.error.message}</p>
              <button
                type="button"
                className="btn btn-secondary thread-retry"
                onClick={() => void messagesQuery.refetch()}
              >
                Try again
              </button>
            </div>
          </div>
        )}

        {groups !== undefined && groups.length === 0 && threadPending.length === 0 && (
          <div className="thread-region thread-region--center">
            <div className="thread-empty">
              <p className="meta">No messages yet — send the first message.</p>
            </div>
          </div>
        )}

        {/* History groups, then the pending slice in-flow after them. The list
            renders whenever either source has items so a first-ever message
            skips the empty state and appears immediately as pending. */}
        {groups !== undefined && (groups.length > 0 || threadPending.length > 0) && (
          <div className="thread-region">
            <ol className="message-list">
              {groups.map((group) => {
                // Groups are built non-empty; the guard satisfies
                // noUncheckedIndexedAccess without assertions.
                const first = group.messages[0]
                const last = group.messages.at(-1)
                if (first === undefined || last === undefined) return null
                const isOwn = group.senderId === currentUser.id
                const senderName = resolveSender(group.senderId)
                return (
                  <li
                    key={first.id}
                    className={isOwn ? 'message-group message-group--own' : 'message-group'}
                  >
                    {!isOwn && (
                      <span className="message-sender">
                        <span className="avatar avatar--sm" aria-hidden="true">
                          {initials(senderName)}
                        </span>
                        <span className="message-sender-name">{senderName}</span>
                      </span>
                    )}
                    {group.messages.map((message, index) => {
                      const isLast = index === group.messages.length - 1
                      const classes = [
                        'message-bubble',
                        isOwn ? 'message-bubble--own' : 'message-bubble--other',
                        isLast ? (isOwn ? 'message-bubble--corner-own' : 'message-bubble--corner-other') : '',
                      ]
                        .filter((c) => c !== '')
                        .join(' ')
                      return (
                        <p key={message.id} className={classes}>
                          {message.content}
                        </p>
                      )
                    })}
                    <span className="meta message-meta">
                      {timestampFormat.format(new Date(last.createdAt))}
                    </span>
                  </li>
                )
              })}
              {threadPending.length > 0 && (
                <li className="message-group message-group--own" aria-label="Pending messages">
                  {/* Pending items are always the current user's own: they sit
                      under history, subdued, each with an explicit status word
                      so the optimistic state is announced, not just tinted. */}
                  {threadPending.map((pending) => (
                    <Fragment key={pending.clientMessageId}>
                      <p className="message-bubble message-bubble--own message-bubble--pending">
                        {pending.content}
                      </p>
                      <span
                        className={
                          pending.status === 'failed'
                            ? 'meta message-meta message-meta--failed'
                            : 'meta message-meta'
                        }
                      >
                        {pending.status === 'failed' ? 'Failed to send' : 'Sending…'}
                      </span>
                    </Fragment>
                  ))}
                </li>
              )}
            </ol>
          </div>
        )}
      </div>
      <Composer
        ariaLabel={`Message to ${conversationLabel(conversation, currentUser.id)}`}
        onSubmit={onSendMessage}
      />
    </section>
  )
}
