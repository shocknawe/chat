import { useState, type FormEvent, type KeyboardEvent } from 'react'

/**
 * Message length cap, mirroring the backend's authoritative
 * `MessagingLimits.MAX_CONTENT_LENGTH` (4000). Applied as the input's
 * `maxLength` attribute only: the backend remains the authority (it counts
 * Unicode code points; the attribute counts UTF-16 units, which is never
 * MORE permissive, so astral-plane text is simply capped a little earlier —
 * the server still re-validates and rejects over-length content).
 */
export const MAX_MESSAGE_LENGTH = 4000

/**
 * Composer (OpenSpec task 6.3) — the send affordance below the thread.
 *
 * Submission rules:
 * - Enter submits; Shift+Enter inserts a newline (standard chat semantics).
 * - Empty or whitespace-only drafts can never be submitted: the submit paths
 *   (Enter and the Send button) both no-op on a blank trim, and the button is
 *   additionally `disabled` so the affordance itself signals unavailability.
 * - Submitted content is BOUNDARY-TRIMMED (`String.prototype.trim` — edges
 *   only, interior spacing and newlines untouched) so what the pending bubble
 *   displays is exactly what the backend validates and persists.
 */
interface ComposerProps {
  /** Announces which conversation the message goes to (screen readers). */
  ariaLabel: string
  onSubmit: (content: string) => void
}

export function Composer({ ariaLabel, onSubmit }: ComposerProps) {
  const [draft, setDraft] = useState('')
  const canSend = draft.trim() !== ''

  const submit = (): void => {
    const content = draft.trim()
    if (content === '') return
    onSubmit(content)
    setDraft('')
  }

  const handleFormSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault()
    submit()
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>): void => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      submit()
    }
  }

  return (
    <form className="composer" onSubmit={handleFormSubmit}>
      <textarea
        className="composer-input"
        aria-label={ariaLabel}
        placeholder="Write a message…"
        rows={1}
        maxLength={MAX_MESSAGE_LENGTH}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={handleKeyDown}
      />
      <button type="submit" className="btn composer-send" disabled={!canSend}>
        Send
      </button>
    </form>
  )
}
