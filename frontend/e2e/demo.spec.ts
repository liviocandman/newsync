import { test, expect, type Page } from '@playwright/test';
import { stopProcess } from './support/stop-process.ts';
import { execFileSync, spawn, type ChildProcess } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { join, resolve } from 'node:path';
import { createWriteStream, mkdirSync } from 'node:fs';
import { createInterface } from 'node:readline';

const image =
  'postgres:18.3@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db';
const container = `news-b00-e2e-${randomUUID()}`;
const password = randomUUID();
let containerCreated = false;
let backend: ChildProcess | undefined;
let jdbcUrl = '';
let backendLog: ReturnType<typeof createWriteStream>;
function docker(...args: string[]) {
  return execFileSync('docker', args, {
    encoding: 'utf8',
    timeout: 120_000,
    env: { ...process.env, POSTGRES_PASSWORD: password },
  }).trim();
}
async function stopBackend() {
  await stopProcess(backend);
}
async function startBackend() {
  const java = process.env.JAVA_HOME
    ? join(
        process.env.JAVA_HOME,
        'bin',
        process.platform === 'win32' ? 'java.exe' : 'java',
      )
    : 'java';
  backend = spawn(
    java,
    [
      '-jar',
      resolve('../backend/target/news.jar'),
      '--spring.profiles.active=demo',
      '--server.port=18080',
    ],
    {
      env: {
        ...process.env,
        JDBC_URL: jdbcUrl,
        JDBC_USERNAME: 'b00_test',
        JDBC_PASSWORD: password,
        APP_BASE_URL: 'http://127.0.0.1:5174',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  for (const stream of [backend.stdout!, backend.stderr!]) {
    createInterface({ input: stream }).on('line', (line) =>
      backendLog.write(line.replaceAll(password, '[redacted]') + '\n'),
    );
  }
  backend.on('error', (error) => backendLog.write(String(error)));
  await expect
    .poll(
      async () => {
        if (backend?.exitCode !== null)
          throw new Error(
            'Backend encerrou. Consulte backend.log; compile com -Pdemo.',
          );
        try {
          return (await fetch('http://127.0.0.1:18080/health/ready')).status;
        } catch {
          return 0;
        }
      },
      { timeout: 60_000, intervals: [100, 250, 500] },
    )
    .toBe(200);
}
test.beforeAll(async () => {
  // Evita conectar acidentalmente a um serviço preexistente na porta de teste.
  const probe = createServer();
  probe.listen(18080, '127.0.0.1');
  await once(probe, 'listening');
  await new Promise<void>((resolve, reject) =>
    probe.close((error) => (error ? reject(error) : resolve())),
  );
  mkdirSync('test-results', { recursive: true });
  backendLog = createWriteStream('test-results/backend.log', { flags: 'a' });
  docker(
    'run',
    '--detach',
    '--rm',
    '--name',
    container,
    '--label',
    `news.f02.run=${process.env.NEWS_E2E_RUN_ID}`,
    '--publish',
    '127.0.0.1::5432',
    '--tmpfs',
    '/var/lib/postgresql',
    '--env',
    'POSTGRES_DB=news_b00_e2e',
    '--env',
    'POSTGRES_USER=b00_test',
    '--env',
    'POSTGRES_PASSWORD',
    '--health-cmd',
    'pg_isready -U b00_test -d news_b00_e2e',
    '--health-interval',
    '1s',
    '--health-timeout',
    '3s',
    '--health-retries',
    '30',
    image,
  );
  containerCreated = true;
  await expect
    .poll(
      () =>
        docker('inspect', '--format', '{{.State.Health.Status}}', container),
      { timeout: 45_000 },
    )
    .toBe('healthy');
  const mapped = docker('port', container, '5432/tcp').split(':').at(-1);
  jdbcUrl = `jdbc:postgresql://127.0.0.1:${mapped}/news_b00_e2e`;
  await startBackend();
});
test.afterAll(async () => {
  try {
    await stopBackend();
  } finally {
    try {
      if (containerCreated) docker('rm', '--force', container);
    } finally {
      backendLog?.end();
    }
  }
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
  const articleUrl = page.url();
  await stopBackend();
  await startBackend();
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
