import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { api, type Source } from '../api';

export function sourceResult(source: Source) {
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
export function useSources() {
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
export function useCollectionUpdates() {
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
  return sources;
}
