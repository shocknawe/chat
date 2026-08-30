import { useCallback, useEffect, useState } from 'react'
import { applyTheme, initialTheme, storeTheme, type Theme } from '../theme'

export interface UseThemeResult {
  theme: Theme
  toggleTheme: () => void
}

/**
 * Owns the active theme (task 2.5): initializes from an explicit stored
 * override or, absent one, the OS preference (`theme.ts`), applies it to the
 * document on every change, and persists explicit toggles so they survive a
 * reload. A toggle always sets an explicit override — once the user has
 * chosen, the OS preference no longer participates for this device.
 */
export function useTheme(): UseThemeResult {
  const [theme, setTheme] = useState<Theme>(() => initialTheme())

  useEffect(() => {
    applyTheme(theme)
  }, [theme])

  const toggleTheme = useCallback(() => {
    setTheme((current) => {
      const next: Theme = current === 'dark' ? 'light' : 'dark'
      storeTheme(next)
      return next
    })
  }, [])

  return { theme, toggleTheme }
}
