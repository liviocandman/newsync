import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, type SearchFilters, type Source } from './api';
import styles from './App.module.css';

function localTime(value: string | null) {
  if (!value) return '';
  const time = new Date(value);
  if (!Number.isFinite(time.getTime())) return '';
  return new Date(time.getTime() - time.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 19);
}

export function SearchForm({
  params,
  sources,
  submit,
}: {
  params: URLSearchParams;
  sources: Source[];
  submit: (params: URLSearchParams) => void;
}) {
  const [q, setQ] = useState(params.get('q') ?? '');
  const [sourceId, setSourceId] = useState(params.get('sourceId') ?? '');
  const [from, setFrom] = useState(localTime(params.get('from')));
  const [to, setTo] = useState(localTime(params.get('to')));
  const [limit, setLimit] = useState(params.get('limit') ?? '30');
  function search(event: FormEvent) {
    event.preventDefault();
    const next = new URLSearchParams({ q: q.trim(), limit, offset: '0' });
    if (sourceId) next.set('sourceId', sourceId);
    if (from) next.set('from', new Date(from).toISOString());
    if (to) next.set('to', new Date(to).toISOString());
    submit(next);
  }
  return (
    <form className={styles.sourceForm} onSubmit={search}>
      <label>
        Termos da busca
        <input
          required
          value={q}
          onChange={(event) => setQ(event.target.value)}
        />
      </label>
      <label>
        Fonte da busca
        <select
          value={sourceId}
          onChange={(event) => setSourceId(event.target.value)}
        >
          <option value="">Todas as fontes</option>
          {sources.map((source) => (
            <option key={source.id} value={source.id}>
              {source.title}
              {source.state === 'archived' ? ' (arquivada)' : ''}
            </option>
          ))}
        </select>
      </label>
      <label>
        A partir de
        <input
          type="datetime-local"
          step="1"
          value={from}
          onChange={(event) => setFrom(event.target.value)}
        />
      </label>
      <label>
        Antes de
        <input
          type="datetime-local"
          step="1"
          value={to}
          onChange={(event) => setTo(event.target.value)}
        />
      </label>
      <label>
        Resultados por página
        <select
          value={limit}
          onChange={(event) => setLimit(event.target.value)}
        >
          {[10, 30, 100].map((size) => (
            <option key={size} value={size}>
              {size}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={!q.trim()}>
        Pesquisar
      </button>
    </form>
  );
}

export function SearchResults() {
  const [params, setParams] = useSearchParams();
  const filters: SearchFilters = {
    q: params.get('q') ?? '',
    sourceId: params.get('sourceId') ?? undefined,
    from: params.get('from') ?? undefined,
    to: params.get('to') ?? undefined,
    limit: Number(params.get('limit') ?? 30),
    offset: Number(params.get('offset') ?? 0),
  };
  const sources = useQuery({ queryKey: ['sources'], queryFn: api.sources });
  const results = useQuery({
    queryKey: ['search', filters],
    queryFn: ({ signal }) => api.search(filters, signal),
    enabled: params.has('q'),
    refetchOnWindowFocus: false,
  });
  function page(offset: number) {
    const next = new URLSearchParams(params);
    next.set('offset', String(offset));
    setParams(next);
  }
  return (
    <section className={styles.sourceManager}>
      <h2>Busca no acervo</h2>
      <p>
        A busca considera título e texto recebido do feed; pode ser apenas um
        trecho. As palavras são combinadas, sem correção, tradução ou busca por
        frase.
      </p>
      <p>
        O período considera a data de ordenação do acervo. Horários no seu fuso
        local; o limite final é exclusivo.
      </p>
      <SearchForm
        key={params.toString()}
        params={params}
        sources={sources.data ?? []}
        submit={(next) => {
          if (
            next.get('q') === filters.q &&
            (next.get('sourceId') ?? undefined) === filters.sourceId &&
            (next.get('from') ?? undefined) === filters.from &&
            (next.get('to') ?? undefined) === filters.to &&
            Number(next.get('limit')) === filters.limit &&
            filters.offset === 0
          )
            void results.refetch();
          if (next.toString() !== params.toString()) setParams(next);
        }}
      />
      {sources.isError && (
        <p role="alert">
          {sources.error.message}{' '}
          <button
            onClick={() => {
              void sources.refetch();
            }}
          >
            Recarregar fontes
          </button>
        </p>
      )}
      {!params.has('q') ? (
        <p>Informe palavras para reencontrar um artigo.</p>
      ) : results.isPending ? (
        <p role="status">Pesquisando…</p>
      ) : results.isError ? (
        <div role="alert" className={styles.error}>
          <p>{results.error.message}</p>
          <button
            onClick={() => {
              void results.refetch();
            }}
          >
            Repetir pesquisa
          </button>
        </div>
      ) : (
        <>
          {results.isFetching && <p role="status">Atualizando resultados…</p>}
          {results.data.items.length === 0 ? (
            <p>Nenhum resultado para esta pesquisa.</p>
          ) : (
            <nav aria-label="Resultados da busca">
              <ul className={styles.list}>
                {results.data.items.map((article) => (
                  <li key={article.id}>
                    <Link
                      className={styles.articleLink}
                      to={`/search/articles/${article.id}?${params}`}
                    >
                      <strong>{article.title}</strong>
                      <span>
                        {article.read ? 'Lido' : 'Não lido'} ·{' '}
                        {article.saved ? 'Salvo' : 'Não salvo'}
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>
          )}
          <div className={styles.pagination}>
            <button
              disabled={filters.offset === 0 || results.isFetching}
              onClick={() => page(Math.max(0, filters.offset - filters.limit))}
            >
              Resultados anteriores
            </button>
            <span>Página {Math.floor(filters.offset / filters.limit) + 1}</span>
            <button
              disabled={!results.data.hasMore || results.isFetching}
              onClick={() => page(filters.offset + filters.limit)}
            >
              Próximos resultados
            </button>
          </div>
          <p>O acervo pode mudar entre páginas.</p>
        </>
      )}
    </section>
  );
}
