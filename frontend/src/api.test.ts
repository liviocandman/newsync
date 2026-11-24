import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { api as apiType } from './api';
let api: typeof apiType;
const fetchMock = vi.fn<typeof fetch>();
const state = {
  id: 'a',
  read: true,
  saved: false,
  readAt: '2026-10-29T00:00:00Z',
  savedAt: null,
};
function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
function csrf(token = 'first') {
  return json({ token, headerName: 'X-CSRF-TOKEN' });
}
function error(code: string, status = 403) {
  return json({ code, message: 'Recusado.', requestId: 'r' }, status);
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
beforeEach(async () => {
  vi.resetModules();
  vi.stubGlobal('fetch', fetchMock);
  ({ api } = await import('./api'));
});
afterEach(() => {
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});
it('renova somente CSRF_INVALID e reenvia o mesmo PATCH uma vez', async () => {
  fetchMock
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(error('CSRF_INVALID'))
    .mockResolvedValueOnce(csrf('second'))
    .mockResolvedValueOnce(json(state));
  await expect(api.state('a', { read: true })).resolves.toEqual(state);
  expect(
    fetchMock.mock.calls.map(([url, init]) => [url, init?.method ?? 'GET']),
  ).toEqual([
    ['/api/v1/auth/csrf', 'GET'],
    ['/api/v1/articles/a/state', 'PATCH'],
    ['/api/v1/auth/csrf', 'GET'],
    ['/api/v1/articles/a/state', 'PATCH'],
  ]);
  const first = fetchMock.mock.calls[1][1]!;
  const second = fetchMock.mock.calls[3][1]!;
  expect(first.body).toBe('{"read":true}');
  expect(second.body).toBe(first.body);
  expect(first.headers).toMatchObject({ 'X-CSRF-TOKEN': 'first' });
  expect(second.headers).toMatchObject({ 'X-CSRF-TOKEN': 'second' });
});
it('compartilha o carregamento inicial do token entre gravações concorrentes', async () => {
  const initial = deferred<Response>();
  fetchMock.mockImplementation((url) =>
    url === '/api/v1/auth/csrf'
      ? initial.promise
      : Promise.resolve(json(state)),
  );
  const operations = Promise.all([
    api.state('a', { read: true }),
    api.state('b', { saved: true }),
  ]);
  expect(fetchMock).toHaveBeenCalledTimes(1);
  initial.resolve(csrf());
  await expect(operations).resolves.toEqual([state, state]);
  expect(fetchMock).toHaveBeenCalledTimes(3);
});
it('renova uma única vez para falhas CSRF concorrentes e mantém cada comando', async () => {
  let csrfRequests = 0;
  fetchMock.mockImplementation((url, init) => {
    if (url === '/api/v1/auth/csrf') {
      csrfRequests++;
      return Promise.resolve(csrf(csrfRequests === 1 ? 'first' : 'second'));
    }
    return Promise.resolve(
      new Headers(init?.headers).get('X-CSRF-TOKEN') === 'first'
        ? error('CSRF_INVALID')
        : json(state),
    );
  });
  await expect(
    Promise.all([
      api.state('a', { read: true }),
      api.state('b', { saved: true }),
    ]),
  ).resolves.toEqual([state, state]);
  expect(csrfRequests).toBe(2);
  for (const [id, body] of [
    ['a', '{"read":true}'],
    ['b', '{"saved":true}'],
  ]) {
    const commands = fetchMock.mock.calls.filter(
      ([url]) => url === `/api/v1/articles/${id}/state`,
    );
    expect(commands).toHaveLength(2);
    expect(commands.map(([, init]) => init?.body)).toEqual([body, body]);
    expect(
      commands.map(([, init]) =>
        new Headers(init?.headers).get('X-CSRF-TOKEN'),
      ),
    ).toEqual(['first', 'second']);
  }
});
it('uma falha antiga não invalida a renovação já concluída por outra gravação', async () => {
  const delayed = deferred<Response>();
  const started = deferred<void>();
  let csrfRequests = 0;
  fetchMock.mockImplementation((url, init) => {
    if (url === '/api/v1/auth/csrf') {
      csrfRequests++;
      return Promise.resolve(csrf(csrfRequests === 1 ? 'first' : 'second'));
    }
    if (new Headers(init?.headers).get('X-CSRF-TOKEN') === 'first') {
      if (url === '/api/v1/articles/a/state') {
        started.resolve();
        return delayed.promise;
      }
      return Promise.resolve(error('CSRF_INVALID'));
    }
    return Promise.resolve(json(state));
  });
  const first = api.state('a', { read: true });
  await started.promise;
  await expect(api.state('b', { saved: true })).resolves.toEqual(state);
  delayed.resolve(error('CSRF_INVALID'));
  await expect(first).resolves.toEqual(state);
  await expect(api.state('c', { read: false })).resolves.toEqual(state);
  expect(csrfRequests).toBe(2);
  expect(fetchMock).toHaveBeenCalledTimes(7);
});
it('encerra após a segunda falha CSRF sem um terceiro PATCH', async () => {
  fetchMock
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(error('CSRF_INVALID'))
    .mockResolvedValueOnce(csrf('second'))
    .mockResolvedValueOnce(error('CSRF_INVALID'));
  await expect(api.state('a', { saved: true })).rejects.toMatchObject({
    status: 403,
    code: 'CSRF_INVALID',
  });
  expect(fetchMock).toHaveBeenCalledTimes(4);
});
it('falha na renovação encerra sem reenviar a marcação', async () => {
  fetchMock
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(error('CSRF_INVALID'))
    .mockResolvedValueOnce(error('UNAVAILABLE', 503));
  await expect(api.state('a', { saved: true })).rejects.toMatchObject({
    status: 503,
    code: 'UNAVAILABLE',
  });
  expect(fetchMock).toHaveBeenCalledTimes(3);
});
it.each([
  ['ORIGIN_REJECTED', 403],
  ['FORBIDDEN', 403],
  ['OTHER', 403],
  ['CSRF_INVALID', 401],
  ['UNAVAILABLE', 503],
])('não repete %s com status %s', async (code, status) => {
  fetchMock
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(error(code, status));
  await expect(api.state('a', { read: true })).rejects.toMatchObject({
    status,
    code,
  });
  expect(fetchMock).toHaveBeenCalledTimes(2);
});
it('403 sem código não renova nem repete', async () => {
  fetchMock
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(json({ message: 'Recusado.' }, 403));
  await expect(api.state('a', { read: true })).rejects.toMatchObject({
    status: 403,
    code: undefined,
  });
  expect(fetchMock).toHaveBeenCalledTimes(2);
});
it('erro de rede não renova nem repete', async () => {
  fetchMock
    .mockResolvedValueOnce(csrf())
    .mockRejectedValueOnce(new TypeError('Rede indisponível.'));
  await expect(api.state('a', { read: true })).rejects.toThrow(
    'Rede indisponível.',
  );
  expect(fetchMock).toHaveBeenCalledTimes(2);
});
it('falha no primeiro GET CSRF não envia PATCH e permite nova ação explícita', async () => {
  fetchMock.mockRejectedValueOnce(new TypeError('Rede indisponível.'));
  await expect(api.state('a', { read: true })).rejects.toThrow(
    'Rede indisponível.',
  );
  fetchMock.mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json(state));
  await expect(api.state('a', { read: true })).resolves.toEqual(state);
  expect(
    fetchMock.mock.calls.filter(([, init]) => init?.method === 'PATCH'),
  ).toHaveLength(1);
});
it('serializa filtros e cursor e aceita arquivamento 204', async () => {
  fetchMock.mockResolvedValueOnce(json({ items: [], nextCursor: null }));
  await api.articles({
    sourceId: 's',
    read: false,
    saved: true,
    cursor: 'opaque/+',
  });
  expect(fetchMock.mock.calls[0][0]).toBe(
    '/api/v1/articles?sourceId=s&read=false&saved=true&cursor=opaque%2F%2B',
  );
  fetchMock
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(new Response(null, { status: 204 }));
  await expect(api.archiveSource('s')).resolves.toBeUndefined();
});

it('serializa toda a busca e GET recusado não recupera CSRF', async () => {
  fetchMock.mockResolvedValueOnce(json({ items: [], hasMore: false }));
  const filters = {
    q: 'Java & CSRF',
    sourceId: 's',
    from: '2026-10-27T00:00:00Z',
    to: '2026-10-28T00:00:00Z',
    limit: 10,
    offset: 20,
  };
  await expect(api.search(filters)).resolves.toEqual({
    items: [],
    hasMore: false,
  });
  expect(fetchMock.mock.calls[0][0]).toBe(
    '/api/v1/search?q=Java+%26+CSRF&sourceId=s&from=2026-10-27T00%3A00%3A00Z&to=2026-10-28T00%3A00%3A00Z&limit=10&offset=20',
  );
  fetchMock.mockResolvedValueOnce(error('CSRF_INVALID'));
  await expect(api.search(filters)).rejects.toMatchObject({
    status: 403,
    code: 'CSRF_INVALID',
  });
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(
    fetchMock.mock.calls.every(([url]) =>
      String(url).startsWith('/api/v1/search?'),
    ),
  ).toBe(true);
});
