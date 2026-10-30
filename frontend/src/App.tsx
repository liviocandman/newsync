import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  Link,
  Route,
  Routes,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import {
  api,
  type Article,
  type Source,
  type State,
  type Filters,
} from './api';
import { SearchResults } from './Search';
import styles from './App.module.css';

const demo = import.meta.env.VITE_APP_MODE === 'demo';
function date(value: string | null) {
  return value
    ? new Intl.DateTimeFormat('pt-BR', {
        day: '2-digit',
        month: 'short',
        timeZone: 'UTC',
      }).format(new Date(value))
    : 'Sem data';
}
function Failure({ error, retry }: { error: Error; retry: () => void }) {
  return (
    <div role="alert" className={styles.error}>
      <p>{error.message}</p>
      <button onClick={retry}>Tentar novamente</button>
    </div>
  );
}
function sourceResult(source: Source) {
  if (source.collecting) return 'Coletando; aguarde o resultado.';
  if (source.manualRequestedAt)
    return 'Importação solicitada; aguardando resultado.';
  return {
    never: 'Pendente; nenhuma importação concluída.',
    success: 'Importação concluída.',
    not_modified: 'Feed sem alterações.',
    partial: 'Importação parcial; alguns itens foram ignorados.',
    failed: 'A importação falhou.',
  }[source.lastResult];
}
function useSources() {
  const [visible, setVisible] = useState(document.visibilityState !== 'hidden');
  const client = useQueryClient();
  useEffect(() => {
    const visibility = () => {
      const shown = document.visibilityState !== 'hidden';
      setVisible(shown);
      if (shown) void client.invalidateQueries({ queryKey: ['sources'] });
    };
    document.addEventListener('visibilitychange', visibility);
    return () => document.removeEventListener('visibilitychange', visibility);
  }, [client]);
  return useQuery({
    queryKey: ['sources'],
    queryFn: api.sources,
    refetchOnWindowFocus: 'always',
    refetchInterval: (query) =>
      visible && query.state.data?.some((source) => source.collectionEnabled)
        ? 5000
        : false,
    refetchIntervalInBackground: false,
  });
}
export function App() {
  const [filters, setFilters] = useState<Filters>({});
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const cursor = cursors.at(-1);
  const articles = useQuery({
    queryKey: ['articles', filters, cursor],
    queryFn: () => api.articles({ ...filters, cursor }),
  });
  const sources = useSources();
  const client = useQueryClient();
  const sourceResults = useRef(new Map<string, string | null>());
  const sourcesInitialized = useRef(false);
  useEffect(() => {
    let changed = false;
    for (const source of sources.data ?? []) {
      if (source.collecting || source.manualRequestedAt) {
        if (!sourceResults.current.has(source.id))
          sourceResults.current.set(source.id, null);
        continue;
      }
      const signature = `${source.lastResult}/${source.lastSuccessAt}/${source.nextPollAt}`;
      if (
        (sourceResults.current.has(source.id) &&
          sourceResults.current.get(source.id) !== signature) ||
        (!sourceResults.current.has(source.id) &&
          sourcesInitialized.current &&
          source.lastResult !== 'never')
      )
        changed = true;
      sourceResults.current.set(source.id, signature);
    }
    if (sources.data) sourcesInitialized.current = true;
    if (changed) {
      void client.invalidateQueries({ queryKey: ['articles'] });
      void client.invalidateQueries({ queryKey: ['article'] });
      void client.invalidateQueries({ queryKey: ['search'] });
    }
  }, [sources.data, client]);
  function filter(patch: Filters) {
    setFilters({ ...filters, ...patch });
    setCursors([undefined]);
  }
  return (
    <div className={styles.app}>
      <header className={styles.header}>
        <Link to="/" className={styles.brand}>
          caderno<span>um tempo para ler</span>
        </Link>
        <span className={styles.demo}>
          {demo ? 'Demonstração local · B-00' : 'Leitor pessoal local'}
        </span>
      </header>
      <div className={styles.workspace}>
        <aside className={styles.sidebar} aria-label="Artigos">
          <div className={styles.sectionTitle}>
            <span>CAIXA DE LEITURA</span>
            <span>{articles.data?.items.length ?? '—'}</span>
          </div>
          <h1>Pequenas descobertas.</h1>
          <p className={styles.intro}>
            {demo
              ? 'Textos para experimentar seu leitor.'
              : 'Suas fontes, no seu tempo.'}
          </p>
          <Link className={styles.manageLink} to="/search">
            Buscar no acervo
          </Link>
          {sources.isPending ? (
            <p role="status">Carregando fontes…</p>
          ) : sources.isError ? (
            <Failure
              error={sources.error}
              retry={() => {
                void sources.refetch();
              }}
            />
          ) : (
            <>
              {!demo && (
                <Link className={styles.manageLink} to="/sources">
                  Gerenciar fontes
                </Link>
              )}
              <div className={styles.filters}>
                <label>
                  Fonte
                  <select
                    aria-label="Filtrar por fonte"
                    value={filters.sourceId ?? ''}
                    onChange={(event) =>
                      filter({ sourceId: event.target.value || undefined })
                    }
                  >
                    <option value="">Todas as fontes</option>
                    {sources.data.map((source) => (
                      <option key={source.id} value={source.id}>
                        {source.title}
                        {source.state === 'archived' ? ' (arquivada)' : ''}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Leitura
                  <select
                    aria-label="Filtrar por leitura"
                    value={
                      filters.read === undefined ? '' : String(filters.read)
                    }
                    onChange={(event) =>
                      filter({
                        read:
                          event.target.value === ''
                            ? undefined
                            : event.target.value === 'true',
                      })
                    }
                  >
                    <option value="">Todos os artigos</option>
                    <option value="false">Não lidos</option>
                    <option value="true">Lidos</option>
                  </select>
                </label>
                <label>
                  Salvos
                  <select
                    aria-label="Filtrar por salvos"
                    value={
                      filters.saved === undefined ? '' : String(filters.saved)
                    }
                    onChange={(event) =>
                      filter({
                        saved:
                          event.target.value === ''
                            ? undefined
                            : event.target.value === 'true',
                      })
                    }
                  >
                    <option value="">Todos</option>
                    <option value="true">Salvos</option>
                    <option value="false">Não salvos</option>
                  </select>
                </label>
              </div>
              {demo &&
                sources.data.map((source) => (
                  <p key={source.id} className={styles.source}>
                    {source.title}
                    <span>{sourceResult(source)}</span>
                  </p>
                ))}
            </>
          )}
          {articles.isPending ? (
            <p role="status">Carregando artigos…</p>
          ) : articles.isError ? (
            <Failure
              error={articles.error}
              retry={() => {
                void articles.refetch();
              }}
            />
          ) : articles.data.items.length === 0 ? (
            <p>Nenhum artigo por aqui ainda.</p>
          ) : (
            <nav aria-label="Lista de artigos">
              <ul className={styles.list}>
                {articles.data.items.map((article) => (
                  <li key={article.id}>
                    <Link
                      to={`/articles/${article.id}`}
                      className={styles.articleLink}
                    >
                      <span className={styles.date}>
                        {date(article.publishedAt)} ·{' '}
                        {article.read ? 'Lido' : 'Não lido'}
                      </span>
                      <strong>{article.title}</strong>
                      <span className={styles.articleMeta}>
                        {article.saved
                          ? 'Salvo na sua coleção'
                          : demo
                            ? 'Caderno de demonstração'
                            : (sources.data?.find(
                                (source) => source.id === article.sourceId,
                              )?.title ?? 'Recebido do feed')}
                        <span aria-hidden="true">↗</span>
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>
          )}
          {(cursors.length > 1 || articles.data?.nextCursor) && (
            <div className={styles.pagination}>
              <button
                disabled={cursors.length === 1 || articles.isFetching}
                onClick={() => setCursors(cursors.slice(0, -1))}
              >
                Página anterior
              </button>
              <span>Página {cursors.length}</span>
              <button
                disabled={!articles.data?.nextCursor || articles.isFetching}
                onClick={() => {
                  if (articles.data?.nextCursor)
                    setCursors([...cursors, articles.data.nextCursor]);
                }}
              >
                Próxima página
              </button>
            </div>
          )}
          <p className={styles.footnote}>
            {demo
              ? 'Dados sintéticos. As marcações ficam neste banco local.'
              : 'As marcações ficam nesta instalação. Fontes ativas podem receber artigos automaticamente quando a coleta está habilitada.'}
          </p>
        </aside>
        <main className={styles.main}>
          <Routes>
            <Route path="/search" element={<SearchResults />} />
            <Route path="/search/articles/:id" element={<SearchScreen />} />
            <Route path="/articles/:id" element={<Reader />} />
            {!demo && <Route path="/sources" element={<SourcesScreen />} />}
            <Route
              path="/"
              element={
                <div className={styles.welcome}>
                  <span className={styles.eyebrow}>SEU ESPAÇO DE LEITURA</span>
                  <h2>
                    Uma pausa.
                    <br />
                    Uma nova ideia.
                  </h2>
                  <p>
                    Escolha um texto ao lado para começar.
                    <br />
                    Você decide o que marcar como lido ou guardar.
                  </p>
                  {!demo && (
                    <Link className={styles.manageLink} to="/sources">
                      Adicionar uma fonte
                    </Link>
                  )}
                </div>
              }
            />
            <Route path="*" element={<h2>Página não encontrada.</h2>} />
          </Routes>
        </main>
      </div>
    </div>
  );
}
function SearchScreen() {
  const [params] = useSearchParams();
  return (
    <>
      <Link to={`/search?${params}`}>Voltar à busca</Link>
      <Reader />
    </>
  );
}
function SourcesScreen() {
  const client = useQueryClient();
  const sources = useSources();
  const [feedUrl, setFeedUrl] = useState('');
  const [title, setTitle] = useState('');
  const creation = useMutation({
    mutationFn: () =>
      api.createSource({
        feedUrl: feedUrl.trim(),
        ...(title.trim() ? { title: title.trim() } : {}),
      }),
    onSuccess: async () => {
      setFeedUrl('');
      setTitle('');
      await client.invalidateQueries({ queryKey: ['sources'] });
    },
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    creation.mutate();
  }
  return (
    <section className={styles.sourceManager}>
      <span className={styles.eyebrow}>SEU ACERVO LOCAL</span>
      <h2>Fontes de leitura</h2>
      <p>
        Cadastre o endereço RSS ou Atom. Com a coleta habilitada, fontes ativas
        podem importar no próximo ciclo, inclusive após reativação. Importar
        agora pede prioridade entre tentativas e respeita o tempo de espera da
        fonte.
      </p>
      <form className={styles.sourceForm} onSubmit={submit}>
        <label>
          URL do feed
          <input
            name="feedUrl"
            type="url"
            required
            value={feedUrl}
            placeholder="https://exemplo.org/feed.xml"
            onChange={(event) => setFeedUrl(event.target.value)}
          />
        </label>
        <label>
          Título (opcional)
          <input
            name="title"
            value={title}
            maxLength={300}
            onChange={(event) => setTitle(event.target.value)}
          />
        </label>
        <button disabled={creation.isPending} type="submit">
          {creation.isPending ? 'Cadastrando…' : 'Cadastrar fonte'}
        </button>
      </form>
      {creation.isSuccess && (
        <p role="status">
          Fonte cadastrada. A coleta habilitada poderá importar automaticamente
          no próximo ciclo.
        </p>
      )}
      {creation.isError && (
        <p role="alert" className={styles.error}>
          {creation.error.message}
          {'code' in creation.error &&
            creation.error.code === 'SOURCE_EXISTS' &&
            ' A URL já pertence a uma fonte desta instalação. Encontre-a abaixo e use Reativar se estiver pausada ou arquivada.'}
        </p>
      )}
      {sources.isPending ? (
        <p role="status">Carregando fontes…</p>
      ) : sources.isError ? (
        <Failure
          error={sources.error}
          retry={() => {
            void sources.refetch();
          }}
        />
      ) : sources.data.length === 0 ? (
        <p>Nenhuma fonte cadastrada.</p>
      ) : (
        <ul className={styles.sourceList}>
          {sources.data.map((source) => (
            <SourceCard key={source.id} source={source} />
          ))}
        </ul>
      )}
    </section>
  );
}
function SourceCard({ source }: { source: Source }) {
  const client = useQueryClient();
  const [renaming, setRenaming] = useState(false);
  const [title, setTitle] = useState(source.title);
  const action = useMutation({
    mutationFn: async (
      command: 'refresh' | 'pause' | 'activate' | 'archive' | 'rename',
    ): Promise<void> => {
      if (command === 'refresh') {
        await api.refreshSource(source.id);
        return;
      }
      if (command === 'archive') {
        await api.archiveSource(source.id);
        return;
      }
      await api.updateSource(
        source.id,
        command === 'rename'
          ? { title: title.trim() }
          : { state: command === 'pause' ? 'paused' : 'active' },
      );
    },
    onSuccess: async (_, command) => {
      if (command === 'rename') setRenaming(false);
      await Promise.all([
        client.invalidateQueries({ queryKey: ['sources'] }),
        client.invalidateQueries({ queryKey: ['articles'] }),
      ]);
    },
  });
  return (
    <li className={styles.sourceCard} aria-label={`Fonte ${source.title}`}>
      <div className={styles.sourceHeading}>
        <h3>{source.title}</h3>
        <span>
          {
            { active: 'Ativa', paused: 'Pausada', archived: 'Arquivada' }[
              source.state
            ]
          }
        </span>
      </div>
      <p className={styles.feedUrl}>{source.feedUrl}</p>
      <p
        role={
          source.manualRequestedAt || source.collecting ? 'status' : undefined
        }
      >
        {sourceResult(source)}
      </p>
      {!source.collectionEnabled && (
        <p>Coleta desabilitada nesta instalação.</p>
      )}
      {source.state !== 'active' && (
        <p>
          Atualização suspensa enquanto a fonte estiver{' '}
          {source.state === 'paused' ? 'pausada' : 'arquivada'}.
        </p>
      )}
      {source.diagnostic && (
        <p className={styles.error}>
          {source.diagnostic.message} Itens ignorados:{' '}
          {source.diagnostic.ignoredItems}.
        </p>
      )}
      <p className={styles.sourceTimestamp}>
        Falhas consecutivas: {source.consecutiveFailures}.
      </p>
      <p className={styles.sourceTimestamp}>
        Último resultado:{' '}
        {
          {
            never: 'Pendente',
            success: 'Sucesso',
            not_modified: 'Sem alterações',
            partial: 'Parcial',
            failed: 'Falha',
          }[source.lastResult]
        }
        .
      </p>
      <p className={styles.sourceTimestamp}>
        Última tentativa:{' '}
        {source.lastAttemptAt
          ? new Date(source.lastAttemptAt).toLocaleString('pt-BR')
          : 'Nenhuma'}
        .
      </p>
      <p className={styles.sourceTimestamp}>
        Último sucesso:{' '}
        {source.lastSuccessAt
          ? new Date(source.lastSuccessAt).toLocaleString('pt-BR')
          : 'Nenhum'}
        .
      </p>
      {source.collectionEnabled && source.state === 'active' && (
        <p className={styles.sourceTimestamp}>
          Próxima elegibilidade:{' '}
          {new Date(
            Math.max(
              new Date(source.nextPollAt).getTime(),
              source.retryAfterAt ? new Date(source.retryAfterAt).getTime() : 0,
            ),
          ).toLocaleString('pt-BR')}
          . Não é previsão de conclusão; outras fontes podem estar aguardando.
        </p>
      )}
      {source.retryAfterAt && (
        <p>
          Espera solicitada pela fonte até{' '}
          {new Date(source.retryAfterAt).toLocaleString('pt-BR')}.
        </p>
      )}
      {renaming && (
        <form
          className={styles.renameForm}
          onSubmit={(event) => {
            event.preventDefault();
            action.mutate('rename');
          }}
        >
          <label>
            Novo título
            <input
              value={title}
              maxLength={300}
              required
              onChange={(event) => setTitle(event.target.value)}
            />
          </label>
          <button disabled={action.isPending || !title.trim()} type="submit">
            Confirmar título
          </button>
          <button
            disabled={action.isPending}
            type="button"
            onClick={() => setRenaming(false)}
          >
            Cancelar
          </button>
        </form>
      )}
      <div className={styles.sourceActions}>
        {source.state === 'active' && (
          <>
            <button
              disabled={
                action.isPending ||
                !!source.manualRequestedAt ||
                source.collecting
              }
              onClick={() => action.mutate('refresh')}
            >
              {source.collecting
                ? 'Coletando…'
                : source.manualRequestedAt
                  ? 'Importação solicitada'
                  : 'Importar agora'}
            </button>
            <button
              disabled={action.isPending}
              onClick={() => action.mutate('pause')}
            >
              Pausar
            </button>
          </>
        )}
        {source.state !== 'active' && (
          <button
            disabled={action.isPending}
            onClick={() => action.mutate('activate')}
          >
            Reativar
          </button>
        )}
        {source.state !== 'archived' && (
          <button
            disabled={action.isPending || renaming}
            onClick={() => {
              setTitle(source.title);
              setRenaming(true);
            }}
          >
            Renomear
          </button>
        )}
        {source.state !== 'archived' && (
          <button
            disabled={action.isPending}
            onClick={() => action.mutate('archive')}
          >
            Arquivar
          </button>
        )}
      </div>
      {action.isPending && <p role="status">Enviando…</p>}
      {action.isSuccess && action.variables === 'refresh' && (
        <p role="status">
          Pedido de importação aceito. O resultado aparecerá nesta fonte.
        </p>
      )}
      {action.isError && (
        <p role="alert" className={styles.error}>
          {action.error.message}
          {'retryAfter' in action.error &&
            typeof action.error.retryAfter === 'string' &&
            ` Aguarde o prazo informado: ${action.error.retryAfter}.`}
        </p>
      )}
    </li>
  );
}
function Reader() {
  const { id = '' } = useParams();
  return <ArticleReader key={id} id={id} />;
}
function ArticleReader({ id }: { id: string }) {
  const client = useQueryClient();
  const detail = useQuery({
    queryKey: ['article', id],
    queryFn: () => api.article(id),
  });
  const mutation = useMutation({
    mutationFn: (patch: Partial<Pick<State, 'read' | 'saved'>>) =>
      api.state(id, patch),
    onSuccess: async (state) => {
      client.setQueryData<Article>(['article', id], (previous) =>
        previous ? { ...previous, ...state } : previous,
      );
      await Promise.all([
        client.invalidateQueries({ queryKey: ['articles'] }),
        client.invalidateQueries({ queryKey: ['search'] }),
        client.invalidateQueries({ queryKey: ['article', id] }),
      ]);
    },
  });
  if (detail.isPending)
    return (
      <p role="status" className={styles.loading}>
        Abrindo artigo…
      </p>
    );
  if (detail.isError)
    return (
      <Failure
        error={detail.error}
        retry={() => {
          void detail.refetch();
        }}
      />
    );
  const article = detail.data;
  return (
    <article className={styles.reader} key={id}>
      <div className={styles.readerTop}>
        <span className={styles.eyebrow}>
          {demo ? 'CADERNO DE DEMONSTRAÇÃO' : 'RECEBIDO DO FEED'}
        </span>
        <span>{date(article.publishedAt)}</span>
      </div>
      <h2>{article.title}</h2>
      <p className={styles.disclosure}>
        Conteúdo recebido do feed; pode ser apenas um trecho.
      </p>
      <div className={styles.actions}>
        <button
          aria-pressed={article.read}
          disabled={mutation.isPending}
          onClick={() => mutation.mutate({ read: !article.read })}
        >
          {article.read ? 'Marcar como não lido' : 'Marcar como lido'}
        </button>
        <button
          aria-pressed={article.saved}
          disabled={mutation.isPending}
          onClick={() => mutation.mutate({ saved: !article.saved })}
        >
          {article.saved ? 'Remover dos salvos' : 'Salvar artigo'}
        </button>
        {mutation.isPending && <span role="status">Gravando…</span>}
      </div>
      {mutation.isError && (
        <p role="alert" className={styles.error}>
          Não foi possível gravar a marcação. {mutation.error.message}
        </p>
      )}
      {article.contentHtml ? (
        <div
          data-testid="article-content"
          className={styles.content}
          dangerouslySetInnerHTML={{ __html: article.contentHtml }}
        />
      ) : (
        <div className={styles.emptyBody}>
          <h3>Este artigo veio sem corpo.</h3>
          <p>
            O feed disponibilizou apenas o título e o link. Você ainda pode
            salvar ou marcar este artigo.
          </p>
        </div>
      )}
      {article.originalUrl && (
        <a
          className={styles.original}
          href={article.originalUrl}
          target="_blank"
          rel="noopener noreferrer"
        >
          Abrir link original <span aria-hidden="true">↗</span>
        </a>
      )}
      <p className={styles.readNote}>
        Abrir um artigo não altera a marcação de leitura.
      </p>
    </article>
  );
}
