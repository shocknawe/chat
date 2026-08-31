import { useEffect, useRef, type KeyboardEvent, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { Conversation, Message, User } from '../api'
import {
  recordForMessage,
  recordForPending,
  type InspectTarget,
  type TransportLedger,
  type TransportRecord,
  type TransportStep,
} from '../inspector/transportLedger'
import { fetchMergedHistory } from '../messagesCache'
import type { PendingMessage } from '../hooks/usePendingMessages'
import type { ConnectionState } from '../realtime'
import { initials } from '../initials'

/**
 * Slice 6 (tasks 7.5–7.9): the info drawer.
 *
 * Visual source of truth: docs/one-shot/oneshot-info-drawer.html (the `info`
 * panel) — subject card, lifecycle timeline, fact rows, wire frames, and the
 * caveat strip are carried over with `inspector-` class names.
 *
 * Presentation (task 7.5): the SHELL decides `overlay` from the 980px
 * threshold (`INSPECTOR_OVERLAY_QUERY`) and the drawer's job follows from it:
 *
 * - Docked (≥980px): a complementary `aside` docked beside the thread (the
 *   shell adds the third grid column); focus is NOT moved on open — the
 *   activating control keeps it.
 * - Overlay (<980px): a modal `role="dialog"` over the thread with a
 *   shell-rendered scrim; focus moves to the close control on open and Tab /
 *   Shift+Tab are confined inside the panel.
 *
 * Crossing the threshold RE-PRESENTS rather than closes: the target lives in
 * the shell, this component never unmounts on a mode change, and the inspected
 * message is retained (spec: "Mode switch retains selection").
 *
 * Honesty (tasks 7.7–7.8): everything rendered here is either persistent
 * (taken from the message itself — ids, correlation token, timestamps,
 * ordering key, content length) or session-scoped (transport steps and wire
 * frames, taken from the ledger). An unobserved field renders as absent; a
 * message with no transport record states "not observed in this session"
 * instead of rendering a column of dashes; acknowledgement is stated as
 * persistence — never delivery or reading — and fan-out is unverified.
 */

/** The documented content maximum (docs/openapi.yaml: Message.content maxLength). */
export const MAX_CONTENT_CODE_POINTS = 4000

/** The documented history ordering key — there are no sequence numbers. */
const ORDERING_KEY = '(createdAt ASC, id ASC)'

const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

/** HH:MM:SS.mmm for the timeline stamps (observed steps always carry one). */
function clockStamp(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (value: number, width: number) => String(value).padStart(width, '0')
  return `${pad(date.getHours(), 2)}:${pad(date.getMinutes(), 2)}:${pad(date.getSeconds(), 2)}.${pad(
    date.getMilliseconds(),
    3,
  )}`
}

/** A pending item still has no server identity; a failed one retains its rejection. */
type ResolvedInspection =
  | { kind: 'message'; message: Message }
  | { kind: 'pending'; pending: PendingMessage }
  | { kind: 'gone' }

interface InspectorDrawerProps {
  /** What the drawer presents (message record vs connection/protocol view). */
  target: InspectTarget
  /** Below the 980px threshold the drawer overlays the thread (dialog semantics). */
  overlay: boolean
  currentUser: User
  conversation: Conversation
  pendingMessages: PendingMessage[]
  /** The session-scoped ledger (task 7.2); live-updates arrive as new state. */
  ledger: TransportLedger
  connectionState: ConnectionState
  /** Unacknowledged command count, read at render from the socket's queue. */
  getPendingCommandCount: () => number
  /** All dismissal paths (close control, scrim, Escape) route here. */
  onClose: () => void
}

export function InspectorDrawer({
  target,
  overlay,
  currentUser,
  conversation,
  pendingMessages,
  ledger,
  connectionState,
  getPendingCommandCount,
  onClose,
}: InspectorDrawerProps) {
  const rootRef = useRef<HTMLElement | null>(null)
  const closeButtonRef = useRef<HTMLButtonElement | null>(null)

  // Read-only subscription to the ALREADY-LOADED history cache entry (the
  // thread owns the fetch): `enabled: false` delivers cached data reactively —
  // so an ack landing while the drawer is open re-renders the record (task
  // 7.9) — and can never issue a request of its own.
  const messagesQuery = useQuery({
    queryKey: ['messages', currentUser.id, conversation.id],
    queryFn: () => fetchMergedHistory(currentUser.id, conversation.id),
    enabled: false,
  })

  // Overlay mode entry (open below the threshold, or a threshold CROSSING that
  // re-presents a docked drawer as an overlay): focus moves inside. The
  // crossing is a re-presentation — the DOM node and the target persist.
  useEffect(() => {
    if (overlay) closeButtonRef.current?.focus()
  }, [overlay])

  /** Focus confinement while overlaid (task 7.5): Tab and Shift+Tab wrap. */
  const handleKeyDown = (event: KeyboardEvent<HTMLElement>): void => {
    if (!overlay || event.key !== 'Tab') return
    const root = rootRef.current
    if (root === null) return
    const focusable = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
    if (focusable.length === 0) return
    const active = document.activeElement
    const currentIndex = focusable.findIndex((element) => element === active)
    event.preventDefault()
    if (event.shiftKey) {
      const previous =
        focusable[currentIndex === -1 ? focusable.length - 1 : currentIndex - 1]
      ;(previous ?? focusable.at(-1))?.focus()
    } else {
      const next =
        focusable[currentIndex === -1 || currentIndex === focusable.length - 1 ? 0 : currentIndex + 1]
      ;(next ?? focusable[0])?.focus()
    }
  }

  const resolved: ResolvedInspection | { kind: 'connection' } =
    target.kind === 'connection'
      ? { kind: 'connection' }
      : target.key.by === 'id'
        ? (() => {
            const key = target.key
            const message = messagesQuery.data?.find((m) => m.id === key.messageId)
            return message !== undefined
              ? ({ kind: 'message', message } as const)
              : ({ kind: 'gone' } as const)
          })()
        : (() => {
            const key = target.key
            const pending = pendingMessages.find((p) => p.clientMessageId === key.clientMessageId)
            return pending !== undefined
              ? ({ kind: 'pending', pending } as const)
              : ({ kind: 'gone' } as const)
          })()

  return (
    <aside
      ref={rootRef}
      id="inspector-panel"
      className={overlay ? 'card inspector-panel inspector-panel--overlay' : 'card inspector-panel'}
      // Overlay mode is modal; docked mode is a complementary region.
      role={overlay ? 'dialog' : undefined}
      aria-modal={overlay ? true : undefined}
      aria-labelledby="inspector-title"
      aria-describedby="inspector-lead"
      onKeyDown={handleKeyDown}
    >
      <div className="inspector-head">
        <div className="inspector-title-row">
          <span className="avatar avatar--sm" aria-hidden="true">
            {initials(
              resolved.kind === 'message'
                ? (conversation.participants.find((p) => p.id === resolved.message.senderId)
                    ?.displayName ?? 'Someone')
                : currentUser.displayName,
            )}
          </span>
          <h2 className="title inspector-title" id="inspector-title">
            Info
          </h2>
          <button
            ref={closeButtonRef}
            type="button"
            className="icon-button inspector-close"
            aria-label="Close info"
            title="Close info"
            onClick={onClose}
          >
            <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
              <path d="m7 7 10 10M17 7 7 17" />
            </svg>
          </button>
        </div>
        <p className="meta inspector-lead" id="inspector-lead">
          {resolved.kind === 'connection'
            ? 'Connection and protocol facts for this session. Pick the ⓘ beside a message to inspect it.'
            : "What this window and the server recorded for this message."}
        </p>
      </div>
      <div className="inspector-scroll">
        {resolved.kind === 'connection' ? (
          <ConnectionView
            currentUser={currentUser}
            conversation={conversation}
            connectionState={connectionState}
            getPendingCommandCount={getPendingCommandCount}
          />
        ) : resolved.kind === 'gone' ? (
          <p className="inspector-empty">
            This message is no longer visible in this conversation. Nothing about it is retained
            beyond the record itself.
          </p>
        ) : (
          <MessageView resolved={resolved} currentUser={currentUser} ledger={ledger} />
        )}
      </div>
    </aside>
  )
}

/* ── Small presentational pieces ──────────────────────────────────────── */

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="inspector-section">
      <h3 className="inspector-section-title">{title}</h3>
      {children}
    </section>
  )
}

