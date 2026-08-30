import { useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'

/**
 * Message length cap, mirroring the backend's authoritative
 * `MessagingLimits.MAX_CONTENT_LENGTH` (4000). Applied as the input's
 * `maxLength` attribute only: the backend remains the authority (it counts
 * Unicode code points; the attribute counts UTF-16 units, which is never
 * MORE permissive, so astral-plane text is simply capped a little earlier —
 * the server still re-validates and rejects over-length content).
 */
export const MAX_MESSAGE_LENGTH = 4000

/** Matches `.composer-input`'s `max-height` in index.css — the CSS cap is the
 * ultimate authority (this only avoids setting an inline height taller than
 * it needs to). */
const MAX_COMPOSER_HEIGHT_PX = 132

/**
 * Composer (OpenSpec task 6.3, re-skinned under task 2.4) — the send
 * affordance below the thread.
 *
 * Submission rules:
 * - Enter submits; Shift+Enter inserts a newline (standard chat semantics).
 * - Empty or whitespace-only drafts can never be submitted: the submit paths
 *   (Enter and the Send button) both no-op on a blank trim, and the button is
 *   additionally `disabled` so the affordance itself signals unavailability.
 * - Submitted content is BOUNDARY-TRIMMED (`String.prototype.trim` — edges
 *   only, interior spacing and newlines untouched) so what the pending bubble
 *   displays is exactly what the backend validates and persists.
 *
 * Auto-grow (task 2.4): the textarea starts at one line and grows with typed
 * content up to `--composer-input` `max-height`, then scrolls internally;
 * clearing the draft (send, or manual deletion) shrinks it back to one line.
 * Height is recalculated via direct DOM measurement (`scrollHeight`) rather
 * than guessed from character/line counts, so wrapped lines and pasted
 * multi-line text size correctly. `useLayoutEffect` (not `useEffect`) avoids
 * a visible one-frame jump between the old and new height.
 */
interface ComposerProps {
  /** Announces which conversation the message goes to (screen readers). */
  ariaLabel: string
  onSubmit: (content: string) => void
}

export function Composer({ ariaLabel, onSubmit }: ComposerProps) {
  const [draft, setDraft] = useState('')
  const canSend = draft.trim() !== ''
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)

  useLayoutEffect(() => {
    const node = textareaRef.current
    if (node === null) return
    // Reset before measuring: a taller previous height would otherwise be
    // included in `scrollHeight`, so the box could grow but never shrink.
    node.style.height = 'auto'
    node.style.height = `${Math.min(node.scrollHeight, MAX_COMPOSER_HEIGHT_PX)}px`
  }, [draft])

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
        ref={textareaRef}
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
