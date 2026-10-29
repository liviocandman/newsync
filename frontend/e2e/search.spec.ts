import { test, expect } from '@playwright/test';
import { TestBackend } from './support/backend.ts';
const backend = new TestBackend('normal');
const sourceId = '50000000-0000-0000-0000-000000000001';
test.beforeAll(async () => {
  await backend.create({ collectionEnabled: false });
});
test.afterAll(async () => {
  await backend.dispose();
});

test('busca no acervo → filtro e página → detalhe e marcação → volta com contexto', async ({
  page,
}, testInfo) => {
  const external: string[] = [];
  page.context().on('request', (request) => {
    if (new URL(request.url()).origin !== 'http://127.0.0.1:5174')
      external.push(request.url());
  });
  backend.sql(`INSERT INTO sources(id,feed_url,normalized_feed_url,title,status,created_at,updated_at)
    VALUES ('${sourceId}','https://search.invalid/feed','https://search.invalid/feed','Acervo arquivado','archived',now(),now());
    INSERT INTO articles(id,source_id,dedup_key,title,content_html,content_text,sort_at,first_seen_at,last_seen_at,updated_at,search_document)
    SELECT ('60000000-0000-0000-0000-' || lpad(n::text,12,'0'))::uuid,'${sourceId}','search-' || n,'Chamada técnica ' || n,
    '<p>PostgreSQL timeout no texto recebido.</p>','PostgreSQL timeout no texto recebido.','2026-10-27T12:00:00Z',now(),now(),now(),
    setweight(to_tsvector('simple','Chamada técnica ' || n),'A') || setweight(to_tsvector('simple','PostgreSQL timeout no texto recebido.'),'B')
    FROM generate_series(1,12) n;`);
  await page.goto('/');
  await page
    .getByRole('link', { name: 'Buscar no acervo', exact: true })
    .click();
  await page.getByLabel('Termos da busca').fill('PostgreSQL timeout');
  await page.getByLabel('Fonte da busca').selectOption(sourceId);
  await page.getByLabel('A partir de').fill('2026-10-27T00:00');
  await page.getByLabel('Antes de').fill('2026-10-28T00:00');
  await page.getByLabel('Resultados por página').selectOption('10');
  await page.getByRole('button', { name: 'Pesquisar', exact: true }).click();
  const results = page.getByRole('navigation', { name: 'Resultados da busca' });
  await expect(results.getByRole('link')).toHaveCount(10);
  await page.getByRole('button', { name: 'Próximos resultados' }).click();
  await expect(results.getByRole('link')).toHaveCount(2);
  await expect(
    page.getByRole('button', { name: 'Próximos resultados' }),
  ).toBeDisabled();
  const context = new URL(page.url()).search;
  expect(new URLSearchParams(context).get('offset')).toBe('10');
  await results.getByRole('link').first().click();
  await expect(page.getByTestId('article-content')).toContainText(
    'PostgreSQL timeout',
  );
  await page
    .getByRole('button', { name: 'Marcar como lido', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Marcar como não lido' }),
  ).toBeEnabled();
  await page
    .getByRole('button', { name: 'Salvar artigo', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Remover dos salvos' }),
  ).toBeEnabled();
  await page.getByRole('link', { name: 'Voltar à busca' }).click();
  expect(new URL(page.url()).search).toBe(context);
  await expect(results.getByRole('link').first()).toContainText('Lido · Salvo');
  await page.reload();
  await expect(results.getByRole('link')).toHaveCount(2);
  await expect(results.getByRole('link').first()).toContainText('Lido · Salvo');
  await expect(page.getByLabel('Fonte da busca')).toHaveValue(sourceId);
  await expect(page.getByLabel('A partir de')).toHaveValue('2026-10-27T00:00');
  await page.getByRole('button', { name: 'Resultados anteriores' }).click();
  await expect(results.getByRole('link')).toHaveCount(10);
  await page.screenshot({
    path: testInfo.outputPath('busca-acervo.png'),
    fullPage: true,
  });
  expect(external).toEqual([]);
});
