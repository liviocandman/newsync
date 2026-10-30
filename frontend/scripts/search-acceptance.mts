// Manual acceptance using real feeds, separate from deterministic gate regressions.
// Requires the normal JAR and SHA-256 produced by ./scripts/verify.
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { TestBackend } from '../e2e/support/backend.ts';
import type { Source, Summary, SearchPage } from '../src/api.ts';

// Use the E2E supervisor configuration isolation; never inherit personal JDBC or Spring settings.
for (const key of Object.keys(process.env)) {
  if (
    !/^(PATH|HOME|USER|JAVA_HOME|TMPDIR|CI|LANG|LC_.*|LD_LIBRARY_PATH|PLAYWRIGHT_.*|DOCKER_.*|NODE_EXTRA_CA_CERTS|npm_config_cache|NEWS_NORMAL_JAR|NEWS_NORMAL_SHA256)$/.test(
      key,
    )
  )
    delete process.env[key];
}
const destination = resolve(
  process.argv[2] ?? '../docs/evidencias/B-05/pesquisas.json',
);
const jar = process.env.NEWS_NORMAL_JAR;
if (
  !jar ||
  createHash('sha256').update(readFileSync(jar)).digest('hex') !==
    process.env.NEWS_NORMAL_SHA256
)
  throw new Error('Informe JAR normal e hash verificados pelo gate.');
