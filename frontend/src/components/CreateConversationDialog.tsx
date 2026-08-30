import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { ApiError, type Conversation, type User } from '../api'
import { initials } from '../initials'

/**
 * The `+` creation dialog (tasks 3.5–3.8).
 *
 * Presentational scope: the caller (`SignedInShell`) owns the overlay state
 * machine, the candidate list (directory minus the caller and everyone they
 * already converse with, task 3.5), the create REST call, the rail patch, the
 * selection, the announcement, and the composer focus. This component owns
 * only the modal surface itself:
 *
 * - Focus confinement (task 3.6): on open, focus starts on the dialog title
 *   and moves to the first candidate once the directory resolves (it stays on
 *   the title when the directory is exhausted — there is no empty list to
 *   enter, per spec). Tab and Shift+Tab are intercepted and wrap within the
 *   panel, so keyboard focus cannot leave the dialog while it is open.
 *   Dismissal (Escape / scrim / Cancel, all routed to `onDismiss`) returns
 *   focus to the opening `+` control through the shell's overlay machinery.
 * - In-flight guard (task 3.8): while a request for a person is in flight,
 *   that person's affordance states the progress in words ("Starting…") and
 *   is disabled — a second submission for that person is impossible. Other
 *   people stay selectable: the guard is deliberately per person.
 * - Failure (task 3.7): the dialog STAYS open, the failure is stated in
 *   words, and nothing else changes — the caller only ever mutates the rail
 *   on a resolved promise, so a rejected request cannot add a conversation.
 *   Focus returns to the person's control so another attempt is one
 *   activation away.
 */

/** Turns a failed creation request into the words the dialog states (task 3.7). */
export function describeCreationFailure(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Something went wrong while starting the conversation. Please try again.'
  }
  if (error.status === 0) {
    // Transport failure already carries a sentence written for humans.
    return error.message
  }
  // The server's ProblemDetail reason is written for humans too; only fall
  // back to status-specific copy when the body carried no usable reason.
  if (error.message.trim() !== '') {
    return error.message
  }
  switch (error.status) {
    case 400:
      return 'The request was not valid.'
    case 401:
      return 'Your identity was not recognised — reload and pick a user, then try again.'
    case 404:
      return 'That person is not in the directory — pick someone else.'
    default:
      return 'Something went wrong. Please try again.'
  }
}

interface CreateConversationDialogProps {
  /**
   * Directory users the current user has NO conversation with, excluding the
   * current user themselves (task 3.5). `undefined` while the directory or
   * the conversation list is still loading.
   */
  candidates: User[] | undefined
  /** Performs the REST creation; resolves with the created-or-existing conversation, rejects with the failure reason. */
  onCreate: (participantId: string) => Promise<Conversation>
  /** Any dismissal (Escape, scrim, Cancel) — the shell returns focus to the `+` control. */
  onDismiss: () => void
}

