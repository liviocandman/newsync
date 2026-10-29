import { test, expect, type Page } from '@playwright/test';
import { TestBackend } from './support/backend.ts';
const backend = new TestBackend('demo');
test.beforeAll(async () => {
  await backend.create();
});
test.afterAll(async () => {
  await backend.dispose();
});

async function expectSafeBody(page: Page, external: string[]) {
  const body = page.getByTestId('article-content');
  await expect(body).toContainText(
    'Três vasos receberam sementes nesta manhã.',
  );
  await expect(body).toContainText('Texto seguro.');
  await expect(
    body.getByRole('link', { name: 'Link seguro', exact: true }),
  ).toHaveAttribute('href', 'https://example.invalid/seguro');
  await expect(
    body.getByRole('link', { name: 'Link HTTP', exact: true }),
  ).toHaveAttribute('href', 'http://example.invalid/seguro-http');
  const violations = await body.evaluate((element) =>
    [...element.querySelectorAll('*')].flatMap((node) => {
      const issues: string[] = [];
      if (!['P', 'A'].includes(node.tagName)) issues.push(node.tagName);
      for (const attribute of node.attributes) {
        if (!['href', 'rel'].includes(attribute.name))
          issues.push(attribute.name);
      }
      if (node.hasAttribute('href')) {
        const url = new URL(node.getAttribute('href')!, document.baseURI);
        if (!['http:', 'https:'].includes(url.protocol))
          issues.push(url.protocol);
        if (node.getAttribute('rel') !== 'noopener noreferrer')
          issues.push('rel');
      }
      return issues;
    }),
  );
  expect(violations).toEqual([]);
  expect(
    await page.evaluate(() => Reflect.get(window, '__hostile')),
  ).toBeUndefined();
  expect(external).toEqual([]);
}
test('abrir → marcar → recarregar → reiniciar preserva estados e bloqueia conteúdo ativo', async ({
  page,
}, testInfo) => {
  const external: string[] = [];
  page.context().on('request', (request) => {
    if (new URL(request.url()).origin !== 'http://127.0.0.1:5174')
      external.push(request.url());
  });
  await page.goto('/');
  await expect(page.getByText('Demonstração local · B-00')).toBeVisible();
  await expect(
    page.getByRole('link', { name: 'Gerenciar fontes' }),
  ).toHaveCount(0);
  await page.getByRole('link', { name: /Uma horta na janela/ }).click();
  await expect(
    page.getByRole('heading', { name: 'Uma horta na janela' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Marcar como lido' }),
  ).toHaveAttribute('aria-pressed', 'false');
  await expectSafeBody(page, external);
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
  await page.reload();
  await expectSafeBody(page, external);
  await expect(
    page.getByRole('button', { name: 'Marcar como não lido' }),
  ).toHaveAttribute('aria-pressed', 'true');
  await expect(
    page.getByRole('button', { name: 'Remover dos salvos' }),
  ).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Remover dos salvos' }).click();
  await expect(
    page.getByRole('button', { name: 'Salvar artigo', exact: true }),
  ).toBeEnabled();
  await page
    .getByRole('button', { name: 'Salvar artigo', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Remover dos salvos' }),
  ).toBeEnabled();
  const articleUrl = page.url();
  await backend.stop();
  await backend.start();
  // A página ainda possui o token e cookie anteriores ao restart.
  await page.getByRole('button', { name: 'Remover dos salvos' }).click();
  await expect(
    page.getByRole('button', { name: 'Salvar artigo', exact: true }),
  ).toBeEnabled();
  await page
    .getByRole('button', { name: 'Salvar artigo', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Remover dos salvos' }),
  ).toBeEnabled();
  await page.reload();
  await expectSafeBody(page, external);
  expect(page.url()).toBe(articleUrl);
  await expect(
    page.getByRole('button', { name: 'Marcar como não lido' }),
  ).toHaveAttribute('aria-pressed', 'true');
  await expect(
    page.getByRole('button', { name: 'Remover dos salvos' }),
  ).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Marcar como não lido' }).click();
  await expect(
    page.getByRole('button', { name: 'Marcar como lido' }),
  ).toBeEnabled();
  await page.getByRole('button', { name: 'Marcar como lido' }).click();
  await expect(
    page.getByRole('button', { name: 'Marcar como não lido' }),
  ).toBeEnabled();
  await page.screenshot({
    path: testInfo.outputPath('leitor.png'),
    fullPage: true,
  });
  await page.getByRole('link', { name: /Um caderno em branco/ }).click();
  await expect(page.getByText('Este artigo veio sem corpo.')).toBeVisible();
  expect(external).toEqual([]);
});
