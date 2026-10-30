# Caderno — agregador de feeds

Leitor pessoal local de RSS/Atom, sem login: cadastre uma fonte e leia ou abra o original. Fontes ativas são atualizadas automaticamente com a coleta habilitada. IDs e marcações de lido/salvo persistem no PostgreSQL. B-03 passou o gate local completo; [resultados e limites](docs/evidencias/B-03.md).

## Instalação empacotada

B-08 inclui UI no JAR, imagem Linux amd64 e Compose app/db. O pacote local gerado pelo gate em `artifacts/<execução>/distribution/` contém a imagem, seu hash/identidade e os auxiliares. O operador usa Docker Engine >=28, Compose e Python 3.11+, sem JDK/Node no host. Siga o [guia de instalação, atualização e recuperação](scripts/install-guide.md), com projeto/volume novos e senha em arquivo privado. A reprodução por outra pessoa ainda é necessária para o aceite completo de B-08.

## Iniciar o leitor pessoal em desenvolvimento

Pré-requisitos: JDK 25, Node 24.15–24.x, Docker com daemon ativo e Compose. Execute os comandos em Linux/WSL. A primeira instalação baixa dependências e imagens.

Na raiz, copie a configuração e altere as duas entradas de senha para o mesmo valor local:

```bash
cp .env.local.example .env.local
docker compose --env-file .env.local -f compose.local.yaml up -d --wait db
cd backend
./mvnw clean package
cd ..
set -a
. ./.env.local
set +a
java -jar backend/target/news.jar
```

Em outro terminal:

```bash
cd frontend
npm ci
npm run dev
```

