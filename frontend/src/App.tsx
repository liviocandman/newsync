import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Route, Routes, useParams } from 'react-router-dom';
import { api, type Article, type State } from './api';
import styles from './App.module.css';

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
export function App() {
  const articles = useQuery({ queryKey: ['articles'], queryFn: api.articles });
  const sources = useQuery({ queryKey: ['sources'], queryFn: api.sources });
  return (
    <div className={styles.app}>
      <header className={styles.header}>
        <Link to="/" className={styles.brand}>
          caderno<span>um tempo para ler</span>
        </Link>
        <span className={styles.demo}>Demonstração local · B-00</span>
      </header>
      <div className={styles.workspace}>
        <aside className={styles.sidebar} aria-label="Artigos">
          <div className={styles.sectionTitle}>
            <span>CAIXA DE LEITURA</span>
            <span>{articles.data?.items.length ?? '—'}</span>
          </div>
          <h1>Pequenas descobertas.</h1>
          <p className={styles.intro}>Textos para experimentar seu leitor.</p>
          {sources.isPending ? (
            <p role="status">Carregando fonte…</p>
          ) : sources.isError ? (
            <Failure
              error={sources.error}
              retry={() => {
                void sources.refetch();
              }}
            />
          ) : (
            sources.data.map((source) => (
              <p key={source.id} className={styles.source}>
                {source.title}
                <span>
                  {source.lastResult === 'success'
                    ? 'Atualizada'
                    : 'Aguardando atualização'}
                </span>
              </p>
            ))
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
            <nav>
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
                          : 'Caderno de demonstração'}
                        <span aria-hidden="true">↗</span>
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>
          )}
          <p className={styles.footnote}>
            Dados sintéticos. As marcações ficam neste banco local.
          </p>
        </aside>
        <main className={styles.main}>
          <Routes>
            <Route path="/articles/:id" element={<Reader />} />
            <Route
              path="*"
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
                </div>
              }
            />
          </Routes>
        </main>
      </div>
    </div>
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
        <span className={styles.eyebrow}>CADERNO DE DEMONSTRAÇÃO</span>
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
