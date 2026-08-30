import { useCallback, useEffect, useState } from 'react'
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
import { useChatSocket } from './hooks/useChatSocket'
import { usePendingMessages } from './hooks/usePendingMessages'
import { initials } from './initials'
import { upsertAuthoritativeMessage } from './messagesCache'
import type { ErrorEvent, MessageAckEvent } from './realtime'

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
  // Pending outbound items (task 6.3): optimistic messages with no server id
  // yet, held OUTSIDE the TanStack cache. State is lifted here — the common
  // ancestor of ThreadPane (renders it) and the socket handlers (6.4 mutates
  // it via `removePending`/`markPendingFailed`) — and is keyed to this
  // identity by the shell's `key`, so a user switch resets it automatically.
  const { pendingMessages, addPending, removePending, markPendingFailed } = usePendingMessages()

  /**
   * Task 6.4 pending→authoritative reconciliation. The backend ack always
   * carries the command's `clientMessageId` alongside the authoritative
   * message (backend `MessageAck` has a non-nullable `clientMessageId: UUID`),
   * so correlation is direct.
   *
   * Exactly-once invariant: after an ack, the message appears ONCE — the
   * pending bubble is removed AND the authoritative message enters the cache
   * through the single keyed upsert (idempotent on server id), so neither a
   * duplicate render nor a lost message is possible even if the same message
   * later re-arrives via NEW_MESSAGE (6.5) or history (6.7).
   */
  const handleMessageAck = useCallback(
    (event: MessageAckEvent) => {
      removePending(event.clientMessageId)
      upsertAuthoritativeMessage(user.id, event.message)
    },
    [removePending, user.id],
  )

  /**
   * Task 6.4 failure path: a correlated error (`clientMessageId` present)
   * flips the matching pending item to `failed` — the bubble stays rendered
   * with the "Failed to send" status, is never retried, and is never removed.
   * If no pending item matches (e.g. the ack already won the race),
   * `markPendingFailed` is a harmless no-op. Uncorrelated errors (unparseable
   * frames, etc.) carry no `clientMessageId` and are logged visibly.
   */
  const handleRealtimeError = useCallback(
    (event: ErrorEvent) => {
      if (event.clientMessageId !== undefined) {
        markPendingFailed(event.clientMessageId)
        return
      }
      console.warn(`[chat] uncorrelated realtime error ${event.code}: ${event.reason}`)
    },
    [markPendingFailed],
  )

  // Realtime connection (task 6.2): the socket lifecycle is bound to this
  // identity — created once `currentUser` is established (this shell only
  // renders then) and terminated on user switch/unmount. `sendMessage` is
  // consumed by the submit path below, the ack/error handlers are 6.4 (wired
  // here), `onNewMessage` remains unwired until 6.5, and `connectionState`
  // feeds the availability indicator in 6.6.
  const { sendMessage } = useChatSocket(user.id, {
    onMessageAck: handleMessageAck,
    onRealtimeError: handleRealtimeError,
  })

  /**
   * Task 6.3 submit path. `content` arrives already boundary-trimmed and
   * guaranteed non-blank by the composer (it mirrors what the backend will
   * validate, which rejects empty/whitespace). The optimistic pending item is
   * appended FIRST so the bubble appears even when the socket is unavailable —
   * the socket queues the command internally (6.1) and will flush it on
   * reconnect under the same `clientMessageId`.
   */
  const handleSendMessage = (conversationId: string, content: string): void => {
    const clientMessageId = crypto.randomUUID()
    addPending({
      clientMessageId,
      conversationId,
      senderId: user.id,
      content,
      createdAt: new Date().toISOString(),
      status: 'pending',
    })
    sendMessage({ clientMessageId, conversationId, content })
  }

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
        <ThreadPane
          currentUser={user}
          conversation={selectedConversation}
          pendingMessages={pendingMessages}
          onSendMessage={(content) => {
            if (selectedConversation !== null) {
              handleSendMessage(selectedConversation.id, content)
            }
          }}
        />
      </main>
    </div>
  )
}

export default App
