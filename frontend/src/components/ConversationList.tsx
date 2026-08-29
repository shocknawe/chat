import type { Conversation } from '../api'
import { initials } from '../initials'

/**
 * Conversation rail (task 5.2) — the signed-in user's conversations, fetched
 * by the caller via TanStack Query keyed on `['conversations', userId]` so a
 * switch of identity naturally re-scopes the query.
 *
 * Human labels: the backend `ConversationDto` exposes the full participant
 * list (`{ id, displayName }`), so the label is the OTHER participants'
 * display names joined. Only if a conversation somehow has no other
 * participant do we fall back to a shortened-id label — no invented fields.
 *
 * Every state is announced in words (DESIGN.md, Status-Is-Text): a text
 * status line + skeleton tiles while loading, `role="alert"` with retry on
 * failure, and an explicit empty note when the user has no conversations.
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
}

export function ConversationList({
  currentUserId,
  conversations,
  isPending,
  error,
  onRetry,
  selectedId,
  onSelect,
}: ConversationListProps) {
  return (
    <nav className="rail card" aria-labelledby="rail-title">
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
