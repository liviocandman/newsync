import { test, expect } from '@playwright/test';

test('imagem instalada → fonte coletada → busca → detalhe e marcação sem Vite', async ({
  page,
}, testInfo) => {
  const external: string[] = [];
  const errors: string[] = [];
  page.context().on('request', (request) => {
    if (new URL(request.url()).origin !== 'http://127.0.0.1:18080')
      external.push(request.url());
  });
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.text().includes('Content Security Policy'))
      errors.push(message.text());
  });
  await page.goto('/');
  await expect(page.getByText('Leitor pessoal local')).toBeVisible();
  await page
    .getByRole('link', { name: 'Buscar no acervo', exact: true })
    .click();
  await page.getByLabel('Termos da busca').fill('PostgreSQL');
  await page.getByRole('button', { name: 'Pesquisar', exact: true }).click();
  const results = page.getByRole('navigation', { name: 'Resultados da busca' });
  await expect(results.getByRole('link')).toHaveCount(1);
  await results.getByRole('link').click();
  await page.reload();
  await expect(page.getByTestId('article-content')).toContainText(
    'Texto seguro',
  );
  await expect(
    page.getByTestId('article-content').locator('img,script,iframe,style'),
  ).toHaveCount(0);
  expect(
    await page.evaluate(() => Reflect.get(window, '__hostile')),
  ).toBeUndefined();
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
  await page.reload();
  await expect(results.getByRole('link')).toContainText('Lido · Salvo');
  await page.screenshot({
    path: testInfo.outputPath('imagem-instalada.png'),
    fullPage: true,
  });
  expect(errors).toEqual([]);
  expect(external).toEqual([]);
});
