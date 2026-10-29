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