/**
 * One fact row. Honesty rule (task 7.8): an unobserved value renders as
 * ABSENT — a muted em dash plus an explanatory note — never a fabricated one.
 */
function Fact({ label, value, note }: { label: string; value: string | undefined; note?: string }) {
  return (
    <div className="inspector-fact">
      <p className="inspector-fact-label">{label}</p>
      {value === undefined ? (
        <p className="inspector-fact-value inspector-fact-value--absent">—</p>
      ) : (
        <p className="inspector-fact-value data">{value}</p>
      )}
      {note !== undefined && <p className="inspector-fact-note">{note}</p>}
    </div>
  )
}

type StepState = 'done' | 'failed' | 'waiting' | 'unverified'

function StepItem({
  label,
  note,
  state,
  stamp,
}: {
  label: string
  note: string
  state: StepState
  /** Observed instant; absent (never fabricated) for waiting/unverified steps. */
  stamp: string | undefined
}) {
  return (
    <li className="inspector-step" data-state={state}>
      <span className="inspector-step-node" aria-hidden="true" />
      <div className="inspector-step-top">
        <span className="inspector-step-label">{label}</span>
        {stamp === undefined ? (
          <span className="inspector-step-stamp inspector-fact-value--absent">—</span>
        ) : (
          <span className="inspector-step-stamp data">{clockStamp(stamp)}</span>
        )}
      </div>
      <p className="inspector-step-note">{note}</p>
    </li>
  )
}

