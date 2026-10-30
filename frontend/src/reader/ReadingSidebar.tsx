import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, type Filters } from '../api';
import { useSources, sourceResult } from '../sources/useSources';
import { Failure, date, demo } from './ui';
import styles from '../App.module.css';

export function ReadingSidebar({
  sources,
}: {
  sources: ReturnType<typeof useSources>;
}) {
  const [filters, setFilters] = useState<Filters>({});
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const cursor = cursors.at(-1);
  const articles = useQuery({
    queryKey: ['articles', filters, cursor],
    queryFn: () => api.articles({ ...filters, cursor }),
  });
  function filter(patch: Filters) {
    setFilters({ ...filters, ...patch });
    setCursors([undefined]);
  }
  return (
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
                value={filters.read === undefined ? '' : String(filters.read)}
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
                value={filters.saved === undefined ? '' : String(filters.saved)}
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
  );
}