export function CreateConversationDialog({
  candidates,
  onCreate,
  onDismiss,
}: CreateConversationDialogProps) {
  /** Everyone with a request in flight (task 3.8: the guard is per person). */
  const [inFlightIds, setInFlightIds] = useState<ReadonlySet<string>>(() => new Set())
  /** Words describing the latest failure, or null while none is showing. */
  const [failure, setFailure] = useState<string | null>(null)

  const dialogRef = useRef<HTMLElement | null>(null)
  const headingRef = useRef<HTMLHeadingElement | null>(null)

  // Initial focus (task 3.6): the dialog title, so focus is inside the dialog
  // from its very first render even while the directory is still loading.
  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  // First-candidate focus (task 3.6): exactly once, when the candidate list
  // first resolves. An exhausted directory focuses nothing extra — the title
  // keeps focus and the Cancel button is one Tab away.
  const candidateFocusAppliedRef = useRef(false)
  useEffect(() => {
    if (candidateFocusAppliedRef.current || candidates === undefined) return
    candidateFocusAppliedRef.current = true
    if (candidates.length === 0) return // exhausted: the title keeps focus
    dialogRef.current?.querySelector<HTMLButtonElement>('button[data-participant-id]')?.focus()
  }, [candidates])

  // Keyboard retry focus (task 3.7 "another attempt is possible"): after a
  // failure re-enables the person's control, put focus back on it.
  const [retryFocusId, setRetryFocusId] = useState<string | null>(null)
  useEffect(() => {
    if (retryFocusId === null) return
    setRetryFocusId(null)
    dialogRef.current
      ?.querySelector<HTMLButtonElement>(`button[data-participant-id="${retryFocusId}"]`)
      ?.focus()
  }, [retryFocusId])

  const handleChoose = async (participantId: string): Promise<void> => {
    if (inFlightIds.has(participantId)) return // task 3.8: no duplicate submission per person
    setInFlightIds((previous) => new Set(previous).add(participantId))
    setFailure(null)
    try {
      await onCreate(participantId)
      // Success: the caller closed this dialog (and moved focus to the
      // composer); this dialog is unmounted, so nothing further runs here.
    } catch (error) {
      setInFlightIds((previous) => {
        const next = new Set(previous)
        next.delete(participantId)
        return next
      })
      setFailure(describeCreationFailure(error))
      // Re-present the failed control to the keyboard once enabled again.
      setRetryFocusId(participantId)
    }
  }

  /** Task 3.6: trap Tab inside the panel — Tab and Shift+Tab both wrap. */
  const handleTrapKeyDown = (event: KeyboardEvent<HTMLElement>): void => {
    if (event.key !== 'Tab') return
    const dialog = dialogRef.current
    if (dialog === null) return
    const focusable = Array.from(
      dialog.querySelectorAll<HTMLElement>(
        'button:not([disabled]), [href], input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    )
    const active = document.activeElement
    const currentIndex = focusable.findIndex((element) => element === active)
    event.preventDefault()
    if (event.shiftKey) {
      const previous = focusable[currentIndex === -1 ? focusable.length - 1 : currentIndex - 1]
      ;(previous ?? focusable.at(-1))?.focus()
    } else {
      const next =
        focusable[currentIndex === -1 || currentIndex === focusable.length - 1 ? 0 : currentIndex + 1]
      ;(next ?? focusable[0])?.focus()
    }
  }

  return (
    <div className="dialog-backdrop">
      {/* Clickable dismissal, mirroring the rail's scrim (task 2.6). Focus is
          trapped inside the panel, so the scrim sits outside the tab order. */}
      <button
        type="button"
        className="dialog-scrim"
        aria-label="Close new conversation dialog"
        tabIndex={-1}
        onClick={onDismiss}
      />
      <section
        ref={dialogRef}
        className="create-conversation-dialog card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="create-conversation-title"
        aria-describedby="create-conversation-lead"
        onKeyDown={handleTrapKeyDown}
      >
        <h2 ref={headingRef} id="create-conversation-title" className="title dialog-title" tabIndex={-1}>
          New conversation
        </h2>
        <p id="create-conversation-lead" className="meta dialog-lead">
          Choose who you want to talk to.
        </p>

        {failure !== null && (
          <div role="alert" className="dialog-error">
            <p className="dialog-error-title">Couldn't start the conversation.</p>
            <p className="meta dialog-error-detail">{failure}</p>
          </div>
        )}

        {candidates === undefined && (
          <p role="status" className="meta dialog-status">
            Loading the directory…
          </p>
        )}

        {candidates !== undefined && candidates.length > 0 && (
          <ul className="dialog-candidates">
            {candidates.map((candidate) => {
              const isInFlight = inFlightIds.has(candidate.id)
              return (
                <li key={candidate.id}>
                  <button
                    type="button"
                    className="dialog-candidate"
                    data-participant-id={candidate.id}
                    /* Accessible name matches the one-shot: an action phrase
                       rather than the bare person, so the flattened text of
                       name + status can never garble it. */
                    aria-label={`Start a conversation with ${candidate.displayName}`}
                    aria-describedby={isInFlight ? `candidate-status-${candidate.id}` : undefined}
                    disabled={isInFlight}
                    onClick={() => void handleChoose(candidate.id)}
                  >
                    <span className="avatar" aria-hidden="true">
                      {initials(candidate.displayName)}
                    </span>
                    <span className="dialog-candidate-name">{candidate.displayName}</span>
                    {/* Task 3.8: progress stated in words (Status-Is-Text),
                        never a spinner alone; it is the button's description,
                        so assistive technology reads it with the name. */}
                    {isInFlight && (
                      <span className="meta dialog-candidate-status" id={`candidate-status-${candidate.id}`}>
                        Starting…
                      </span>
                    )}
                  </button>
                </li>
              )
            })}
          </ul>
        )}

        {/* Task 3.5: an exhausted directory is STATED, with no empty list. */}
        {candidates !== undefined && candidates.length === 0 && (
          <p className="dialog-empty">You already have a conversation open with everyone in the directory.</p>
        )}

        <button type="button" className="btn btn-secondary dialog-cancel" onClick={onDismiss}>
          Cancel
        </button>
      </section>
    </div>
  )
}