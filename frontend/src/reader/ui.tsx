import styles from '../App.module.css';
export const demo = import.meta.env.VITE_APP_MODE === 'demo';
export function date(value: string | null) {
  return value
    ? new Intl.DateTimeFormat('pt-BR', {
        day: '2-digit',
        month: 'short',
        timeZone: 'UTC',
      }).format(new Date(value))
    : 'Sem data';
}
export function Failure({ error, retry }: { error: Error; retry: () => void }) {
  return (
    <div role="alert" className={styles.error}>
      <p>{error.message}</p>
      <button onClick={retry}>Tentar novamente</button>
    </div>
  );
}
