import { useCallback, useEffect, useRef, useState } from 'react'
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
import { ConnectionStatus } from './components/ConnectionStatus'
import { ConversationList, conversationLabel } from './components/ConversationList'
import { ThreadPane } from './components/ThreadPane'
import { UserSelectScreen } from './components/UserSelectScreen'
import { useAnnouncer } from './hooks/useAnnouncer'
import { useChatSocket } from './hooks/useChatSocket'
import { COMPACT_QUERY, useMediaQuery } from './hooks/useMediaQuery'
import { usePendingMessages } from './hooks/usePendingMessages'
import { useTheme } from './hooks/useTheme'
import { initials } from './initials'
import { upsertAuthoritativeMessage } from './messagesCache'
import { queryClient } from './queryClient'
import type { ConnectionState, ErrorEvent, MessageAckEvent, NewMessageEvent } from './realtime'

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
 *
 * Accessibility announcer (task 2.7): one polite live region lives here,
 * above the identity gate, because `SignedInShell` below is fully remounted
 * (via its `user.id` key) on every identity switch — a live region owned by
 * it would be torn down at exactly the moment it needs to announce the
 * switch that just happened.
 */
function App() {
  const [selectedUserId, setSelectedUserId] = useState<string | null>(() => readStoredUserId())
  const usersQuery = useQuery({ queryKey: ['users'], queryFn: fetchUsers })
  const { announcement, announce } = useAnnouncer()

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
    announce(`Signed in as ${user.displayName}`)
  }

  const handleSwitchUser = () => {
    clearStoredUserId()
    setSelectedUserId(null)
  }

  return (
    <>
      {currentUser === null ? (
        <UserSelectScreen
          users={users}
          isPending={usersQuery.isPending}
          error={usersQuery.error}
          onRetry={() => void usersQuery.refetch()}
          onSelect={handleSelect}
        />
      ) : (
        <SignedInShell key={currentUser.id} user={currentUser} onSwitchUser={handleSwitchUser} announce={announce} />
      )}
      {/* Task 2.7: conversation, identity, and delivery-status changes are
          announced here — see the components above for where `announce` is
          called for each. */}
      <div className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {announcement}
      </div>
    </>
  )
}

interface SignedInShellProps {
  user: User
  onSwitchUser: () => void
  announce: (message: string) => void
}

