import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { randomBytes } from 'node:crypto';
const nonce = randomBytes(18).toString('base64');
const csp = `default-src 'self'; script-src 'self' 'nonce-${nonce}'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws://127.0.0.1:*; img-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`;
export default defineConfig(({ command, mode }) => ({
  envDir:
    command === 'build' || process.env.NEWS_E2E_RUN_ID ? false : undefined,
  define:
    command === 'build'
      ? {
          'import.meta.env.VITE_APP_MODE': JSON.stringify(
            mode === 'demo' ? 'demo' : 'normal',
          ),
        }
      : {},
  plugins: [
    react(),
    {
      name: 'packaged-ui-mode',
      apply: 'build',
      transformIndexHtml: () => [
        {
          tag: 'meta',
          attrs: {
            name: 'news-ui-mode',
            content: mode === 'demo' ? 'demo' : 'normal',
          },
          injectTo: 'head',
        },
      ],
    },
  ],
  html: command === 'serve' ? { cspNonce: nonce } : undefined,
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
}));
