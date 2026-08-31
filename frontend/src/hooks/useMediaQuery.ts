import { useEffect, useState } from 'react'

/**
 * Subscribes to a media query, re-rendering on change (task 2.6's compact
 * breakpoint detection, and available for any future query). Reads the
 * current match synchronously at mount so the first render is already
 * correct (no flash of the wrong layout).
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches)

  useEffect(() => {
    const mql = window.matchMedia(query)
    setMatches(mql.matches)
    const handleChange = (event: MediaQueryListEvent): void => setMatches(event.matches)
    mql.addEventListener('change', handleChange)
    return () => mql.removeEventListener('change', handleChange)
  }, [query])

  return matches
}

/** The compact-viewport threshold below which the rail becomes an overlay (task 2.6). */
export const COMPACT_QUERY = '(max-width: 720px)'

/**
 * Slice 6 (task 7.5): BELOW this query's bound the open inspector overlays the
 * thread with a scrim and focus confinement; at or above 980px it docks
 * beside the thread. The JS flag owns presentation (dialog semantics, scrim,
 * confinement) and the CSS class switch follows it, so threshold crossings
 * re-present the drawer — never close it — with the inspected message retained.
 */
export const INSPECTOR_OVERLAY_QUERY = '(max-width: 979px)'
