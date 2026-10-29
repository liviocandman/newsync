import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/playwright.json' }],
  ],
  use: {
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    baseURL: 'http://127.0.0.1:5174',
    viewport: { width: 1280, height: 900 },
    // Traces de rede incluiriam cookies e respostas CSRF. Guardar relatório/captura.
    trace: 'off',
  },
});
