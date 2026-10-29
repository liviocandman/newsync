# Caderno — agregador de feeds

Primeira fatia B-00: RSS/Atom controlado → PostgreSQL → lista/leitura → lido/salvo. Os textos são sintéticos e as marcações persistem no banco local.

## Iniciar a demonstração

Pré-requisitos: JDK 25, Node 24 (mínimo 24.15; patch testado no guia), Docker com daemon ativo e Compose. Linux/WSL é a referência; no Windows use `mvnw.cmd`. A primeira instalação baixa dependências e imagens.

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
npm run dev
```

Abra [http://127.0.0.1:5173](http://127.0.0.1:5173). Use essa origem exatamente: a proteção CSRF/Origin exige correspondência com `APP_BASE_URL`. `GET http://127.0.0.1:8080/health/ready` devolve 200 depois da migração e importação.

Abrir um artigo não marca lido. Use os botões para marcar e salvar. Para observar a reimportação, encerre o Java com Ctrl+C e execute novamente o mesmo comando, mantendo o banco. Os IDs e marcações sobrevivem. `docker compose down` para o banco preservando o volume; `docker compose up -d --wait db` o retoma.

## Verificar

Na raiz, em Linux/WSL, com JDK 25, Node 24.15–24.x, Python 3.11+ e Docker acessível:

```bash
./scripts/verify
```

Na primeira preparação do sistema, instale as bibliotecas Chromium com `cd frontend && npm ci && npm exec -- playwright install --with-deps chromium`. O gate instala dependências pelo lockfile, verifica Spotless/Prettier, executa `clean verify` normal e demo, inspeciona os dois JARs, valida typecheck/Vitest/build e executa Playwright em sequência. Relatórios, exit codes, hashes e inventários ficam em `artifacts/<execução>/`; ausência de suíte ou skip essencial faz falhar. O [guia](docs/11-guia-de-desenvolvimento.md#gate-f-02) detalha pré-requisitos e isolamento.

`./mvnw clean package` e `./mvnw -Pdemo clean package` produzem os respectivos JARs e executam unitários; **não executam as integrações do `verify`**. Use `clean` ao trocar de perfil. O navegador usa o JAR demo novo conferido pelo gate. PostgreSQL/processos E2E são próprios e temporários; as portas 18080/5174 precisam estar livres. O comando não carrega `.env` nem usa `JDBC_URL` pessoal.

## Limites

M1 é uma demonstração local com banco descartável. Ainda não aceita URLs reais nem possui autenticação de uso pessoal. Cadastro, coleta externa, scheduler, paginação/filtros, busca e instalação de produção pertencem às próximas entregas. O perfil normal exige autenticação, e sua implementação vem em B-02.

O perfil Maven `demo` empacota uma fixture fixa; o perfil Spring `demo` habilita o bootstrap e o acesso local com CSRF. O build normal não inclui os dados demo. Não há endpoint de importação, caminho configurável nem download de feeds.

[Guia completo](docs/11-guia-de-desenvolvimento.md) · [Spec B-00](docs/specs/B-00-primeira-fatia-controlada.md) · [Evidências](docs/evidencias/B-00.md)

A [investigação B-01](docs/evidencias/B-01.md) foi concluída em 28/10/2026 com uma amostra de oito fontes reais, três exemplos de recuperação e foco em acompanhar chamadas/abrir o original. A ingestão real continua prevista para B-03.

[F-02 — estabilização da fundação](docs/specs/F-02-estabilizacao-da-fundacao.md) implementa o gate e as regressões de segurança; seu aceite executado está em [F-02](docs/evidencias/F-02.md). A próxima implementação é [B-02 — conta, sessão e administração offline](docs/specs/B-02-conta-sessao-e-administracao-offline.md), ainda pendente; o [backlog](docs/08-backlog-e-entregas.md) registra a sequência e as lacunas atribuídas a B-03/B-04.
