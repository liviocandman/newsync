import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { api, type Article } from './api';
vi.mock('./api', () => ({
  api: {
    articles: vi.fn(),
    sources: vi.fn(),
    article: vi.fn(),
    state: vi.fn(),
  },
}));
const article: Article = {
  id: 'a',
  sourceId: 's',
  title: 'Texto de teste',
  publishedAt: null,
  read: false,
  saved: false,
  readAt: null,
  savedAt: null,
  originalUrl: null,
  contentHtml: '<p>Um texto próprio.</p>',
  contentText: 'Um texto próprio.',
  contentOrigin: 'feed',
  completeness: 'unknown',
};
function show(path = '/') {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
          },
        })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  vi.mocked(api.articles).mockResolvedValue({
    items: [article],
    nextCursor: null,
  });
  vi.mocked(api.sources).mockResolvedValue([]);
  vi.mocked(api.article).mockResolvedValue(article);
});
afterEach(() => vi.resetAllMocks());
it('mantém o estado anterior quando uma gravação falha', async () => {
  vi.mocked(api.state).mockRejectedValue(new Error('Banco indisponível.'));
  show('/articles/a');
  const read = await screen.findByRole('button', { name: 'Marcar como lido' });
  await userEvent.click(read);
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Não foi possível gravar',
  );
  expect(read).toHaveAttribute('aria-pressed', 'false');
  expect(
    screen.queryByRole('button', { name: 'Marcar como não lido' }),
  ).not.toBeInTheDocument();
});
it('abrir não marca lido e mostra a ausência de corpo', async () => {
  vi.mocked(api.article).mockResolvedValue({
    ...article,
    contentHtml: null,
    contentText: null,
  });
  show('/articles/a');
  expect(
    await screen.findByText('Este artigo veio sem corpo.'),
  ).toBeInTheDocument();
  expect(
    screen.getByText('Conteúdo recebido do feed; pode ser apenas um trecho.'),
  ).toBeInTheDocument();
  expect(api.state).not.toHaveBeenCalled();
});
it('mostra carregamento e lista vazia', async () => {
  let finish!: (value: { items: Article[]; nextCursor: null }) => void;
  vi.mocked(api.articles).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  show();
  expect(screen.getByText('Carregando artigos…')).toBeInTheDocument();
  finish({ items: [], nextCursor: null });
  expect(
    await screen.findByText('Nenhum artigo por aqui ainda.'),
  ).toBeInTheDocument();
});
it('expõe falha de leitura e permite repetir', async () => {
  vi.mocked(api.articles).mockRejectedValueOnce(
    new Error('Leitura indisponível.'),
  );
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Leitura indisponível.',
  );
  await userEvent.click(
    screen.getByRole('button', { name: 'Tentar novamente' }),
  );
  expect(
    await screen.findByRole('link', { name: /Texto de teste/ }),
  ).toBeInTheDocument();
});
