import { useEffect, useRef } from 'react'
import type { Conversation } from '../api'
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
  selectedId: string | null
  onSelect: (conversationId: string) => void
  /** True while the viewport is below the compact breakpoint (task 2.6). */
  isCompact?: boolean
  /** True while the rail overlay is the currently open overlay. Meaningless when `isCompact` is false. */
  isOverlayOpen?: boolean
}

export function ConversationList({
  currentUserId,
  conversations,
  isPending,
  error,
  onRetry,
  selectedId,
  onSelect,
  isCompact = false,
  isOverlayOpen = false,
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
      <h2 id="rail-title" className="title rail-title">
        Conversations
      </h2>

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
                  <span className="conversation-name">{label}</span>
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </nav>
  )
}
