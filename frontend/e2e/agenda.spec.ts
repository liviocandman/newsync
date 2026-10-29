import { test, expect } from '@playwright/test';
import { TestBackend } from './support/backend.ts';
const backend = new TestBackend('normal');
test.beforeAll(async () => {
  await backend.create();
});
test.afterAll(async () => {
  await backend.dispose();
});
test('agenda automática apresenta diagnóstico sem pedido manual', async ({
  page,
}, testInfo) => {
  await page.goto('/sources');
  await page
    .getByLabel('URL do feed')
    .fill('https://example.invalid/automatic.xml');
  await page.getByLabel('Título (opcional)').fill('Agenda automática');
  await page.getByRole('button', { name: 'Cadastrar fonte' }).click();
  const source = page.getByRole('listitem', {
    name: 'Fonte Agenda automática',
  });
  await expect(source.getByText('A importação falhou.')).toBeVisible({
    timeout: 25_000,
  });
  await expect(source.getByText('Falhas consecutivas: 1.')).toBeVisible();
  await expect(source.getByText(/Próxima elegibilidade:/)).toBeVisible();
  await expect(source.getByText(/Última tentativa:/)).toBeVisible();
  expect(
    backend.sql(
      "SELECT count(*) FROM sources WHERE manual_requested_at IS NULL AND last_manual_refresh_at IS NULL AND last_attempt_at IS NOT NULL AND last_result='failed' AND next_poll_at>last_attempt_at",
    ),
  ).toBe('1');
  await page.screenshot({
    path: testInfo.outputPath('agenda-normal.png'),
    fullPage: true,
  });
});
