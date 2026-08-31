import {
  useLayoutEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
  type MutableRefObject,
} from 'react'

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
 * Layout: the textarea and send button share one bordered pill
 * (`.composer-box`) rather than sitting as two adjacent controls, with a
 * small right-aligned keyboard hint (`.composer-hint`) underneath — matching
 * the reference chat layout's composer.
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
  /**
   * Slice 5 (task 6.3): true while realtime messaging is unavailable. The
   * composer REMAINS USABLE (spec) but its copy changes to say what happens
   * to a draft now — it waits for the connection instead of being lost.
   */
  waitingForConnection?: boolean
  onSubmit: (content: string) => void
  /**
   * Optional external handle to the textarea for programmatic focus —
   * task 3.7 moves focus here after a conversation is created from the
   * `+` dialog. Merged with the internal auto-grow ref below. Mutable: this
   * component writes the textarea node into it on mount.
   */
  focusRef?: MutableRefObject<HTMLTextAreaElement | null>
}

/** Placeholder while the connection is down (waiting copy, per spec). */
const WAITING_PLACEHOLDER = 'You can keep typing. Messages wait for the connection.'

const DEFAULT_PLACEHOLDER = 'Write a message…'

/** Hint line under the composer, mirroring the placeholder's connection-state split. */
const DEFAULT_HINT = 'Enter sends. Shift and Enter adds a new line.'

export function Composer({
  ariaLabel,
  waitingForConnection = false,
  onSubmit,
  focusRef,
}: ComposerProps) {
  const [draft, setDraft] = useState('')
  const canSend = draft.trim() !== ''
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)

  const setTextareaRef = (node: HTMLTextAreaElement | null): void => {
    textareaRef.current = node
    if (focusRef !== undefined) {
      focusRef.current = node
    }
  }

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
    <div className="composer">
      <form className="composer-box" onSubmit={handleFormSubmit}>
        <textarea
          ref={setTextareaRef}
          className="composer-input"
          aria-label={ariaLabel}
          placeholder={waitingForConnection ? WAITING_PLACEHOLDER : DEFAULT_PLACEHOLDER}
          rows={1}
          maxLength={MAX_MESSAGE_LENGTH}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={handleKeyDown}
        />
        <button
          type="submit"
          className="btn composer-send"
          disabled={!canSend}
          aria-label="Send"
          title="Send"
        >
          <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M21 3 10 14M21 3l-7 18-4-7-7-4 18-7Z"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>
      </form>
      <p className="composer-hint">{DEFAULT_HINT}</p>
    </div>
  )
}
