import { test, expect, type Response } from '@playwright/test';
import { TestBackend } from './support/backend.ts';
const backend = new TestBackend('normal');
const articleId = '10000000-0000-0000-0000-000000000001';
test.beforeAll(async () => {
  await backend.create({ collectionEnabled: false });
});
test.afterAll(async () => {
  await backend.dispose();
});

test('normal vazio → fonte pendente → leitura → restart recupera CSRF uma vez → ciclo da fonte', async ({
  page,
}, testInfo) => {
  const external: string[] = [];
  page.context().on('request', (request) => {
    if (new URL(request.url()).origin !== 'http://127.0.0.1:5174')
      external.push(request.url());
  });
  await page.goto('/');
  await expect(page.getByText('Leitor pessoal local')).toBeVisible();
  await expect(page.getByText('Nenhum artigo por aqui ainda.')).toBeVisible();
  expect(backend.sql('SELECT count(*) FROM sources')).toBe('0');
  await page.getByRole('link', { name: 'Gerenciar fontes' }).click();
  await page.getByLabel('URL do feed').fill('https://example.invalid/feed.xml');
  await page.getByLabel('Título (opcional)').fill('Fonte de teste local');
  await page.getByRole('button', { name: 'Cadastrar fonte' }).click();
  const source = page.getByRole('listitem', {
    name: 'Fonte Fonte de teste local',
  });
  await expect(
    source.getByText('Pendente; nenhuma importação concluída.'),
  ).toBeVisible();
  expect(backend.sql('SELECT count(*) FROM articles')).toBe('0');
  expect(
    backend.sql(
      'SELECT count(*) FROM sources WHERE manual_requested_at IS NOT NULL OR last_attempt_at IS NOT NULL',
    ),
  ).toBe('0');
  // A infraestrutura prepara dados diretamente no PostgreSQL de teste, sem seed no perfil normal.
  backend.sql(`INSERT INTO articles(id,source_id,dedup_key,external_id,title,content_html,content_text,sort_at,first_seen_at,last_seen_at,updated_at,original_url)
    SELECT '${articleId}',id,'id:browser-test','browser-test','Leitura local de teste','<p>Texto seguro recebido do feed.</p>','Texto seguro recebido do feed.',now(),now(),now(),now(),'https://example.invalid/original' FROM sources;
    INSERT INTO articles(id,source_id,dedup_key,external_id,title,sort_at,first_seen_at,last_seen_at,updated_at)
    SELECT ('20000000-0000-0000-0000-' || lpad(n::text,12,'0'))::uuid,id,'id:page-' || n,'page-' || n,'Chamada ' || n,now() - interval '1 hour',now(),now(),now() FROM sources CROSS JOIN generate_series(1,30) n;`);
  await page.reload();
  await page.getByRole('link', { name: /Leitura local de teste/ }).click();
  await expect(page.getByTestId('article-content')).toContainText(
    'Texto seguro recebido do feed.',
  );
  await expect(
    page.getByRole('link', { name: /Abrir link original/ }),
  ).toHaveAttribute('href', 'https://example.invalid/original');
  await page.getByRole('button', { name: 'Marcar como lido' }).click();
  await expect(
    page.getByRole('button', { name: 'Marcar como não lido' }),
  ).toBeEnabled();
  await page
    .getByRole('button', { name: 'Salvar artigo', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Remover dos salvos' }),
  ).toBeEnabled();
  const before = backend.sql(
    `SELECT id || '|' || read_at || '|' || saved_at FROM articles WHERE id='${articleId}'`,
  );
  const articleUrl = page.url();
  await backend.stop();
  await backend.start({ collectionEnabled: false });
  expect(
    backend.sql(
      `SELECT id || '|' || read_at || '|' || saved_at FROM articles WHERE id='${articleId}'`,
    ),
  ).toBe(before);
  // Captura somente método/status/código; não guarda tokens, cookies ou headers.
  const sequence: string[] = [];
  const responseTasks: Promise<void>[] = [];
  const record = (response: Response) => {
    const path = new URL(response.url()).pathname;
    if (path === `/api/v1/articles/${articleId}/state`) {
      const position = sequence.length;
      sequence.push(`PATCH ${response.status()}`);
      if (response.status() === 403)
        responseTasks.push(
          response.json().then((body: { code: string }) => {
            sequence[position] += `/${body.code}`;
          }),
        );
    } else if (path === '/api/v1/auth/csrf')
      sequence.push(`GET CSRF ${response.status()}`);
  };
  page.on('response', record);
  await page.getByRole('button', { name: 'Remover dos salvos' }).click();
  await expect(
    page.getByRole('button', { name: 'Salvar artigo', exact: true }),
  ).toBeEnabled();
  await Promise.all(responseTasks);
  page.off('response', record);
  expect(sequence).toEqual([
    'PATCH 403/CSRF_INVALID',
    'GET CSRF 200',
    'PATCH 200',
  ]);
  await testInfo.attach('restart-csrf-sequence', {
    body: sequence.join('\n'),
    contentType: 'text/plain',
  });
  await page
    .getByRole('button', { name: 'Salvar artigo', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Remover dos salvos' }),
  ).toBeEnabled();
  await page.reload();
  expect(page.url()).toBe(articleUrl);
  await expect(
    page.getByRole('button', { name: 'Marcar como não lido' }),
  ).toHaveAttribute('aria-pressed', 'true');
  await expect(
    page.getByRole('button', { name: 'Remover dos salvos' }),
  ).toHaveAttribute('aria-pressed', 'true');
  expect(
    backend.sql(
      `SELECT id || '|' || read_at FROM articles WHERE id='${articleId}'`,
    ),
  ).toBe(before.split('|').slice(0, 2).join('|'));
  await page.getByLabel('Filtrar por salvos').selectOption('true');
  await expect(
    page.getByRole('link', { name: /Leitura local de teste/ }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Próxima página' }),
  ).toHaveCount(0);
  await page.getByLabel('Filtrar por salvos').selectOption('');
  await page.getByRole('button', { name: 'Próxima página' }).click();
  await expect(page.getByText('Página 2', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Página anterior' }).click();
  await expect(page.getByText('Página 1', { exact: true })).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath('leitor-normal.png'),
    fullPage: true,
  });
  await backend.stop();
  await backend.start({ collectionEnabled: true });
  await page.reload();
  await page.getByRole('link', { name: 'Gerenciar fontes' }).click();
  await source.getByRole('button', { name: 'Importar agora' }).click();
  await expect(
    source.getByText(
      'Pedido de importação aceito. O resultado aparecerá nesta fonte.',
    ),
  ).toBeVisible();
  await expect(source.getByText('A importação falhou.')).toBeVisible({
    timeout: 25_000,
  });
  await source.getByRole('button', { name: 'Pausar', exact: true }).click();
  await expect(source.getByText('Pausada', { exact: true })).toBeVisible();
  await expect(
    source.getByRole('button', { name: 'Importar agora' }),
  ).toHaveCount(0);
  await source.getByRole('button', { name: 'Reativar' }).click();
  await expect(source.getByText('Ativa', { exact: true })).toBeVisible();
  await source.getByRole('button', { name: 'Arquivar' }).click();
  await expect(source.getByText('Arquivada', { exact: true })).toBeVisible();
  await page.getByLabel('URL do feed').fill('https://example.invalid/feed.xml');
  await page.getByRole('button', { name: 'Cadastrar fonte' }).click();
  await expect(page.getByRole('alert')).toContainText('Reativar');
  await source.getByRole('button', { name: 'Reativar' }).click();
  await expect(source.getByText('Ativa', { exact: true })).toBeVisible();
  expect(backend.sql('SELECT count(*) FROM articles')).toBe('31');
  expect(backend.sql('SELECT count(*) FROM sources')).toBe('1');
  expect(external).toEqual([]);
});
