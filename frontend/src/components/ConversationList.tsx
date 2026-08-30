import { useEffect, useRef, type RefObject } from 'react'
import type { Conversation } from '../api'
import { conversationPreview } from '../conversationPreview'
import type { PendingMessage } from '../hooks/usePendingMessages'
import { initials } from '../initials'

/**
 * Conversation rail (task 5.2) — the signed-in user's conversations, fetched
 * by the caller via TanStack Query keyed on `['conversations', userId]` so a
 * switch of identity naturally re-scopes the query.
 *
 * Human labels: the backend `ConversationDto` exposes the full participant
 * list (`{ id, displayName }`), so the label is the OTHER participants' names
 * joined. Only if a conversation somehow has no other participant do we fall
 * back to a shortened-id label — no invented fields.
 *
 * Rail preview (tasks 4.2–4.3): under the label the row summarises the latest
 * activity — the conversation's server-provided `lastMessage` content
 * (truncated by CSS), "Sending…"/"Failed to send" while the current user's
 * newest message there is awaiting acknowledgement/rejected (the same status
 * words the thread uses), or "No messages yet" for an empty history. The
 * preview is marked `aria-hidden`: the row's accessible name stays the
 * conversation label so identity-first navigation ("Bob") is not buried under
 * a message-length name — the full content is one activation away in the
 * thread, which states the empty-history case itself.
 *
 * Every state is announced in words (DESIGN.md, Status-Is-Text): a text
 * status line + skeleton tiles while loading, `role="alert"` with retry on
 * failure, and an explicit empty note when the user has no conversations.
 *
 * Compact-viewport overlay (task 2.6): below the compact breakpoint the rail
 * is presented as a slide-over rather than a fixed column. `App.tsx` owns
 * whether it is currently open (part of the app-wide exclusive-overlay
 * state) and whether the viewport is compact at all; this component just
 * reflects that as a class for the CSS transform and, while compact and
 * closed, marks itself `inert` so its buttons are neither focusable nor
 * exposed to assistive technology while off-screen.
 */

export function conversationLabel(conversation: Conversation, currentUserId: string): string {
  const others = conversation.participants.filter((p) => p.id !== currentUserId)
  if (others.length === 0) {
    return `Conversation ${conversation.id.slice(0, 8)}…`
  }
  return others.map((p) => p.displayName).join(', ')
}

interface ConversationListProps {
  currentUserId: string
  conversations: Conversation[] | undefined
  isPending: boolean
  error: Error | null
  onRetry: () => void
  /**
   * The current user's optimistic outbound items (tasks 4.3, 6.3): the slice
   * belonging to each conversation drives that row's pending/failed preview
   * override. Same store the thread pane renders its bubbles from.
   */
  pendingMessages: PendingMessage[]
  selectedId: string | null
  onSelect: (conversationId: string) => void
  /** True while the viewport is below the compact breakpoint (task 2.6). */
  isCompact?: boolean
  /** True while the rail overlay is the currently open overlay. Meaningless when `isCompact` is false. */
  isOverlayOpen?: boolean
  /**
   * Task 3.5: opens the new-conversation dialog. Control lives in the rail's
   * heading per spec; rendered (and the dialog's focus-return target) only
   * when provided.
   */
  onNewConversation?: () => void
  /** Handle to the `+` control for the shell's focus return on dialog dismissal (task 3.6). */
  newConversationButtonRef?: RefObject<HTMLButtonElement>
  /** True while the creation dialog is the currently open overlay. */
  isNewConversationOpen?: boolean
}

export function ConversationList({
  currentUserId,
  conversations,
  isPending,
  error,
  onRetry,
  pendingMessages,
  selectedId,
  onSelect,
  isCompact = false,
  isOverlayOpen = false,
  onNewConversation,
  newConversationButtonRef,
  isNewConversationOpen = false,
}: ConversationListProps) {
  const navRef = useRef<HTMLElement | null>(null)
  const isOffCanvas = isCompact && !isOverlayOpen

  // `inert` is a DOM property, not a JSX attribute this TS/React version
  // types — applied imperatively so a closed off-canvas rail can never
  // receive keyboard focus or be read by assistive technology, even though
  // it remains present (translated out of the viewport) for the slide-in
  // transition.
  useEffect(() => {
    const node = navRef.current
    if (node !== null) {
      node.inert = isOffCanvas
    }
  }, [isOffCanvas])

  const className = ['rail', 'card', isCompact && isOverlayOpen ? 'rail--open' : ''].filter(Boolean).join(' ')

  return (
    <nav ref={navRef} id="conversation-rail" className={className} aria-labelledby="rail-title">
      <div className="rail-heading">
        <h2 id="rail-title" className="title rail-title">
          Conversations
        </h2>
        {/* Task 3.5: the creation control lives in the rail heading. The
            shell owns ref + `aria-expanded` so dismissal of the dialog it
            opens can return focus here (task 3.6).
        */}
        {onNewConversation !== undefined && (
          <button
            ref={newConversationButtonRef}
            type="button"
            className="icon-button rail-new"
            aria-label="New conversation"
            title="New conversation"
            aria-haspopup="dialog"
            aria-expanded={isNewConversationOpen}
            onClick={onNewConversation}
          >
            <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 5v14M5 12h14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
          </button>
        )}
      </div>

      {isPending && (
        <div className="rail-loading">
          <p role="status" className="meta rail-status">
            Loading your conversations…
          </p>
          <div className="conversation-list" aria-hidden="true">
            <div className="conversation-skeleton" />
            <div className="conversation-skeleton" />
          </div>
        </div>
      )}

      {error !== null && (
        <div className="rail-error" role="alert">
          <p className="rail-error-title">We couldn't load your conversations.</p>
          <p className="meta rail-error-detail">{error.message}</p>
          <button type="button" className="btn btn-secondary rail-retry" onClick={onRetry}>
            Try again
          </button>
        </div>
      )}

      {conversations !== undefined && conversations.length === 0 && (
        <p className="meta rail-status">No conversations yet — nothing to show here.</p>
      )}

      {conversations !== undefined && conversations.length > 0 && (
        <ul className="conversation-list">
          {conversations.map((conversation) => {
            const label = conversationLabel(conversation, currentUserId)
            const isActive = conversation.id === selectedId
            return (
              <li key={conversation.id}>
                <button
                  type="button"
                  className={isActive ? 'conversation-tile conversation-tile--active' : 'conversation-tile'}
                  aria-current={isActive ? 'true' : undefined}
                  onClick={() => onSelect(conversation.id)}
                >
                  <span className="avatar" aria-hidden="true">
                    {initials(label)}
                  </span>
                  <span className="conversation-copy">
                    <span className="conversation-name">{label}</span>
                    {/* Task 4.2/4.3: server preview, or the pending/failed
                        override — see the module header for the wording rules.
                        Truncated by CSS, never by code: the full content lives
                        in the thread. */}
                    <span className="conversation-preview" aria-hidden="true">
                      {conversationPreview(conversation, pendingMessages)}
                    </span>
                  </span>
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </nav>
  )
}
