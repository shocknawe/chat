import type { ConnectionState } from '../realtime'

/**
 * Connection availability indicator (OpenSpec task 6.6), rendered in the app
 * header beside the identity chip.
 *
 * Per DESIGN.md ("Connection state" plus the Status-Is-Text rule): the state
 * is a WORD first, and color is the second signal, never the only one.
 *
 * - `connected`     → renders nothing; the healthy state needs no banner.
 * - `connecting`    → subtle tinted pill while the first handshake is in
 *                     flight (transient — it resolves within a moment and is
 *                     deliberately quieter than the reconnecting state).
 * - `reconnecting`  → the spec's "realtime temporarily unavailable" state:
 *                     a warn-tinted pill with a warn dot that says both what
 *                     is wrong and what is being done about it.
 * - `disconnected`  → terminal state after `terminate()`; unreachable while
 *                     signed in (termination only happens as the shell
 *                     unmounts) but rendered honestly if ever seen.
 *
 * Announcement: a persistent `role="status"` wrapper (implicitly
 * `aria-live="polite"`) stays mounted even while empty, so the live-region
 * target itself never enters or leaves the accessibility tree — only its
 * content changes, and state transitions are announced, not merely shown.
 */
interface ConnectionStatusProps {
  state: ConnectionState
}

interface StatusCopy {
  text: string
  /** True for the spec's unavailable state (warn styling); false for the subtler transient states. */
  unavailable: boolean
}

const STATUS_COPY: Record<Exclude<ConnectionState, 'connected'>, StatusCopy> = {
  connecting: { text: 'Connecting…', unavailable: false },
  reconnecting: { text: 'Realtime messaging temporarily unavailable · reconnecting…', unavailable: true },
  disconnected: { text: 'Realtime messaging temporarily unavailable', unavailable: false },
}

export function ConnectionStatus({ state }: ConnectionStatusProps) {
  const copy = state === 'connected' ? undefined : STATUS_COPY[state]
  return (
    <p className="connection-status" role="status">
      {copy !== undefined && (
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
      )}
    </p>
  )
}
