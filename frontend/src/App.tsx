import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchConversations, fetchUsers, type User } from './api'
import {
  clearStoredConversationId,
  clearStoredUserId,
  readStoredConversationId,
  readStoredUserId,
  storeConversationId,
  storeUserId,
} from './identity'
import { ConversationList } from './components/ConversationList'
import { ThreadPane } from './components/ThreadPane'
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
 * shows again. Once established, the signed-in shell mounts the conversation
 * rail (task 5.2) and, later, the message thread (tasks 5.3–5.4).
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

  return <SignedInShell key={currentUser.id} user={currentUser} onSwitchUser={handleSwitchUser} />
}

interface SignedInShellProps {
  user: User
  onSwitchUser: () => void
}

/**
 * Chat app frame rendered once the current user is established: a
 * conversations rail on the left and a thread pane on the right.
 *
 * Conversation scoping (task 5.2): the query key includes the user's id, so
 * "Switch user" naturally re-fetches for the new identity. The shell is also
 * keyed on `user.id`, so a switch remounts it and the selected conversation
 * resets — an id from the previous user's list can never leak across.
 *
 * The thread pane (task 5.3) loads and renders history on selection.
 *
 * Reload restore (task 5.4): the selected conversation id is persisted in
 * sessionStorage alongside the identity, so a page reload restores both.
 * The restored id is read once at mount and *validated* against the fetched
 * conversations for this user — a stale id (conversation deleted, user
 * removed from it) is dropped from both state and storage, leaving the
 * thread pane empty. Once a valid id resolves, the messages query mounts
 * and re-fetches the persisted history from the server: a page load is a
 * fresh page with an empty query cache, and TanStack Query's default
 * staleTime (0) refetches on mount regardless, so history is never served
 * stale across reloads.
 */
function SignedInShell({ user, onSwitchUser }: SignedInShellProps) {
  const conversationsQuery = useQuery({
    queryKey: ['conversations', user.id],
    queryFn: () => fetchConversations(user.id),
  })
  const [selectedConversationId, setSelectedConversationId] = useState<string | null>(() =>
    readStoredConversationId(user.id),
  )

  const selectedConversation =
    conversationsQuery.data?.find((c) => c.id === selectedConversationId) ?? null

  // Restored-selection validation: once the conversations list arrives, a
  // stored id that no longer matches one of this user's conversations is
  // dropped (stale ids must never pin the rail highlight or the thread).
  // Render already derives `selectedConversation === null` for a stale id;
  // this syncs state and external storage to match.
  useEffect(() => {
    if (
      conversationsQuery.data !== undefined &&
      selectedConversationId !== null &&
      !conversationsQuery.data.some((c) => c.id === selectedConversationId)
    ) {
      clearStoredConversationId(user.id)
      setSelectedConversationId(null)
    }
  }, [conversationsQuery.data, selectedConversationId, user.id])

  const handleSelectConversation = (conversationId: string) => {
    storeConversationId(user.id, conversationId)
    setSelectedConversationId(conversationId)
  }

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
        <ConversationList
          currentUserId={user.id}
          conversations={conversationsQuery.data}
          isPending={conversationsQuery.isPending}
          error={conversationsQuery.error}
          onRetry={() => void conversationsQuery.refetch()}
          selectedId={selectedConversationId}
          onSelect={handleSelectConversation}
        />
        <ThreadPane currentUser={user} conversation={selectedConversation} />
      </main>
    </div>
  )
}

export default App
