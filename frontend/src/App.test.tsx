import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { api, type Article, type Source } from './api';
vi.mock('./api', () => ({
  api: {
    articles: vi.fn(),
    sources: vi.fn(),
    article: vi.fn(),
    state: vi.fn(),
    createSource: vi.fn(),
    updateSource: vi.fn(),
    archiveSource: vi.fn(),
    refreshSource: vi.fn(),
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
const source: Source = {
  id: 's',
  title: 'Minha fonte',
  feedUrl: 'https://example.org/rss',
  state: 'active',
  lastResult: 'never',
  lastSuccessAt: null,
  lastAttemptAt: null,
  manualRequestedAt: null,
  retryAfterAt: null,
  diagnostic: null,
  nextPollAt: '2026-10-29T12:00:00Z',
  consecutiveFailures: 0,
  collecting: false,
  collectionEnabled: true,
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
it('cadastro confirma a fonte pendente sem solicitar importação', async () => {
  vi.mocked(api.createSource).mockImplementation(async () => {
    vi.mocked(api.sources).mockResolvedValue([source]);
    return source;
  });
  show('/sources');
  expect(await screen.findByText('Leitor pessoal local')).toBeInTheDocument();
  await userEvent.type(
    screen.getByLabelText('URL do feed'),
    'https://example.org/rss',
  );
  await userEvent.type(
    screen.getByLabelText('Título (opcional)'),
    'Minha fonte',
  );
  await userEvent.click(
    screen.getByRole('button', { name: 'Cadastrar fonte' }),
  );
  expect(
    await screen.findByText(
      'Fonte cadastrada. A coleta habilitada poderá importar automaticamente no próximo ciclo.',
    ),
  ).toBeInTheDocument();
  expect(
    await screen.findByText('Pendente; nenhuma importação concluída.'),
  ).toBeInTheDocument();
  expect(api.createSource).toHaveBeenCalledWith({
    feedUrl: 'https://example.org/rss',
    title: 'Minha fonte',
  });
  expect(api.refreshSource).not.toHaveBeenCalled();
});
it('falha ao pausar conserva a fonte ativa e permite uma nova ação', async () => {
  vi.mocked(api.sources).mockResolvedValue([source]);
  vi.mocked(api.updateSource).mockRejectedValue(
    new Error('Gravação indisponível.'),
  );
  show('/sources');
  await userEvent.click(await screen.findByRole('button', { name: 'Pausar' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Gravação indisponível.',
  );
  expect(screen.getByText('Ativa', { exact: true })).toBeInTheDocument();
  expect(
    screen.queryByRole('button', { name: 'Reativar' }),
  ).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Pausar' })).toBeEnabled();
});
it('filtro novo descarta cursor anterior e usa valores declarativos', async () => {
  vi.mocked(api.articles).mockResolvedValue({
    items: [article],
    nextCursor: 'page-2',
  });
  show();
  await userEvent.click(
    await screen.findByRole('button', { name: 'Próxima página' }),
  );
  expect(api.articles).toHaveBeenLastCalledWith({ cursor: 'page-2' });
  await userEvent.selectOptions(
    screen.getByLabelText('Filtrar por leitura'),
    'false',
  );
  expect(api.articles).toHaveBeenLastCalledWith({
    read: false,
    cursor: undefined,
  });
  expect(screen.getByText('Página 1', { exact: true })).toBeInTheDocument();
});

it('acompanha automática e invalida lista e detalhe uma vez por conclusão parcial', async () => {
  vi.useFakeTimers();
  try {
    vi.mocked(api.sources).mockResolvedValue([source]);
    show('/articles/a');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(api.article).toHaveBeenCalledTimes(1);
    const before = vi.mocked(api.articles).mock.calls.length;
    vi.mocked(api.sources).mockResolvedValue([
      { ...source, collecting: true, lastAttemptAt: '2026-10-29T12:00:00Z' },
    ]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5100);
    });
    expect(api.articles).toHaveBeenCalledTimes(before);
    vi.mocked(api.sources).mockResolvedValue([
      {
        ...source,
        lastAttemptAt: '2026-10-29T12:00:00Z',
        lastResult: 'partial',
        diagnostic: {
          code: 'PARTIAL_FEED',
          message: 'Parcial',
          ignoredItems: 1,
        },
      },
    ]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5100);
    });
    expect(api.articles).toHaveBeenCalledTimes(before + 1);
    expect(api.article).toHaveBeenCalledTimes(2);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10100);
    });
    expect(api.articles).toHaveBeenCalledTimes(before + 1);
    expect(api.article).toHaveBeenCalledTimes(2);
    // An unconfirmed attempt changes only lastAttemptAt; it must not look like a new result.
    vi.mocked(api.sources).mockResolvedValue([
      {
        ...source,
        lastResult: 'partial',
        lastAttemptAt: '2026-10-29T12:01:00Z',
        diagnostic: {
          code: 'PARTIAL_FEED',
          message: 'Parcial',
          ignoredItems: 1,
        },
      },
    ]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5100);
    });
    expect(api.article).toHaveBeenCalledTimes(2);
  } finally {
    vi.useRealTimers();
  }
});

