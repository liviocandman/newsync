export type Summary = {
  id: string;
  sourceId: string;
  title: string;
  publishedAt: string | null;
  read: boolean;
  saved: boolean;
};
export type State = {
  id: string;
  read: boolean;
  saved: boolean;
  readAt: string | null;
  savedAt: string | null;
};
export type Article = Summary &
  State & {
    originalUrl: string | null;
    contentHtml: string | null;
    contentText: string | null;
    contentOrigin: 'feed';
    completeness: 'unknown';
  };
export type Source = {
  id: string;
  title: string;
  feedUrl: string;
  state: 'active' | 'paused' | 'archived';
  lastResult: 'never' | 'success' | 'not_modified' | 'partial' | 'failed';
  lastSuccessAt: string | null;
  lastAttemptAt: string | null;
  manualRequestedAt: string | null;
  retryAfterAt: string | null;
  nextPollAt: string;
  consecutiveFailures: number;
  collecting: boolean;
  collectionEnabled: boolean;
  diagnostic: { code: string; message: string; ignoredItems: number } | null;
};
export type Filters = {
  sourceId?: string;
  read?: boolean;
  saved?: boolean;
  limit?: number;
  cursor?: string;
};
export type ArticlePage = { items: Summary[]; nextCursor: string | null };
export type SearchFilters = {
  q: string;
  sourceId?: string;
  from?: string;
  to?: string;
  limit: number;
  offset: number;
};
export type SearchPage = { items: Summary[]; hasMore: boolean };
type Csrf = { token: string; headerName: string };
let csrf: Promise<Csrf> | undefined;
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public code?: string,
    public retryAfter?: string | null,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    credentials: 'same-origin',
    ...options,
  });
  if (!response.ok) {
    let message = `Não foi possível concluir a operação (${response.status}).`;
    let code: string | undefined;
    try {
      const error: unknown = await response.json();
      if (error && typeof error === 'object') {
        if ('message' in error && typeof error.message === 'string')
          message = error.message;
        if ('code' in error && typeof error.code === 'string')
          code = error.code;
      }
    } catch {
      /* The response has no JSON body. */
    }
    throw new ApiError(
      message,
      response.status,
      code,
      response.headers.get('Retry-After'),
    );
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
function token() {
  return (csrf ??= request<Csrf>('/auth/csrf').catch((error) => {
    csrf = undefined;
    throw error;
  }));
}
async function write<T>(
  path: string,
  method: string,
  payload?: unknown,
): Promise<T> {
  // Serialize once to retain the same declarative command throughout recovery.
  const body = payload === undefined ? undefined : JSON.stringify(payload);
  for (let attempt = 0; ; attempt++) {
    const current = await token();
    try {
      return await request<T>(path, {
        method,
        headers: {
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          [current.headerName]: current.token,
        },
        body,
      });
    } catch (error) {
      if (
        attempt === 0 &&
        error instanceof ApiError &&
        error.status === 403 &&
        error.code === 'CSRF_INVALID'
      ) {
        csrf = undefined;
        continue;
      }
      throw error;
    }
  }
}
export const api = {
  search: (filters: SearchFilters, signal?: AbortSignal) => {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(filters)) {
      if (value !== undefined && value !== '') query.set(key, String(value));
    }
    return request<SearchPage>(`/search?${query}`, { signal });
  },
  articles: (filters: Filters = {}) => {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(filters)) {
      if (value !== undefined && value !== '') query.set(key, String(value));
    }
    return request<ArticlePage>(`/articles${query.size ? `?${query}` : ''}`);
  },
  sources: () => request<Source[]>('/sources'),
  source: (id: string) => request<Source>(`/sources/${encodeURIComponent(id)}`),
  createSource: (payload: { feedUrl: string; title?: string }) =>
    write<Source>('/sources', 'POST', payload),
  updateSource: (
    id: string,
    patch: { title?: string; state?: Source['state'] },
  ) => write<Source>(`/sources/${encodeURIComponent(id)}`, 'PATCH', patch),
  archiveSource: (id: string) =>
    write<void>(`/sources/${encodeURIComponent(id)}`, 'DELETE'),
  refreshSource: (id: string) =>
    write<{ status: 'scheduled' | 'collecting' }>(
      `/sources/${encodeURIComponent(id)}/refresh`,
      'POST',
    ),
  article: (id: string) =>
    request<Article>(`/articles/${encodeURIComponent(id)}`),
  state: (id: string, patch: Partial<Pick<State, 'read' | 'saved'>>) =>
    write<State>(`/articles/${encodeURIComponent(id)}/state`, 'PATCH', patch),
};
