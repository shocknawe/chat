import type { User } from '../api'
import { initials } from '../initials'

/**
 * Identity-selection gate — the first surface of the product, shown before
 * any conversation UI (task 5.1). One centered glass card over the ambient
 * field: brand mark, a display greeting, and the configured users as
 * identity tiles with gradient initial avatars. Loading, error, and empty
 * states are all announced in words (DESIGN.md, the Status-Is-Text rule).
 */

interface UserSelectScreenProps {
  users: User[] | undefined
  isPending: boolean
  error: Error | null
  onRetry: () => void
  onSelect: (user: User) => void
}

function greeting(): string {
  const hour = new Date().getHours()
  if (hour < 5) return 'Still up?'
  if (hour < 12) return 'Good morning.'
  if (hour < 18) return 'Good afternoon.'
  return 'Good evening.'
}

export function UserSelectScreen({ users, isPending, error, onRetry, onSelect }: UserSelectScreenProps) {
  return (
    <main className="gate">
      <section className="card gate-card" aria-labelledby="gate-title">
        <span className="brand-mark" aria-hidden="true" />
        <h1 id="gate-title" className="display gate-greeting">
          {greeting()}
        </h1>
        <p className="gate-subcopy">Pick who you are in this window — every window keeps its own identity.</p>

        {isPending && (
          <div className="identity-loading">
            <p role="status" className="meta gate-status">
              Finding everyone…
            </p>
            <div className="identity-list" aria-hidden="true">
              <div className="identity-skeleton" />
              <div className="identity-skeleton" />
            </div>
          </div>
        )}

        {error !== null && (
          <div className="gate-error" role="alert">
            <p className="gate-error-title">We couldn't load the user list.</p>
            <p className="meta gate-error-detail">{error.message}</p>
            <button type="button" className="btn btn-secondary" onClick={onRetry}>
              Try again
            </button>
          </div>
        )}

        {users !== undefined && users.length === 0 && (
          <p className="meta gate-status">No users are configured yet.</p>
        )}

        {users !== undefined && users.length > 0 && (
          <ul className="identity-list">
            {users.map((user) => (
              <li key={user.id}>
                <button type="button" className="identity-tile" onClick={() => onSelect(user)}>
                  <span className="avatar" aria-hidden="true">
                    {initials(user.displayName)}
                  </span>
                  <span className="identity-name">{user.displayName}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  )
}
