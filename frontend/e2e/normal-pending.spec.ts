import { test, expect } from '@playwright/test';
import { TestBackend } from './support/backend.ts';

const backend = new TestBackend('normal');
test.beforeAll(async () => {
  await backend.create({ collectionEnabled: false });
});
test.afterAll(async () => {
  await backend.dispose();
});

test('pedido manual e cooldown sobrevivem ao restart com banco próprio', async ({
  page,
}, testInfo) => {
  await page.goto('/sources');
  await page
    .getByLabel('URL do feed')
    .fill('https://example.invalid/pending.xml');
  await page.getByLabel('Título (opcional)').fill('Pedido persistido');
  await page.getByRole('button', { name: 'Cadastrar fonte' }).click();
  const source = page.getByRole('listitem', {
    name: 'Fonte Pedido persistido',
  });
  await source.getByRole('button', { name: 'Importar agora' }).click();
  await expect(
    source.getByText('Importação solicitada; aguardando resultado.'),
  ).toBeVisible();
  const sourceId = backend.sql('SELECT id FROM sources');
  const acceptedAt = backend.sql('SELECT last_manual_refresh_at FROM sources');
  expect(
    backend.sql(
      "SELECT count(*) FROM sources WHERE manual_requested_at IS NOT NULL AND last_attempt_at IS NULL AND last_result='never'",
    ),
  ).toBe('1');
  expect(backend.sql('SELECT count(*) FROM articles')).toBe('0');

  await backend.stop();
  expect(
    backend.sql(
      'SELECT count(*) FROM sources WHERE manual_requested_at IS NOT NULL',
    ),
  ).toBe('1');
  await backend.start({ collectionEnabled: true });
  await page.reload();
  await expect(source.getByText('A importação falhou.')).toBeVisible({
    timeout: 25_000,
  });
  expect(backend.sql('SELECT last_manual_refresh_at FROM sources')).toBe(
    acceptedAt,
  );
  expect(
    backend.sql(
      "SELECT count(*) FROM sources WHERE manual_requested_at IS NULL AND last_attempt_at IS NOT NULL AND last_result='failed' AND diagnostic_code='DNS_FAILED'",
    ),
  ).toBe('1');

  const limited = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname ===
        `/api/v1/sources/${sourceId}/refresh` &&
      response.request().method() === 'POST' &&
      response.status() === 429,
  );
  await source.getByRole('button', { name: 'Importar agora' }).click();
  const response = await limited;
  expect(((await response.json()) as { code: string }).code).toBe(
    'REFRESH_COOLDOWN',
  );
  expect(Number(response.headers()['retry-after'])).toBeGreaterThan(0);
  expect(
    backend.sql(
      'SELECT count(*) FROM sources WHERE manual_requested_at IS NOT NULL',
    ),
  ).toBe('0');
  await testInfo.attach('pedido-apos-restart', {
    body: 'pedido durável antes do restart → failed/DNS_FAILED → pedido limpo → POST refresh 429 com Retry-After',
    contentType: 'text/plain',
  });
});
