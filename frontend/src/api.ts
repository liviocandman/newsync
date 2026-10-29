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
  lastResult: string;
  lastSuccessAt: string | null;
};
type Csrf = { token: string; headerName: string };
let csrf: Promise<Csrf> | undefined;
async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    credentials: 'same-origin',
    ...options,
  });
  if (!response.ok) {
    let message = `Não foi possível concluir a operação (${response.status}).`;
    try {
      const error = await response.json();
      if (typeof error.message === 'string') message = error.message;
    } catch {
      /* Resposta sem JSON. */
    }
    throw new ApiError(message, response.status);
  }
  return response.json() as Promise<T>;
}
class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
function token() {
  return (csrf ??= request<Csrf>('/auth/csrf').catch((error) => {
    csrf = undefined;
    throw error;
  }));
}
export const api = {
  articles: () => request<{ items: Summary[]; nextCursor: null }>('/articles'),
  sources: () => request<Source[]>('/sources'),
  article: (id: string) =>
    request<Article>(`/articles/${encodeURIComponent(id)}`),
  async state(
    id: string,
    patch: Partial<Pick<State, 'read' | 'saved'>>,
  ): Promise<State> {
    for (let attempt = 0; ; attempt++) {
      const current = await token();
      try {
        return await request<State>(
          `/articles/${encodeURIComponent(id)}/state`,
          {
            method: 'PATCH',
            headers: {
              'Content-Type': 'application/json',
              [current.headerName]: current.token,
            },
            body: JSON.stringify(patch),
          },
        );
      } catch (error) {
        if (
          attempt === 0 &&
          error instanceof ApiError &&
          error.status === 403
        ) {
          csrf = undefined;
          continue;
        }
        throw error;
      }
    }
  },
};
