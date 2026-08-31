import { Fragment, useEffect, useMemo, useRef, type MutableRefObject } from 'react'
import { useQuery } from '@tanstack/react-query'
import { type Conversation, type Message, type User } from '../api'
import {
  REJECTED_STATUS,
  rejectionWording,
} from '../rejectionWording'
import { conversationLabel } from './ConversationList'
import { Composer } from './Composer'
import type { PendingMessage } from '../hooks/usePendingMessages'
import type { InspectMessageKey } from '../inspector/transportLedger'
import { initials } from '../initials'
import { compareMessages } from '../messageOrder'
import { fetchMergedHistory } from '../messagesCache'
import { SENDING_PREVIEW, WAITING_PREVIEW } from '../conversationPreview'
import type { ConnectionState } from '../realtime'

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
 * or surprise anyone under reduced motion. (task 2.7 keeps it that way —
 * there is no scroll animation to gate behind `prefers-reduced-motion`.)
 */

/** Distance from the bottom (px) within which a realtime append still auto-scrolls. */
const NEAR_BOTTOM_THRESHOLD_PX = 100

/** Consecutive messages from one sender, in arrival order. */
interface MessageGroup {
  senderId: string
  messages: Message[]
}

/** One calendar day's worth of sender groups, headed by a date divider (task 2.3). */
interface DaySection {
  key: string
  label: string
  groups: MessageGroup[]
}

function dayKey(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
}

function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

const dayLabelFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'full' })

/** "Today" / "Yesterday" for the two recent, common cases; otherwise a full date. */
function formatDayLabel(iso: string): string {
  const date = new Date(iso)
  const diffDays = Math.round((startOfDay(new Date()) - startOfDay(date)) / 86_400_000)
  if (diffDays === 0) return 'Today'
  if (diffDays === 1) return 'Yesterday'
  return dayLabelFormat.format(date)
}

/**
 * Groups chronologically sorted messages first by calendar day, then by
 * consecutive sender within each day — a day never merges into its
 * neighbour even if the same person sent the last message of one day and
 * the first of the next (task 2.3's date-divider requirement).
 */
function groupByDay(sorted: Message[]): DaySection[] {
  const sections: DaySection[] = []
  for (const message of sorted) {
    const key = dayKey(message.createdAt)
    let section = sections.at(-1)
    if (section === undefined || section.key !== key) {
      section = { key, label: formatDayLabel(message.createdAt), groups: [] }
      sections.push(section)
    }
    const lastGroup = section.groups.at(-1)
    if (lastGroup !== undefined && lastGroup.senderId === message.senderId) {
      lastGroup.messages.push(message)
    } else {
      section.groups.push({ senderId: message.senderId, messages: [message] })
    }
  }
  return sections
}

const timeFormat = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' })