it('suspende consultas na aba oculta e relê diagnóstico ao recuperar visibilidade', async () => {
  vi.useFakeTimers();
  try {
    vi.mocked(api.sources).mockResolvedValue([source]);
    show('/sources');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      value: 'hidden',
    });
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    const before = vi.mocked(api.sources).mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20000);
    });
    expect(api.sources).toHaveBeenCalledTimes(before);
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      value: 'visible',
    });
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(vi.mocked(api.sources).mock.calls.length).toBeGreaterThan(before);
  } finally {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      value: 'visible',
    });
    vi.useRealTimers();
  }
});

it('coleta desabilitada explicita o estado e não mantém polling', async () => {
  vi.useFakeTimers();
  try {
    vi.mocked(api.sources).mockResolvedValue([
      { ...source, collectionEnabled: false },
    ]);
    show('/sources');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(
      screen.getByText('Coleta desabilitada nesta instalação.'),
    ).toBeInTheDocument();
    const before = vi.mocked(api.sources).mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20000);
    });
    expect(api.sources).toHaveBeenCalledTimes(before);
    expect(
      screen.queryByText(/Próxima elegibilidade:/),
    ).not.toBeInTheDocument();
  } finally {
    vi.useRealTimers();
  }
});

it('mostra atividade, aviso parcial e o maior horário de elegibilidade', async () => {
  vi.mocked(api.sources).mockResolvedValue([
    {
      ...source,
      collecting: true,
      lastResult: 'partial',
      consecutiveFailures: 2,
      retryAfterAt: '2026-10-29T14:00:00Z',
      diagnostic: {
        code: 'PARTIAL_FEED',
        message: 'Alguns itens foram ignorados.',
        ignoredItems: 3,
      },
    },
  ]);
  show('/sources');
  expect(
    await screen.findByText('Coletando; aguarde o resultado.'),
  ).toBeInTheDocument();
  expect(screen.getByText(/Itens ignorados: 3/)).toBeInTheDocument();
  expect(screen.getByText('Falhas consecutivas: 2.')).toBeInTheDocument();
  expect(screen.getByText(/Próxima elegibilidade:/)).toHaveTextContent(
    new Date('2026-10-29T14:00:00Z').toLocaleString('pt-BR'),
  );
  expect(screen.getByRole('button', { name: 'Coletando…' })).toBeDisabled();
});

it('refresh recusado conserva diagnóstico e não confirma pedido', async () => {
  vi.mocked(api.sources).mockResolvedValue([source]);
  vi.mocked(api.refreshSource).mockRejectedValue(
    new Error('Aguarde antes de solicitar nova importação.'),
  );
  show('/sources');
  await userEvent.click(
    await screen.findByRole('button', { name: 'Importar agora' }),
  );
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Aguarde antes de solicitar',
  );
  expect(
    screen.queryByText(
      'Pedido de importação aceito. O resultado aparecerá nesta fonte.',
    ),
  ).not.toBeInTheDocument();
  expect(
    screen.getByText('Pendente; nenhuma importação concluída.'),
  ).toBeInTheDocument();
});

it('atualiza o leitor quando a primeira consulta da fonte nova já observa conclusão', async () => {
  vi.mocked(api.createSource).mockImplementation(async () => {
    vi.mocked(api.sources).mockResolvedValue([
      { ...source, lastResult: 'success' },
    ]);
    return source;
  });
  show('/sources');
  await screen.findByText('Nenhuma fonte cadastrada.');
  const before = vi.mocked(api.articles).mock.calls.length;
  await userEvent.type(
    screen.getByLabelText('URL do feed'),
    'https://example.org/rss',
  );
  await userEvent.click(
    screen.getByRole('button', { name: 'Cadastrar fonte' }),
  );
  await screen.findByText('Importação concluída.');
  expect(api.articles).toHaveBeenCalledTimes(before + 1);
});
