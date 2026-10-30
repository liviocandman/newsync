import { chromium } from '@playwright/test';
import { existsSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { resolve } from 'node:path';

const [directory, report] = process.argv.slice(2);
const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.text().includes('Content Security Policy'))
      errors.push(message.text());
  });
  const assets = async () =>
    page
      .locator('script[src],link[rel="stylesheet"]')
      .evaluateAll((nodes) =>
        nodes
          .map(
            (node) =>
              new URL(
                node.getAttribute('src') ?? node.getAttribute('href'),
                location.href,
              ).pathname,
          )
          .sort(),
      );
  await page.goto('http://127.0.0.1:18080/');
  await page.getByText('Leitor pessoal local', { exact: true }).waitFor();
  const before = await assets();
  writeFileSync(resolve(directory, 'browser-ready'), 'ready');
  const deadline = Date.now() + 90_000;
  while (!existsSync(resolve(directory, 'updated'))) {
    if (Date.now() > deadline)
      throw new Error('Atualização não foi sinalizada.');
    await delay(100);
  }
  const response = await page.reload();
  await page.getByText('Leitor pessoal local', { exact: true }).waitFor();
  const after = await assets();
  if (JSON.stringify(before) === JSON.stringify(after))
    throw new Error('Candidato não trocou assets.');
  if (response.headers()['cache-control'] !== 'no-store')
    throw new Error('HTML conservou cache persistente.');
  for (const path of before.filter((path) => !after.includes(path))) {
    if (
      (await page.request.get('http://127.0.0.1:18080' + path)).status() !== 404
    )
      throw new Error(
        'Asset anterior recebeu fallback ou persistiu no novo artefato.',
      );
  }
  if (
    (await page
      .locator('body')
      .evaluate((element) => getComputedStyle(element).backgroundColor)) !==
      'rgb(247, 246, 242)' ||
    errors.length
  )
    throw new Error('CSS/JavaScript/CSP falhou depois da troca de build.');
  writeFileSync(
    report,
    JSON.stringify({ status: 'passed', before, after }) + '\n',
  );
} finally {
  await browser.close();
}
