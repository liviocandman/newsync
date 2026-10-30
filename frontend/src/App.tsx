import { Link, Route, Routes } from 'react-router-dom';
import { SearchResults, SearchScreen } from './Search';
import { SourcesScreen } from './sources/SourcesScreen';
import { useCollectionUpdates } from './sources/useSources';
import { ReadingSidebar } from './reader/ReadingSidebar';
import { Reader } from './reader/Reader';
import { demo } from './reader/ui';
import styles from './App.module.css';

export function App() {
  const sources = useCollectionUpdates();
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
        <ReadingSidebar sources={sources} />
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
