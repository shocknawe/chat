import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchUsers, type User } from './api'
import { clearStoredUserId, readStoredUserId, storeUserId } from './identity'
import { UserSelectScreen } from './components/UserSelectScreen'
import { initials } from './initials'

/**
 * Root component: establishes the window-scoped current user before any
 * conversation UI exists (task 5.1).
 *
 * Identity restore flow: the selection is read from `sessionStorage` once at
 * mount (per-window, survives reload, never shared across windows), then
 * *validated* against the fetched user directory — a stored id that no
 * longer matches a configured user is discarded and the selection screen
 * shows again. Conversation UI lands in task 5.2; until then an established
 * identity renders a minimal shell with a switch-user affordance.
 */
function App() {
  const [selectedUserId, setSelectedUserId] = useState<string | null>(() => readStoredUserId())
  const usersQuery = useQuery({ queryKey: ['users'], queryFn: fetchUsers })

  const users = usersQuery.data
  const currentUser = users?.find((user) => user.id === selectedUserId) ?? null

  // Stored identity restore is only valid while it still matches the
  // directory: once the user list arrives, drop a stale stored id from
  // sessionStorage. Render derives `currentUser === null` for a stale id on
  // its own, so no state update is needed here — this syncs external
  // storage only.
  useEffect(() => {
    if (users !== undefined && selectedUserId !== null && !users.some((u) => u.id === selectedUserId)) {
      clearStoredUserId()
    }
  }, [users, selectedUserId])

  const handleSelect = (user: User) => {
    storeUserId(user.id)
    setSelectedUserId(user.id)
  }

  const handleSwitchUser = () => {
    clearStoredUserId()
    setSelectedUserId(null)
  }

  if (currentUser === null) {
    return (
      <UserSelectScreen
        users={users}
        isPending={usersQuery.isPending}
        error={usersQuery.error}
        onRetry={() => void usersQuery.refetch()}
        onSelect={handleSelect}
      />
    )
  }

  return <SignedInShell user={currentUser} onSwitchUser={handleSwitchUser} />
}

interface SignedInShellProps {
  user: User
  onSwitchUser: () => void
}

/**
 * Minimal placeholder shell rendered once the current user is established.
 * The conversation rail and thread (tasks 5.2–5.4) mount inside this frame.
 */
function SignedInShell({ user, onSwitchUser }: SignedInShellProps) {
  return (
    <div className="shell">
      <header className="app-header">
        <div className="app-header-brand">
          <span className="brand-mark brand-mark--sm" aria-hidden="true" />
          <span className="title">Chat</span>
        </div>
        <div className="app-header-user">
          <span className="chip">
            <span className="avatar avatar--sm" aria-hidden="true">
              {initials(user.displayName)}
            </span>
            {user.displayName}
          </span>
          <button type="button" className="btn btn-ghost" onClick={onSwitchUser}>
            Switch user
          </button>
        </div>
      </header>
      <main className="shell-body">
        <section className="card shell-empty" aria-labelledby="shell-empty-title">
          <h1 id="shell-empty-title" className="display">
            You're in, {user.displayName}.
          </h1>
          <p className="meta">Conversations will appear here.</p>
        </section>
      </main>
    </div>
  )
}

export default App
