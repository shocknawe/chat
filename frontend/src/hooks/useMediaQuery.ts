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