interface ThreadPaneProps {
  currentUser: User
  conversation: Conversation | null
  /**
   * All of the current user's optimistic outbound items (task 6.3); this pane
   * renders the slice belonging to `conversation`. Lives outside the TanStack
   * cache until 6.4 reconciles each item by `clientMessageId`.
   */
  pendingMessages: PendingMessage[]
  /**
   * Slice 5 (tasks 6.3/6.4): the live realtime connection state. Anything
   * other than `connected` renders the in-thread offline banner (with the
   * "Reconnect now" control, task 6.1) and switches the queued-bubble wording
   * to "Waiting for connection…"; the composer gets its waiting copy too.
   */
  connectionState: ConnectionState
  /**
   * Slice 5 (task 6.4): a transient recovery confirmation the shell just
   * raised (and will withdraw on its own timer). Rendered as an in-thread
   * status line, never as a sticky banner.
   */
  recoveryNotice: boolean
  /** Slice 5 (task 6.1): cancel the scheduled reconnect delay and dial now. */
  onReconnectNow: () => void
  /**
   * Slice 5 (task 6.8): re-submits a rejected message under its ORIGINAL
   * `clientMessageId`. The only retry path there is — never automatic.
   */
  onRetryMessage: (pending: PendingMessage) => void
  /** Submits a boundary-trimmed, non-empty draft for `conversation`. */
  onSendMessage: (content: string) => void
  /**
   * Programmatic handle to the composer textarea (task 3.7): after a new
   * conversation is created via the `+` dialog, focus moves here.
   */
  composerFocusRef?: MutableRefObject<HTMLTextAreaElement | null>
  /**
   * Slice 6 (task 7.4): whether the info drawer is open at all (drives the
   * header control's `aria-expanded`; per-message affordances are expanded
   * only while THEIR message is the inspected one).
   */
  inspectorOpen: boolean
  /** The inspected message's identity, or null when none/connection view. */
  inspectedKey: InspectMessageKey | null
  /**
   * Task 7.4: opens (or re-targets) the drawer on one message's record.
   * `origin` is the pressed ⓘ control — its element identity is the focus-
   * return target on close (task 7.6).
   */
  onInspectMessage: (key: InspectMessageKey, origin: HTMLElement) => void
  /** The thread-header control: toggles the drawer on the connection view. */
  onToggleInspector: () => void
  /**
   * Handle to the thread-header inspector control (task 7.6): the focus-return
   * fallback when a closing drawer's originator no longer exists.
   */
  inspectorControlRef: MutableRefObject<HTMLButtonElement | null>
}

