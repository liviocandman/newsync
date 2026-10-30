import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api, type Source } from '../api';
import { useSources, sourceResult } from './useSources';
import { Failure } from '../reader/ui';
import styles from '../App.module.css';

export function SourcesScreen() {
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
