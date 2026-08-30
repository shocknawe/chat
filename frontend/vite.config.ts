/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // Backend origin the dev server proxies to. Overridable per environment
  // (e.g. inside docker-compose the frontend container reaches the backend
  // container by service name).
  const backendTarget = env.VITE_BACKEND_ORIGIN ?? 'http://localhost:8080'

  return {
    plugins: [react()],
    server: {
      host: true,
      port: 5173,
      proxy: {
        // REST API
        '/api': {
          target: backendTarget,
          changeOrigin: true,
        },
        // Application-owned WebSocket realtime layer (raw WebSocketHandler,
        // no STOMP broker — see design.md).
        '/ws': {
          target: backendTarget,
          ws: true,
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
    },
  }
})