export function ThreadPane({
  currentUser,
  conversation,
  pendingMessages,
  connectionState,
  recoveryNotice,
  onReconnectNow,
  onRetryMessage,
  onSendMessage,
  composerFocusRef,
  inspectorOpen,
  inspectedKey,
  onInspectMessage,
  onToggleInspector,
  inspectorControlRef,
}: ThreadPaneProps) {
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

  const sections = useMemo(() => (messages === undefined ? undefined : groupByDay(messages)), [messages])

  // If the pending block lands on a different calendar day than the last
  // history section (e.g. composing across midnight), it gets its own
  // divider too — the same rule that separates history sections.
  const firstPending = threadPending.at(0)
  const lastSection = sections?.at(-1)
  const pendingDivider =
    firstPending !== undefined && (lastSection === undefined || lastSection.key !== dayKey(firstPending.createdAt))
      ? formatDayLabel(firstPending.createdAt)
      : undefined

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

  const otherName = conversationLabel(conversation, currentUser.id)

  return (
    <section className="card thread-pane" aria-labelledby="thread-title">
      <header className="thread-header">
        <span className="avatar" aria-hidden="true">
          {initials(otherName)}
        </span>
        <div className="thread-heading">
          <h1 id="thread-title" className="title thread-title">
            {otherName}
          </h1>
          {/* Honest supporting line (task 2.3): every conversation in this
              product is a 1:1 direct message, so this is a true statement
              about the conversation, not a fabricated presence/activity
              signal — presence lands in a later slice. */}
          <p className="meta thread-subtitle">Direct message</p>
        </div>
        {/* Slice 6 (tasks 7.4–7.6): the thread-header inspector control. It
            toggles the drawer with NO message selected (the connection and
            protocol view), exposes its expanded state, and — via
            `inspectorControlRef` — is the focus-return fallback when a closing
            drawer's opening control no longer exists. */}
        <button
          ref={inspectorControlRef}
          type="button"
          className="icon-button thread-inspector-toggle"
          aria-label="Conversation info"
          title="Conversation info"
          aria-expanded={inspectorOpen}
          aria-controls="inspector-panel"
          onClick={onToggleInspector}
        >
          <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M8 5h12M8 12h12M8 19h12"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
            />
            <circle cx="4" cy="5" r="1.1" fill="currentColor" />
            <circle cx="4" cy="12" r="1.1" fill="currentColor" />
            <circle cx="4" cy="19" r="1.1" fill="currentColor" />
          </svg>
        </button>
      </header>
      {/* Slice 5, tasks 6.3/6.4: connection state stated INSIDE the
          conversation, in words (Status-Is-Text). Two mutually exclusive
          in-thread states, both replacing the other rather than stacking:
          - offline: what is wrong AND what is being done about it, plus the
            task-6.1 "Reconnect now" control (cancel the scheduled delay and
            dial immediately — the socket's no-ops make the control safe).
          - recovery: a transient "Reconnected" line the shell withdraws on
            its own timer; no user action, no sticky confirmation. */}
      {(connectionState !== 'connected' || recoveryNotice) && (
        <div
          className={
            connectionState !== 'connected'
              ? 'thread-connection thread-connection--offline'
              : 'thread-connection thread-connection--restored'
          }
          role="status"
        >
          {connectionState !== 'connected' ? (
            <>
              <p className="thread-connection-copy">
                <strong>Realtime messaging unavailable.</strong>{' '}
                <span className="thread-connection-detail">
                  Reconnecting automatically; history is still available.
                </span>
              </p>
              <button
                type="button"
                className="btn btn-secondary thread-connection-retry"
                onClick={onReconnectNow}
              >
                Reconnect now
              </button>
            </>
          ) : (
            <p className="thread-connection-copy">
              <strong>Reconnected.</strong>{' '}
              <span className="thread-connection-detail">
                Conversation history is up to date.
              </span>
            </p>
          )}
        </div>
      )}
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

        {sections !== undefined && sections.length === 0 && threadPending.length === 0 && (
          <div className="thread-region thread-region--center">
            <div className="thread-empty">
              <p className="meta">No messages yet — send the first message.</p>
            </div>
          </div>
        )}

        {/* History sections (each headed by a date divider after the first),
            then the pending slice in-flow after them. The list renders
            whenever either source has items so a first-ever message skips
            the empty state and appears immediately as pending. */}
        {sections !== undefined && (sections.length > 0 || threadPending.length > 0) && (
          <div className="thread-region">
            <ol className="message-list">
              {sections.map((section, sectionIndex) => (
                <Fragment key={section.key}>
                  {sectionIndex > 0 && (
                    <li className="date-divider" aria-hidden="true">
                      <span>{section.label}</span>
                    </li>
                  )}
                  {section.groups.map((group) => {
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
                          // Slice 6 (task 7.4): the inspected message is
                          // distinguished visually (the ring lands on the
                          // bubble) and its ⓘ affordance is expanded.
                          const isInspected =
                            inspectorOpen &&
                            inspectedKey?.by === 'id' &&
                            inspectedKey.messageId === message.id
                          const classes = [
                            'message-bubble',
                            isOwn ? 'message-bubble--own' : 'message-bubble--other',
                            isLast
                              ? isOwn
                                ? 'message-bubble--corner-own'
                                : 'message-bubble--corner-other'
                              : '',
                          ]
                            .filter((c) => c !== '')
                            .join(' ')
                          return (
                            <div
                              key={message.id}
                              className={
                                isInspected
                                  ? 'message-item message-item--inspected'
                                  : 'message-item'
                              }
                            >
                              <p className={classes}>{message.content}</p>
                              {/* Task 7.4: the accessibly named ⓘ affordance on
                                  EVERY message. `origin` (currentTarget) is the
                                  focus-return target on drawer close (7.6). */}
                              <button
                                type="button"
                                className="info-button"
                                aria-label={`Message info: ${message.content}`}
                                title="Message info"
                                aria-expanded={isInspected}
                                aria-controls="inspector-panel"
                                onClick={(event) =>
                                  onInspectMessage(
                                    { by: 'id', messageId: message.id },
                                    event.currentTarget,
                                  )
                                }
                              >
                                <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
                                  <circle cx="12" cy="12" r="9" />
                                  <path d="M12 11v5" />
                                  <path d="M12 7.6v.6" />
                                </svg>
                              </button>
                            </div>
                          )
                        })}
                        {/* Delivery status stated in words (task 2.3 / spec
                            "Message rows state status"): the backend ack
                            confirms persistence, not delivery or reading, so
                            an authoritative message reads "Sent"/"Received" —
                            never a fabricated "Delivered". Alignment (own
                            messages right-aligned, no sender name) already
                            distinguishes authorship; this word is the second,
                            non-colour signal. */}
                        <span className="meta message-meta">
                          {isOwn ? 'Sent · ' : 'Received · '}
                          {timeFormat.format(new Date(last.createdAt))}
                        </span>
                      </li>
                    )
                  })}
                </Fragment>
              ))}
              {threadPending.length > 0 && (
                <>
                  {pendingDivider !== undefined && (
                    <li className="date-divider" aria-hidden="true">
                      <span>{pendingDivider}</span>
                    </li>
                  )}
                  <li className="message-group message-group--own" aria-label="Pending messages">
                    {/* Pending items are always the current user's own: they sit
                        under history, subdued, each with an explicit status word
                        so the optimistic state is announced, not just tinted.
                        Slice 5 (task 6.3): the queued word becomes "Waiting for
                        connection…" while realtime is down. (task 6.8): a
                        rejected bubble words its rejection FROM THE ERROR CODE
                        (task 6.6) and carries the only retry control there is —
                        the submission is manual, under the original token. */}
                    {threadPending.map((pending) => (
                      <Fragment key={pending.clientMessageId}>
                        <div
                          className={
                            inspectorOpen &&
                            inspectedKey?.by === 'clientMessageId' &&
                            inspectedKey.clientMessageId === pending.clientMessageId
                              ? 'message-item message-item--inspected'
                              : 'message-item'
                          }
                        >
                          <p className="message-bubble message-bubble--own message-bubble--pending">
                            {pending.content}
                          </p>
                          {/* Task 7.4: own PENDING messages carry the same ⓘ
                              affordance — keyed by their correlation token
                              until the ack re-keys the inspection (task 7.9). */}
                          <button
                            type="button"
                            className="info-button"
                            aria-label={`Message info: ${pending.content}`}
                            title="Message info"
                            aria-expanded={
                              inspectorOpen &&
                              inspectedKey?.by === 'clientMessageId' &&
                              inspectedKey.clientMessageId === pending.clientMessageId
                            }
                            aria-controls="inspector-panel"
                            onClick={(event) =>
                              onInspectMessage(
                                { by: 'clientMessageId', clientMessageId: pending.clientMessageId },
                                event.currentTarget,
                              )
                            }
                          >
                            <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
                              <circle cx="12" cy="12" r="9" />
                              <path d="M12 11v5" />
                              <path d="M12 7.6v.6" />
                            </svg>
                          </button>
                        </div>
                        <span
                          className={
                            pending.status === 'failed'
                              ? 'meta message-meta message-meta--failed'
                              : 'meta message-meta'
                          }
                        >
                          {pending.status === 'failed'
                            ? `${REJECTED_STATUS} ${rejectionWording(pending.errorCode ?? '')}`
                            : connectionState !== 'connected'
                              ? WAITING_PREVIEW
                              : SENDING_PREVIEW}
                        </span>
                        {pending.status === 'failed' && (
                          <button
                            type="button"
                            className="message-retry"
                            onClick={() => onRetryMessage(pending)}
                          >
                            Try again
                          </button>
                        )}
                      </Fragment>
                    ))}
                  </li>
                </>
              )}
            </ol>
          </div>
        )}
      </div>
      <Composer
        ariaLabel={`Message to ${otherName}`}
        waitingForConnection={connectionState !== 'connected'}
        onSubmit={onSendMessage}
        focusRef={composerFocusRef}
      />
    </section>
  )
}