process.env.NEWS_E2E_RUN_ID = `search-acceptance-${randomUUID()}`;
const backend = new TestBackend('normal');
const base = 'http://127.0.0.1:18080/api/v1';
async function read<T>(path: string): Promise<T> {
  const response = await fetch(base + path);
  if (!response.ok)
    throw new Error(`GET ${path.split('?')[0]}: ${response.status}`);
  return response.json() as Promise<T>;
}
async function write(path: string, method: string, payload?: unknown) {
  const session = await fetch(base + '/auth/csrf');
  const csrf = (await session.json()) as { headerName: string; token: string };
  const cookie = session.headers
    .getSetCookie()
    .map((value) => value.split(';')[0])
    .join('; ');
  const response = await fetch(base + path, {
    method,
    headers: {
      Origin: 'http://127.0.0.1:5174',
      Cookie: cookie,
      [csrf.headerName]: csrf.token,
      'Content-Type': 'application/json',
    },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
  if (!response.ok) throw new Error(`${method} ${path}: ${response.status}`);
  return response.status === 204 ? undefined : response.json();
}
const report: Record<string, unknown> = {
  executedAt: new Date().toISOString(),
  jarSha256: process.env.NEWS_NORMAL_SHA256,
  mode: 'isolated-tmpfs-real-feeds',
  originalPagesDownloaded: false,
};
let failure: unknown;
try {
  await backend.create({ collectionEnabled: false });
  for (const [feedUrl, title] of [
    ['https://feed.infoq.com/', 'InfoQ'],
    ['https://github.blog/feed/', 'GitHub Blog'],
    ['https://thenewstack.io/blog/feed/', 'The New Stack'],
    ['https://news.ycombinator.com/rss', 'Hacker News'],
  ])
    await write('/sources', 'POST', { feedUrl, title });
  await backend.stop();
  await backend.start({ collectionEnabled: true });
  const deadline = Date.now() + 150000;
  let sources: Source[] = [];
  while (Date.now() < deadline) {
    sources = await read<Source[]>('/sources');
    if (
      sources.every(
        (source) => source.lastResult !== 'never' && !source.collecting,
      )
    )
      break;
    await delay(1000);
  }
  report.sources = sources.map(
    ({ id, title, feedUrl, lastResult, diagnostic }) => ({
      id,
      title,
      feedUrl,
      lastResult,
      diagnostic,
    }),
  );
  await backend.stop();
  await backend.start({ collectionEnabled: false });
  const all: Summary[] = [];
  for (const source of sources)
    all.push(
      ...(
        await read<{ items: Summary[] }>(
          `/articles?sourceId=${source.id}&limit=100`,
        )
      ).items,
    );
  report.persistedCount = backend.sql('SELECT count(*) FROM articles');
  const historical = JSON.parse(
    readFileSync(resolve('../docs/evidencias/B-01-amostra.json'), 'utf8'),
  ).retrieval_examples as {
    id: string;
    reference_item_id: string;
    candidate_search_terms_for_B05: string;
  }[];
  report.historical = await Promise.all(
    historical.map(async (reference) => {
      const expectedId =
        backend.sql(
          `SELECT id FROM articles WHERE external_id='${reference.reference_item_id.replaceAll("'", "''")}'`,
        ) || null;
      const result = await read<SearchPage>(
        '/search?' +
          new URLSearchParams({ q: reference.candidate_search_terms_for_B05 }),
      );
      return {
        id: reference.id,
        query: reference.candidate_search_terms_for_B05,
        expectedId,
        referencePresent: expectedId !== null,
        foundExpected:
          expectedId === null
            ? null
            : result.items.some((item) => item.id === expectedId),
        result,
      };
    }),
  );
  if (all.length < 10)
    throw new Error(
      'Menos de dez artigos reais disponíveis; qualidade de uso permanece pendente.',
    );
  // Distribute across sources when possible; each expectation comes from a persisted title.
  const selected: Summary[] = [];
  for (let position = 0; selected.length < 10; position++) {
    for (const source of sources) {
      const article = all.filter((item) => item.sourceId === source.id)[
        position
      ];
      if (article && selected.length < 10) selected.push(article);
    }
  }
  const searches = [];
  for (let i = 0; i < selected.length; i++) {
    const article = selected[i];
    const tokens = article.title.match(/[\p{L}\p{N}]+/gu) ?? [];
    const useful = tokens.filter(
      (token) =>
        token.length >= 4 &&
        !/^(this|that|with|from|your|have|what|when|about|more|into|using)$/i.test(
          token,
        ),
    );
    const q = (useful.length >= 2 ? useful : tokens).slice(0, 3).join(' ');
    const params = new URLSearchParams({ q, limit: '100' });
    if (i === 6 || i === 9) params.set('sourceId', article.sourceId);
    if (i === 7) {
      const time = backend.sql(
        `SELECT sort_at FROM articles WHERE id='${article.id}'`,
      );
      params.set('from', new Date(time).toISOString());
      params.set('to', new Date(new Date(time).getTime() + 1000).toISOString());
    }
    if (i === 9) {
      await write(`/articles/${article.id}/state`, 'PATCH', { saved: true });
      await write(`/sources/${article.sourceId}`, 'DELETE');
    }
    const result = await read<SearchPage>('/search?' + params);
    searches.push({
      case: `U${i + 1}`,
      query: q,
      sourceId: params.get('sourceId'),
      from: params.get('from'),
      to: params.get('to'),
      expectedId: article.id,
      observedIds: result.items.map((item) => item.id),
      found: result.items.some((item) => item.id === article.id),
      bodyAbsent:
        backend.sql(
          `SELECT content_text IS NULL FROM articles WHERE id='${article.id}'`,
        ) === 't',
      savedArchived: i === 9,
      hasMore: result.hasMore,
    });
  }
  report.searches = searches;
  report.quality = searches.every((search) => search.found)
    ? 'dez chamadas reais recuperadas; limites linguísticos cobertos por T-12'
    : 'ajuste necessário; uma ou mais chamadas esperadas ausentes';
  if (!searches.every((search) => search.found))
    throw new Error('Falha na recuperação esperada.');
} catch (error) {
  failure = error;
  report.failure = error instanceof Error ? error.message : String(error);
} finally {
  await backend.dispose();
  report.cleanup = 'backend encerrado e somente container próprio removido';
  mkdirSync(resolve(destination, '..'), { recursive: true });
  writeFileSync(destination, JSON.stringify(report, null, 2) + '\n');
}
if (failure) throw failure;
console.log(`Aceite registrado em ${destination}`);