/** The one overlay the compact shell can present; a union so a later slice's drawer joins without restructuring the exclusivity mechanism. */
type OverlayId = 'rail'

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
function SignedInShell({ user, onSwitchUser, announce }: SignedInShellProps) {
  const { theme, toggleTheme } = useTheme()
  const isCompact = useMediaQuery(COMPACT_QUERY)

  // Task 2.6: exclusive overlay state. Only the rail exists in this slice,
  // but the shape (a single "currently open overlay id, or none" value) is
  // exactly what makes exclusivity trivial to extend — opening a second
  // overlay is just another `setOpenOverlay` call, which always replaces
  // whatever was open rather than stacking.
  const [openOverlay, setOpenOverlay] = useState<OverlayId | null>(null)
  const railToggleRef = useRef<HTMLButtonElement | null>(null)

  const closeOverlay = useCallback(() => {
    setOpenOverlay(null)
    railToggleRef.current?.focus()
  }, [])

  // Crossing above the compact threshold while the rail overlay is open must
  // not leave it stranded mid-transition-state: it is no longer an overlay
  // at all above the threshold, so the "open" flag is meaningless there.
  useEffect(() => {
    if (!isCompact && openOverlay !== null) {
      setOpenOverlay(null)
    }
  }, [isCompact, openOverlay])

  // Dismiss key (task 2.6).
  useEffect(() => {
    if (openOverlay === null) return
    const handleKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        closeOverlay()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [openOverlay, closeOverlay])

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
      // Task 2.7: delivery-status change, announced in words.
      announce('Message sent')
    },
    [removePending, user.id, announce],
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
        // Task 2.7: delivery-status change, announced in words.
        announce('Message failed to send')
        return
      }
      console.warn(`[chat] uncorrelated realtime error ${event.code}: ${event.reason}`)
    },
    [markPendingFailed, announce],
  )

  /**
   * Task 6.5 realtime receipt. ONE keyed upsert covers both cases in the
   * requirement, with zero refresh and zero selection change:
   * - Active conversation: the open messages query re-renders instantly.
   * - Inactive conversation: the per-conversation cache entry is keyed
   *   `['messages', userId, conversationId]` and lives independently of what
   *   is on screen, so the message seeds that conversation's history for a
   *   later visit without touching the active conversation.
   *
   * Exactly-once across delivery paths: the backend fans NEW_MESSAGE out to
   * every participant connection, including the SENDER'S own other sessions —
   * and this session may already hold the same message via an ACK (6.4) or a
   * history fetch. `upsertAuthoritativeMessage` is keyed on the server id and
   * replaces in place, so a late or duplicate NEW_MESSAGE with an id already
   * cached can never produce a second rendered bubble.
   *
   * No conversations-rail invalidation — deliberately: the `Conversation`
   * DTO is `{ id, participants }` only, and the rail renders nothing derived
   * from messages (no last-message preview, no unread count). Invalidating
   * `['conversations', user.id]` here would refetch byte-identical data and
   * steal nothing; if the DTO ever gains message-derived fields, this is the
   * place to add the invalidation.
   */
  const handleNewMessage = useCallback(
    (event: NewMessageEvent) => {
      upsertAuthoritativeMessage(user.id, event.message)
    },
    [user.id],
  )

  // Realtime connection (task 6.2): the socket lifecycle is bound to this
  // identity — created once `currentUser` is established (this shell only
  // renders then) and terminated on user switch/unmount. `sendMessage` is
  // consumed by the submit path below, the ack/error handlers are 6.4, the
  // new-message handler is 6.5 (all wired here), and `connectionState` feeds
  // the task-6.6 availability indicator and the reconnect history refresh.
  const { sendMessage, connectionState } = useChatSocket(user.id, {
    onMessageAck: handleMessageAck,
    onNewMessage: handleNewMessage,
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

  /**
   * Task 6.6 reconnect recovery. The window between an unexpected disconnect
   * and the reconnect can contain messages whose NEW_MESSAGE events never
   * reached this window (server-side: "Message recoverable after missed
   * delivery"). Recovery: on every transition INTO `connected` AFTER this
   * shell instance's first connection, invalidate the active conversation's
   * history so TanStack Query refetches it from the backend.
   *
   * Initial-connect exclusion: `hasConnectedRef` distinguishes the two cases —
   * the FIRST `connected` of a shell instance must NOT refetch, because the
   * messages query already owns initial history load (mount-time fetch).
   * `prevConnectionStateRef` guards the effect's dependencies: re-running the
   * effect for a conversation switch while already `connected` is not a
   * reconnect and must not refetch either.
   *
   * StrictMode/user-switch safety: the shell mounts fresh per identity (keyed
   * on `user.id`), so both refs reset; the StrictMode double-socket still
   * reports exactly one connected transition (socket A is terminated before
   * it can open), which the ref correctly classifies as the initial connect.
   *
   * Merge safety: this refetch commits through the task-6.7 history merge
   * (`fetchMergedHistory` → `mergeHistoryWithCache`), which unions the
   * response with any realtime upserts that landed while the request was in
   * flight — a newer WebSocket message can be neither erased nor duplicated
   * by the commit. This invalidation is the spec's recovery mechanism; the
   * merge is what makes it safe.
   */
  const hasConnectedRef = useRef(false)
  const prevConnectionStateRef = useRef<ConnectionState>(connectionState)
  useEffect(() => {
    const prevState = prevConnectionStateRef.current
    prevConnectionStateRef.current = connectionState

    if (connectionState !== 'connected') return
    const isReconnect = hasConnectedRef.current && prevState !== 'connected'
    hasConnectedRef.current = true

    if (isReconnect && selectedConversationId !== null) {
      void queryClient.invalidateQueries({
        queryKey: ['messages', user.id, selectedConversationId],
      })
    }
  }, [connectionState, user.id, selectedConversationId])

  const handleSelectConversation = (conversationId: string) => {
    storeConversationId(user.id, conversationId)
    setSelectedConversationId(conversationId)
    // Task 2.6: selecting a conversation is one of the overlay's dismissal
    // mechanisms, and it must go through the same focus-returning close as
    // Escape and the scrim — the compact rail leaves the DOM inert as it
    // closes, so dismissing via selection has to put focus back on a live
    // control (the toggle) instead of dropping it onto <body>.
    closeOverlay()
    // Task 2.7: conversation-selection change, announced in words.
    const conversation = conversationsQuery.data?.find((c) => c.id === conversationId)
    if (conversation !== undefined) {
      announce(`Conversation with ${conversationLabel(conversation, user.id)} selected`)
    }
  }

  const isRailOpen = openOverlay === 'rail'

  return (
    <div className="shell">
      {/* Task 2.7: first focusable element on the signed-in shell. */}
      <a href="#main-content" className="skip-link">
        Skip to conversation
      </a>
      <header className="app-header" aria-label="Chat">
        <div className="app-header-brand">
          {isCompact && (
            <button
              ref={railToggleRef}
              type="button"
              className="icon-button mobile-rail-toggle"
              aria-label={isRailOpen ? 'Close conversations' : 'Open conversations'}
              aria-expanded={isRailOpen}
              aria-controls="conversation-rail"
              onClick={() => setOpenOverlay(isRailOpen ? null : 'rail')}
            >
              <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
                <path
                  d="M4 7h16M4 12h16M4 17h16"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                />
              </svg>
            </button>
          )}
          <span className="brand-mark brand-mark--sm" aria-hidden="true" />
          <span className="title">Chat</span>
        </div>
        <div className="app-header-user">
          <ConnectionStatus state={connectionState} />
          <button
            type="button"
            className="icon-button theme-toggle"
            onClick={toggleTheme}
            aria-pressed={theme === 'dark'}
            aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
            title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          >
            {theme === 'dark' ? (
              <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
                <circle cx="12" cy="12" r="3.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
                <path
                  d="M12 2v2.5M12 19.5V22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M2 12h2.5M19.5 12H22M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                />
              </svg>
            ) : (
              <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
                <path
                  d="M20 15.5A8.5 8.5 0 0 1 8.5 4 8.5 8.5 0 1 0 20 15.5Z"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            )}
          </button>
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
      <main id="main-content" className="shell-body" tabIndex={-1}>
        <ConversationList
          currentUserId={user.id}
          conversations={conversationsQuery.data}
          isPending={conversationsQuery.isPending}
          error={conversationsQuery.error}
          onRetry={() => void conversationsQuery.refetch()}
          selectedId={selectedConversationId}
          onSelect={handleSelectConversation}
          isCompact={isCompact}
          isOverlayOpen={isRailOpen}
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
      {/* Task 2.6: the scrim is one of the overlay's dismissal mechanisms and
          doubles as the visual cue that the rail is modal while open. */}
      {isRailOpen && (
        <button type="button" className="scrim" aria-label="Close conversations" onClick={closeOverlay} />
      )}
    </div>
  )
}

export default App
