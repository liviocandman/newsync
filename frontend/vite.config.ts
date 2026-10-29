import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { randomBytes } from 'node:crypto';
const nonce = randomBytes(18).toString('base64');
const csp = `default-src 'self'; script-src 'self' 'nonce-${nonce}'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws://127.0.0.1:*; img-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`;
export default defineConfig({
  envDir: process.env.NEWS_E2E_RUN_ID ? false : undefined,
  plugins: [react()],
  html: { cspNonce: nonce },
  server: {
    host: '127.0.0.1',
    strictPort: true,
    headers: {
      'Content-Security-Policy': csp,
      'Referrer-Policy': 'no-referrer',
    },
    proxy: Object.fromEntries(
      ['/api/v1', '/health'].map((path) => [
        path,
        {
          target: process.env.API_PROXY_TARGET ?? 'http://127.0.0.1:8080',
          changeOrigin: false,
        },
      ]),
    ),
  },
  preview: {
    host: '127.0.0.1',
    headers: {
      'Content-Security-Policy': csp,
      'Referrer-Policy': 'no-referrer',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'tests/**/*.test.ts'],
  },
});
