import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { api, type Article, type State } from '../api';
import { Failure, date, demo } from './ui';
import styles from '../App.module.css';

export function Reader() {
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
