import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import App from './App.tsx'
import { queryClient } from './queryClient.ts'
import { applyTheme, initialTheme } from './theme.ts'

/**
 * Task 2.5, pre-paint theme resolution. `useTheme` only mounts inside
 * `SignedInShell`, so a persisted override would otherwise wait until AFTER
 * the identity gate's first paint to apply — a dark-mode user sees a light
 * flash on every reload before signing in. Resolving the theme here (stored
 * override, else the OS preference) puts the `data-theme` attribute on the
 * document before `render` runs, so the very first paint is already correct.
 * `useTheme` still owns subsequent toggles; this only covers the pre-React
 * frame.
 */
applyTheme(initialTheme())

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
