import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e',
  outputDir: `test-results/${process.env.NEWS_E2E_MODE ?? 'normal'}`,
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  reporter: [
    ['list'],
    [
      'json',
      {
        outputFile: `test-results/playwright-${process.env.NEWS_E2E_MODE ?? 'normal'}.json`,
      },
    ],
  ],
  use: {
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    baseURL: process.env.NEWS_E2E_PACKAGED
      ? 'http://127.0.0.1:18080'
      : 'http://127.0.0.1:5174',
    viewport: { width: 1280, height: 900 },
    // Network traces would include cookies and CSRF responses. Keep only reports and screenshots.
    trace: 'off',
  },
});
