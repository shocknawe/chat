import { useCallback, useRef, useState } from 'react'

export interface UseAnnouncerResult {
  /** Current text of the live region — render this inside an `aria-live="polite"` node. */
  announcement: string
  /** Publishes a new announcement (task 2.7: conversation, identity, delivery-status changes). */
  announce: (message: string) => void
}

/**
 * A single app-wide polite live region (task 2.7). Lives at the top of the
 * component tree (`App`) rather than inside `SignedInShell` so an identity
 * switch — which remounts the whole shell via its `key` — does not tear down
 * the announcer along with everything else it is meant to announce.
 *
 * Re-announcing the same text twice in a row (e.g. selecting the
 * already-selected conversation) would not re-trigger assistive-tech output
 * because the live region's content did not change; a trailing zero-width
 * space toggle guards against that by making consecutive identical messages
 * textually distinct without being visible or read aloud as extra content.
 */
export function useAnnouncer(): UseAnnouncerResult {
  const [announcement, setAnnouncement] = useState('')
  const toggleRef = useRef(false)

  const announce = useCallback((message: string) => {
    toggleRef.current = !toggleRef.current
    setAnnouncement(toggleRef.current ? message : `${message}​`)
  }, [])

  return { announcement, announce }
}