/** The observed steps of the record, in the order this window observed them. */
function observedStep(step: TransportStep): { label: string; note: string; state: StepState } {
  switch (step.kind) {
    case 'queued':
      return {
        label: 'Queued in this window',
        note: 'The optimistic bubble rendered from client state; the command kept its correlation token.',
        state: 'done',
      }
    case 'sent':
      return {
        label: 'Written to the socket',
        note: 'The SEND_MESSAGE frame was handed to an open WebSocket by this window.',
        state: 'done',
      }
    case 'acknowledged':
      return {
        label: 'Persisted and acknowledged',
        note: 'MESSAGE_ACK returned the server-assigned id and creation time after the row was committed.',
        state: 'done',
      }
    case 'rejected':
      return {
        label: 'Rejected by the server',
        note: `ERROR · ${step.code ?? 'unknown code'}. A correlated rejection is final — the command is never re-sent automatically.`,
        state: 'failed',
      }
    case 'delivered':
      return {
        label: 'Delivered to this window',
        note: 'Arrived as a NEW_MESSAGE event on this session’s socket.',
        state: 'done',
      }
  }
}

/** One recorded protocol frame, exactly as it crossed the wire. */
function FrameView({ name, payload }: { name: string; payload: unknown }) {
  return (
    <div className="inspector-frame">
      <span className="inspector-frame-name data">{name}</span>
      <pre className="inspector-frame-json data">{JSON.stringify(payload, null, 2)}</pre>
    </div>
  )
}

/* ── Message record view (tasks 7.7–7.8) ──────────────────────────────── */

