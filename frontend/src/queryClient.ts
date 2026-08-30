import { QueryClient } from '@tanstack/react-query'

/**
 * The single TanStack Query client instance (task 6.4).
 *
 * Extracted from `main.tsx` so cache helpers (`messagesCache.ts`) and the
 * React tree share ONE client: realtime event handlers mutate the exact same
 * cache that `useQuery` subscribers read. `main.tsx` imports this module and
 * hands the instance to `QueryClientProvider`.
 *
 * TanStack Query owns REST server-state caching (see design.md — WebSocket
 * events patch/invalidate these caches but are not routed through it).
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
})