Abra [http://127.0.0.1:5173](http://127.0.0.1:5173), entre em **Gerenciar fontes**, cadastre a URL RSS/Atom. A fonte nasce ativa e pode coletar no próximo ciclo, sem aba aberta. **Importar agora** pede prioridade entre tentativas; o pedido recebe confirmação antes do download e respeita cooldown/Retry-After. Acompanhe resultado, falhas e próxima elegibilidade na fonte; o horário não prevê conclusão. É possível renomear, pausar, reativar e arquivar, preservando o acervo. URLs são imutáveis; duplicadas, inclusive arquivadas, oferecem reativação da fonte existente. A lista aceita filtros de fonte/lido/salvo e navegação por páginas.

A origem do navegador precisa corresponder exatamente a `APP_BASE_URL`. Backend e frontend ficam em loopback. Abrir um artigo não marca lido; o conteúdo pode ser só resumo ou título/link. Nenhum recurso remoto é carregado para completar o texto. O transporte aceita feeds públicos HTTP/HTTPS e recusa destinos internos; uma URL cadastrada ainda pode falhar na primeira importação.

O Maven gera a UI normal ou demo pelo lockfile antes de empacotar o respectivo JAR, sem depender de `dist` anterior. Para usar a UI do JAR sem Vite, após carregar o ambiente JDBC, execute `APP_BASE_URL=http://127.0.0.1:8080 java -jar backend/target/news.jar` e abra essa origem. O quickstart com Vite mantém `APP_BASE_URL` em 5173 explicitamente.

O banco pessoal `news_personal` usa porta **5434** e volume exclusivo no projeto Compose `news-personal-local`. Para pará-lo preservando o volume, use `docker compose --env-file .env.local -f compose.local.yaml down`; para retomar, repita o `up`. O perfil normal não importa fixture. Ao iniciar ou reativar, fontes ativas vencidas e pedidos manuais elegíveis podem coletar. A agenda e Retry-After persistem; o restart não reproduz ticks perdidos. Para inspecionar uma instalação B-03 antes de permitir rede, acrescente `--app.collection.enabled=false` ao comando Java. A interface indica a coleta desabilitada; pedidos manuais podem ficar pendentes até habilitá-la.

## Backup e restauração

B-06 acrescenta `python3 scripts/backup.py backup|restore`, com PostgreSQL 18 e ferramentas da imagem. Pare e aguarde o Java, mantenha o banco ligado e guarde o pacote custom/manifesto fora do checkout, em diretório Linux privado. O auxiliar confere hashes, histórico Flyway e dados completos; restaura somente em banco novo, sem sobrescrever a origem. O guia inclui [comandos completos para a instalação pessoal](scripts/backup-guide.md).

Use o JAR de hash correspondente, inicie o destino com `--app.collection.enabled=false` e confira leitor, marcações e busca antes de habilitar coleta. Não use perfil demo, Flyway repair ou troca de JAR para desfazer schema. Artigos/marcações posteriores à captura não reaparecem. Frequência, retenção e cópia em outro disco são escolhas manuais, sem automação entregue. O T-20 integra `./scripts/verify`, com dados sintéticos e sem arquivar dumps brutos.

## Iniciar a demonstração

Pré-requisitos: JDK 25, Node 24 (mínimo 24.15; patch testado no guia), Docker com daemon ativo e Compose. Execute os comandos em Linux/WSL. A primeira instalação baixa dependências e imagens.

Na raiz:

```bash
cp .env.example .env
docker compose up -d --wait db
cd backend
./mvnw -Pdemo clean package
cd ..
set -a
. ./.env
set +a
java -jar backend/target/news.jar --spring.profiles.active=demo
```

Em outro terminal:

```bash
cd frontend
npm ci
VITE_APP_MODE=demo npm run dev
```

Abra [http://127.0.0.1:5173](http://127.0.0.1:5173). Use essa origem exatamente: a proteção CSRF/Origin exige correspondência com `APP_BASE_URL`. `GET http://127.0.0.1:8080/health/ready` devolve 200 depois da migração e importação.

Abrir um artigo não marca lido. Use os botões para marcar e salvar. Para observar a reimportação, encerre o Java com Ctrl+C e execute novamente o mesmo comando, mantendo o banco. Os IDs e marcações sobrevivem. `docker compose down` para o banco preservando o volume; `docker compose up -d --wait db` o retoma.

## Verificar

Na raiz, em Linux/WSL, com JDK 25, Node 24.15–24.x, Python 3.11+ e Docker acessível:

```bash
./scripts/verify
```

Na primeira preparação do sistema, instale as bibliotecas Chromium com `cd frontend && npm ci && npm exec -- playwright install --with-deps chromium`. O gate instala dependências pelo lockfile, verifica Spotless/Prettier, executa `clean verify` normal e demo, inspeciona os dois JARs, valida typecheck/Vitest/build e executa Playwright em sequência. Preserva os cinco percursos via Vite e acrescenta normal/demo sem Vite, imagem, troca de assets, instalação/atualização/recuperação T-19 e T-20 nos dois builds. Relatórios, exit codes, hashes, inventários e pacote ficam em `artifacts/<execução>/`; ausência de suíte ou skip essencial faz falhar. Para repetir a prova histórica B-06, informe `NEWS_LEGACY_JAR=/caminho/do/JAR-normal-B06` ao gate. O [guia](docs/11-guia-de-desenvolvimento.md#gate-f-02) detalha pré-requisitos e isolamento.

`./mvnw clean package` e `./mvnw -Pdemo clean package` produzem os respectivos JARs e executam unitários; **não executam as integrações do `verify`**. Use `clean` ao trocar de perfil. O gate preserva os JARs normal e demo novos em caminhos separados e confere hash/inventário de cada um no respectivo percurso de navegador. Os dois percursos usam recursos próprios e rodam em sequência. PostgreSQL/processos E2E são próprios e temporários; as portas 18080/5174 precisam estar livres. O comando não carrega `.env` nem usa `JDBC_URL` pessoal.

Em **Buscar no acervo**, envie palavras do título/texto recebido e, se necessário, uma fonte ou período. Fontes pausadas/arquivadas continuam pesquisáveis. A consulta e a página ficam na URL, inclusive ao abrir um artigo e voltar à busca. O conteúdo pode ser só um trecho; acentos e flexões diferentes não são equivalentes na configuração `simple`. A busca não consulta os originais.

Próxima melhoria especificada: [F-03 — qualidade e apresentação do repositório](docs/specs/F-03-qualidade-e-apresentacao-do-repositorio.md), ainda sem implementação. Cobre lint Java/frontend/Python, padronização de arquivos, auditoria de dependências/imagens, métricas, refatorações pontuais e documentação disponível em checkout. Os controles descritos como futuros na spec não fazem parte do gate atual.

## Limites

B-03 acrescenta cadastro e importação manual de URLs reais, identidade GUID/URL/fingerprint, paginação/filtros e leitura local. O [aceite integrado local](docs/evidencias/B-03.md) passou com dois builds, PostgreSQL, scanners e três percursos de navegador. B-04 acrescenta agenda automática, backoff e diagnóstico operacional; B-05 acrescenta busca local no acervo; B-06 acrescenta backup/restauração manuais e T-20; B-08 implementa distribuição local e recuperação por imagem. B-06 continua necessária antes de guardar um acervo importante.

A direção de produto revisada em 29/10/2026 é um leitor pessoal local, sem conta, senha ou login, com um único acervo compartilhado pelos clientes da instalação. Loopback é o padrão validado pela configuração de ambos os perfis. Acesso remoto por túnel ou rede de acesso restrito é uma opção de implantação ainda não implementada nem testada; a aplicação dependerá desse perímetro para limitar quem acessa o acervo. Expor a API pessoal publicamente exige controle de acesso antes da exposição. Origin, CSRF e proteção do conteúdo continuam necessários e não autenticam quem alcança a API.

O perfil Maven `demo` empacota uma fixture fixa; o perfil Spring `demo` habilita seu bootstrap e bloqueia cadastro/coleta externa. O build normal exclui dados demo. A apresentação `VITE_APP_MODE=demo` é explícita; o frontend padrão é pessoal. O banco demo usa porta 5433 e volume próprios. Encerre backend/Vite antes de alternar os perfis que usam as mesmas portas de aplicação. Em ambos os perfis, escritas exigem Origin/CSRF. Após restart, o cliente renova e reenvia uma única vez somente em `403/CSRF_INVALID`, mantendo o mesmo comando.

[Guia completo](docs/11-guia-de-desenvolvimento.md) · [Spec B-00](docs/specs/B-00-primeira-fatia-controlada.md) · [Evidências](docs/evidencias/B-00.md)

A [investigação B-01](docs/evidencias/B-01.md) foi concluída em 28/10/2026 com uma amostra de oito fontes reais, três exemplos de recuperação e foco em acompanhar chamadas/abrir o original. Seus recortes sintéticos orientam os testes de ingestão B-03; a amostra não é cadastrada automaticamente.

[F-02 — estabilização da fundação](docs/specs/F-02-estabilizacao-da-fundacao.md) implementa o gate e as regressões de segurança; seu aceite executado está em [F-02](docs/evidencias/F-02.md). A [autenticação própria de B-02](docs/specs/B-02-conta-sessao-e-administracao-offline.md) foi adiada e saiu do caminho obrigatório. A [B-03 — leitor local e ingestão real](docs/specs/B-03-leitor-local-e-ingestao-real.md) está implementada e verificada localmente. A [análise posterior](docs/revisoes/B-03-preparacao-das-proximas-entregas.md) conferiu o manifesto do código e os relatórios arquivados, sem reexecutar o gate.

A [B-04 — agenda e diagnóstico](docs/specs/B-04-agenda-e-diagnostico.md) está implementada; validação e limites ficam em [suas evidências](docs/evidencias/B-04.md). [B-05 — busca](docs/specs/B-05-busca-no-acervo.md) está implementada; a execução de aceite fica em [suas evidências](docs/evidencias/B-05.md). [B-06 — backup/restauração](docs/specs/B-06-backup-e-restauracao.md) está implementada; o [aceite local](docs/evidencias/B-06.md) registra T-20, gate completo e ensaio em dois containers, com dados sintéticos. Faça e confira seu próprio backup antes de guardar acervo importante. O [backlog](docs/08-backlog-e-entregas.md) acompanha a sequência; os comandos acima continuam descrevendo somente as funcionalidades já entregues.

A [B-08 — imagem, instalação e atualização](docs/specs/B-08-imagem-instalacao-e-atualizacao.md), detalhada em 29/10/2026, está implementada e validada localmente. Seus incrementos integram UI/CSP ao JAR, imagem/Compose local, captura com app em contêiner parado e atualização/recuperação. O [guia versionável](scripts/install-guide.md) e as [evidências locais](docs/evidencias/B-08.md) registram os resultados e a pendência de reprodução por terceiro; não há publicação em registry ou declaração de estabilidade.

`docs/` é ignorado pelo `.gitignore`: esta documentação fica local e exige inclusão explícita caso deva ser versionada. A política de exclusão foi preservada.