function MessageView({
  resolved,
  currentUser,
  ledger,
}: {
  resolved: ResolvedInspection & { kind: 'message' | 'pending' }
  currentUser: User
  ledger: TransportLedger
}) {
  const pending = resolved.kind === 'pending' ? resolved.pending : undefined
  const message = resolved.kind === 'message' ? resolved.message : undefined
  const content = message?.content ?? pending?.content ?? ''
  const clientMessageId = message?.clientMessageId ?? pending?.clientMessageId ?? ''
  const conversationId = message?.conversationId ?? pending?.conversationId ?? ''
  const senderId = message?.senderId ?? pending?.senderId ?? ''
  const own = senderId === currentUser.id
  const failed = pending?.status === 'failed'

  // Session-scoped transport record (task 7.2); `undefined` is the honest
  // "not observed in this session" state, stated as a sentence below.
  const record: TransportRecord | undefined =
    message !== undefined
      ? recordForMessage(ledger, message)
      : recordForPending(ledger, clientMessageId)

  const tokenCreationObserved =
    pending !== undefined || record?.steps.some((step) => step.kind === 'queued') === true
  const clientCreatedAt =
    pending?.createdAt ?? record?.steps.find((step) => step.kind === 'queued')?.observedAt

  return (
    <>
      <div className="inspector-subject">
        <span className="inspector-subject-who">{own ? 'You' : 'The other participant'}</span>
        <p className="inspector-subject-text">{content}</p>
      </div>

      <Section title="Transport">
        {record === undefined ? (
          <>
            {/* Task 7.8: a missing record is a STATEMENT, never a column of
                dashes. The persistent fields below still render — they come
                from the message itself, not the session. */}
            <p className="inspector-absent">Transport not observed in this session.</p>
            <p className="inspector-fact-note">
              Transport steps are recorded only while this window is open; a reload discards them.
              The fields below come from the message itself.
            </p>
          </>
        ) : (
          <>
            <ol className="inspector-step-list">
              {record.steps.map((step, index) => {
                const rendered = observedStep(step)
                return (
                  <StepItem
                    key={`${step.kind}-${index}`}
                    label={rendered.label}
                    note={rendered.note}
                    state={rendered.state}
                    stamp={step.observedAt}
                  />
                )
              })}
              {/* Forward-looking steps are stated WITHOUT timestamps (absent,
                  never fabricated) — they name what has not been observed. */}
              {pending !== undefined && !failed && (
                <>
                  <StepItem
                    label="Persisted and acknowledged"
                    note="Not observed yet. MESSAGE_ACK has not arrived in this session."
                    state="waiting"
                    stamp={undefined}
                  />
                  <StepItem
                    label="Fanned out to other sessions"
                    note="Fan-out is unverified: the server reports no result for this hop."
                    state="unverified"
                    stamp={undefined}
                  />
                </>
              )}
              {message !== undefined && own && (
                <StepItem
                  label="Fanned out to other sessions"
                  note="Fan-out is unverified: acknowledgement confirms persistence to this window only, never delivery to anyone else."
                  state="unverified"
                  stamp={undefined}
                />
              )}
            </ol>
            <p className="inspector-fact-note">
              Observations are session-scoped: a reload discards them.
            </p>
          </>
        )}
      </Section>

      <Section title="Identifiers">
        <div className="inspector-fact-list">
          <Fact
            label="Message id"
            value={message?.id}
            note={
              message === undefined
                ? 'Assigned by the server at persistence; it does not exist for a message awaiting acknowledgement.'
                : 'Server-assigned. Stable ordering tiebreaker, not chronological.'
            }
          />
          <Fact
            label="Client message id"
            value={clientMessageId}
            note={
              own
                ? tokenCreationObserved
                  ? 'Correlation/idempotency token minted in this window (crypto.randomUUID), re-sent unchanged on every retry. Never the message id.'
                  : 'Correlation/idempotency token persisted on the message. The window that minted it was not observed in this session. Never the message id.'
                : "The sender's correlation/idempotency token — carried on the message itself (task 7.1), never an identifier."
            }
          />
          <Fact label="Conversation id" value={conversationId} />
          <Fact
            label="Sender id"
            value={senderId}
            note={own ? 'Bound from the validated connection identity, never from the command payload.' : undefined}
          />
        </div>
      </Section>

      <Section title="Timing">
        <div className="inspector-fact-list">
          <Fact
            label="Server created at"
            value={message?.createdAt}
            note={
              message === undefined
                ? 'Server-authored at commit; it does not exist yet for this message.'
                : 'Server-authored at commit, stored UTC.'
            }
          />
          <Fact
            label="Client created at"
            value={own ? clientCreatedAt : undefined}
            note={
              own
                ? clientCreatedAt !== undefined
                  ? 'Local wall clock at submission. Never persisted — it exists only to order the optimistic bubble.'
                  : 'Not observed in this session.'
                : 'Only the sender’s own client has one; it is never transmitted.'
            }
          />
          <Fact
            label="Ordering key"
            value={ORDERING_KEY}
            note="The documented history ordering. There are no sequence numbers."
          />
        </div>
      </Section>

      <Section title="Payload">
        <div className="inspector-fact-list">
          <Fact
            label="Content length"
            value={`${[...content].length} / ${MAX_CONTENT_CODE_POINTS} code points`}
            note="Measured against the documented maximum."
          />
        </div>
        {record !== undefined &&
          record.frames.map((frame, index) => (
            <FrameView key={`${frame.name}-${index}`} name={frame.name} payload={frame.payload} />
          ))}
      </Section>

      {failed && pending !== undefined && (
        <Section title="Failure">
          <div className="inspector-fact-list">
            <Fact label="Code" value={pending.errorCode} />
            <Fact
              label="Reason"
              value={pending.errorReason}
              note="The server’s human-readable detail, retained for inspection. The product branches on the code, never on this text."
            />
          </div>
        </Section>
      )}

      {/* Task 7.8: acknowledgement semantics stated honestly. */}
      {message !== undefined && own && (
        <p className="inspector-caveat">
          Acknowledged means persisted by the server — nothing more. There is no delivery receipt,
          no read receipt, and no signal that any other window rendered this message.
        </p>
      )}
      {pending !== undefined && !failed && (
        <p className="inspector-caveat">
          Awaiting acknowledgement. The command is held in this window’s queue and re-sent unchanged
          — under its original correlation token — when the connection is restored.
        </p>
      )}
      {failed && (
        <p className="inspector-caveat">
          A correlated rejection is final: the message is never re-sent automatically. The Try again
          control is the only path back, and it re-submits under the original correlation token.
        </p>
      )}
    </>
  )
}

