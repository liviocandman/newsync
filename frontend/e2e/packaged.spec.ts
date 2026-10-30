import { test, expect, type Response } from '@playwright/test';
import { TestBackend, uiOrigin } from './support/backend.ts';
const backend = new TestBackend('normal');
const sourceId = '50000000-0000-0000-0000-000000000001';
test.beforeAll(async () => {
  await backend.create({ collectionEnabled: false });
});
test.afterAll(async () => {
  await backend.dispose();
});

test('JAR sem Vite → busca e deep link → marcação → restart e renovação única', async ({
  page,
  request,
}, testInfo) => {
  const external: string[] = [];
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.text().includes('Content Security Policy'))
      errors.push(message.text());
  });
  page.context().on('request', (r) => {
    if (new URL(r.url()).origin !== uiOrigin) external.push(r.url());
  });
  const shell = await request.get('/');
  expect(shell.headers()['content-security-policy']).toBe(
    "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; connect-src 'self'; img-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
  );
  expect(shell.headers()['cache-control']).toBe('no-store');
  const html = await shell.text();
  expect(html).toContain('name="news-ui-mode" content="normal"');
  expect(html).not.toMatch(/nonce=|@vite|<style/);
  const assets = [...html.matchAll(/(?:src|href)="(\/assets\/[^\"]+)"/g)].map(
    (m) => m[1],
  );
  expect(assets.length).toBeGreaterThanOrEqual(2);
  for (const asset of assets) {
    const response = await request.get(asset);
    expect(response.status()).toBe(200);
    expect(response.headers()['x-content-type-options']).toBe('nosniff');
    expect(response.headers()['cache-control']).toContain('immutable');
  }
  expect((await request.get('/assets/missing.js')).status()).toBe(404);
  await page.goto('/');
  await expect(page.getByText('Leitor pessoal local')).toBeVisible();
  await page.getByRole('link', { name: 'Gerenciar fontes' }).click();
  await page.reload();
  await expect(
    page.getByRole('heading', { name: 'Fontes de leitura' }),
  ).toBeVisible();
  backend.sql(`INSERT INTO sources(id,feed_url,normalized_feed_url,title,status,created_at,updated_at)
    VALUES ('${sourceId}','https://packaged.invalid/feed','https://packaged.invalid/feed','Acervo empacotado','archived',now(),now());
    INSERT INTO articles(id,source_id,dedup_key,title,content_html,content_text,sort_at,first_seen_at,last_seen_at,updated_at,search_document)
    SELECT ('60000000-0000-0000-0000-' || lpad(n::text,12,'0'))::uuid,'${sourceId}','packaged-' || n,
    'Chamada <img src="https://hostile.invalid/track"> ' || n,'<p>PostgreSQL timeout no texto recebido.</p>',
    'PostgreSQL timeout no texto recebido.','2026-10-27T12:00:00Z',now(),now(),now(),
    to_tsvector('simple','PostgreSQL timeout') FROM generate_series(1,12) n;`);
  await page
    .getByRole('link', { name: 'Buscar no acervo', exact: true })
    .click();
  await page.getByLabel('Termos da busca').fill('PostgreSQL timeout');
  await page.getByLabel('Fonte da busca').selectOption(sourceId);
  await page.getByLabel('Resultados por página').selectOption('10');
  await page.getByRole('button', { name: 'Pesquisar', exact: true }).click();
  const results = page.getByRole('navigation', { name: 'Resultados da busca' });
  await expect(results.getByRole('link')).toHaveCount(10);
  await page.getByRole('button', { name: 'Próximos resultados' }).click();
  await expect(results.getByRole('link')).toHaveCount(2);
  const query = new URL(page.url()).search;
  await results.getByRole('link').first().click();
  const deepLink = page.url();
  await page.reload();
  expect(page.url()).toBe(deepLink);
  await expect(page.getByTestId('article-content')).toContainText(
    'PostgreSQL timeout',
  );
  await page
    .getByRole('button', { name: 'Marcar como lido', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Marcar como não lido' }),
  ).toBeEnabled();
  const sequence: string[] = [];
  const record = (r: Response) => {
    if (new URL(r.url()).pathname.endsWith('/state'))
      sequence.push(`PATCH ${r.status()}`);
    if (new URL(r.url()).pathname.endsWith('/auth/csrf'))
      sequence.push(`GET CSRF ${r.status()}`);
  };
  await backend.stop();
  await backend.start({ collectionEnabled: false });
  page.on('response', record);
  await page
    .getByRole('button', { name: 'Salvar artigo', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Remover dos salvos' }),
  ).toBeEnabled();
  page.off('response', record);
  expect(sequence).toEqual(['PATCH 403', 'GET CSRF 200', 'PATCH 200']);
  await page.getByRole('link', { name: 'Voltar à busca' }).click();
  expect(new URL(page.url()).search).toBe(query);
  await page.reload();
  await expect(results.getByRole('link').first()).toContainText('Lido · Salvo');
  await expect(page.locator('body')).toHaveCSS(
    'background-color',
    'rgb(247, 246, 242)',
  );
  await page.screenshot({
    path: testInfo.outputPath('jar-mesma-origem.png'),
    fullPage: true,
  });
  expect(errors).toEqual([]);
  expect(external).toEqual([]);
});
