import type { ConnectionState } from '../realtime'

/**
 * Connection availability indicator (OpenSpec task 6.2, slice 5 of
 * `add-conversation-creation-presence-inspector`), rendered in the app header
 * beside the identity chip.
 *
 * Per DESIGN.md ("Connection state" plus the Status-Is-Text rule): the state
 * is a WORD first, and color is the second signal, never the only one.
 *
 * Slice 5's task-6.2 contract replaces the earlier "healthy state needs no
 * banner" rationale (which rendered NOTHING while `connected`): a state that
 * vanishes when things are healthy is a colour-only signal by another name —
 * the reader can no longer tell "no news" from "not wired up". The pill is
 * therefore ALWAYS visible while signed in, in every state, word plus dot:
 *
 * - `connected`     → the ok-tinted pill, word "Connected". Permanent: the
 *                     spec requires the state at ALL times, including this
 *                     one ("connection state is always visible in words").
 * - `connecting`    → neutral pill, "Connecting…" (transient first attempt).
 * - `reconnecting`  → the spec's "realtime temporarily unavailable" state:
 *                     a warn-tinted pill with a pulsing warn dot that says
 *                     both what is wrong and what is being done about it.
 * - `disconnected`  → terminal state after `terminate()`; unreachable while
 *                     signed in (termination only happens as the shell
 *                     unmounts) but rendered honestly if ever seen.
 *
 * Announcement: a persistent `role="status"` wrapper (implicitly
 * `aria-live="polite"`) stays mounted, so the live-region target itself
 * never enters or leaves the accessibility tree — only its content changes,
 * and state transitions are announced, not merely shown.
 */
interface ConnectionStatusProps {
  state: ConnectionState
}

interface StatusCopy {
  text: string
  /** True for the spec's unavailable state (warn styling); false for the subtler states. */
  unavailable: boolean
}

const STATUS_COPY: Record<ConnectionState, StatusCopy> = {
  connected: { text: 'Connected', unavailable: false },
  connecting: { text: 'Connecting…', unavailable: false },
  reconnecting: { text: 'Realtime messaging temporarily unavailable · reconnecting…', unavailable: true },
  disconnected: { text: 'Realtime messaging temporarily unavailable', unavailable: false },
}

export function ConnectionStatus({ state }: ConnectionStatusProps) {
  const copy = STATUS_COPY[state]
  return (
    <p className="connection-status" role="status">
      {
        // Keyed on the state so a transition re-mounts the pill and replays
        // its (reduced-motion-safe) entrance instead of silently swapping text.
        <span
          key={state}
          className={
            copy.unavailable
              ? 'connection-status-pill connection-status-pill--warn'
              : 'connection-status-pill'
          }
        >
          <span className="connection-status-dot" aria-hidden="true" />
          {copy.text}
        </span>
      }
    </p>
  )
}