/* ── Connection / protocol view (no message selected) ─────────────────── */

function ConnectionView({
  currentUser,
  conversation,
  connectionState,
  getPendingCommandCount,
}: {
  currentUser: User
  conversation: Conversation
  connectionState: ConnectionState
  getPendingCommandCount: () => number
}) {
  const stateWord =
    connectionState === 'connected'
      ? 'Connected'
      : connectionState === 'connecting'
        ? 'Connecting'
        : connectionState === 'reconnecting'
          ? 'Reconnecting'
          : 'Disconnected'
  const pendingCount = getPendingCommandCount()

  return (
    <>
      <Section title="Connection">
        <div className="inspector-fact-list">
          <Fact label="Socket" value={stateWord} />
          <Fact
            label="Endpoint"
            value={`/ws?userId=${currentUser.id}`}
            note="Identity is bound at the handshake. An unknown id is rejected, and no session is opened."
          />
          <Fact
            label="Awaiting acknowledgement"
            value={`${pendingCount} command${pendingCount === 1 ? '' : 's'}`}
            note="Held in FIFO order and re-sent unchanged when the socket reopens."
          />
          <Fact
            label="Reconnect backoff"
            value="500 ms × 2ⁿ, capped at 10 s, ±30% jitter"
            note="No attempt cap."
          />
        </div>
      </Section>

      <Section title="Protocol">
        <div className="inspector-fact-list">
          <Fact label="Commands" value="SEND_MESSAGE" note="The only frame the client sends." />
          <Fact
            label="Events"
            value="MESSAGE_ACK · NEW_MESSAGE · ERROR · CONVERSATION_CREATED · PRESENCE"
          />
          <Fact
            label="History"
            value={`GET /api/conversations/${conversation.id}/messages`}
            note="Full history, no cursor. Merged into the cache by message id, server rows winning."
          />
          <Fact
            label="Max content"
            value={`${MAX_CONTENT_CODE_POINTS} code points`}
          />
          <Fact
            label="Presence"
            value="Scoped snapshot events"
            note="A full replacement set of online conversation partners: the socket’s first event, then again on every partner transition."
          />
        </div>
      </Section>

      <p className="inspector-caveat">
        MESSAGE_ACK is only sent after the row is committed. Acknowledgement confirms persistence —
        never delivery to anyone, and never that anyone has read the message.
      </p>
    </>
  )
}
