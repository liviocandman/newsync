# Backup e restauração B-06 (Linux/WSL, PostgreSQL 18 → 18)

O auxiliar `python3 scripts/backup.py` usa `docker exec` no **ID explícito** do container e as ferramentas 18 da própria imagem. Não lê `.env`, não recebe senhas por argumentos, não inicia Java e não cria/remove bancos. A autenticação é pelo socket local já provisionado no container da instalação. Não há endpoint/upload de backup. Não usar `down -v`, copiar volume em uso ou iniciar Java no banco novo antes da restauração.

Prepare um diretório **fora do checkout**, no filesystem Linux (WSL: `/home/...`, não `/mnt/c/...`). O auxiliar exige dono atual e permissões sem acesso de grupo/outros; recusa DrvFS, onde chmod não comprova as ACLs Windows. Verifique também permissões dos diretórios ancestrais e do disco que guardará a cópia separada. Em armazenamento Windows, confira as ACLs efetivas no host antes da transferência; este auxiliar não atesta essas ACLs. O dump contém dados pessoais, inclusive conteúdo/URLs: não commitar, anexar a relatórios nem publicar em CI.

```bash
cd /mnt/c/news
install -d -m 700 "$HOME/news-backups"
stat -c '%U %a %n' "$HOME/news-backups"
# Registre o PID ao iniciar o Java pessoal (alternativa ao primeiro comando do README):
set -a
. ./.env.local
set +a
java -jar backend/target/news.jar &
NEWS_APP_PID=$!
# Quando for capturar, no MESMO terminal:
kill -TERM "$NEWS_APP_PID"
wait "$NEWS_APP_PID" || test "$?" -eq 143
# O processo terminou; PostgreSQL continua ligado.
NEWS_SOURCE_ID=$(docker compose --env-file .env.local -f compose.local.yaml ps -q db)
test -n "$NEWS_SOURCE_ID"
docker inspect --format '{{.Id}} {{index .Config.Labels "com.docker.compose.project"}} {{json .NetworkSettings.Ports}}' "$NEWS_SOURCE_ID"
# Confira projeto news-personal-local e porta 5434 antes de continuar.
python3 scripts/backup.py backup \
  --container "$NEWS_SOURCE_ID" --database news_personal --user news_personal \
  --jar backend/target/news.jar --stopped-pid "$NEWS_APP_PID" \
  --directory "$HOME/news-backups"
```

A última linha da saída é o pacote único `news-<UTC>-<UUID>`. Copie esse caminho para `NEWS_BACKUP` no exemplo abaixo. O diretório `.incomplete` é mantido em erro/interrupção e não é backup concluído. O nome anterior válido nunca é reutilizado. O pacote concluído inclui `archive.dump` custom integral, manifesto com UTC/build/hash JAR/versões/origem/porta/Flyway/checksum/tamanho, hashes de todas as linhas e estrutura, e cópias deste guia e do auxiliar. Nenhuma senha/configuração pessoal é copiada. Preserve separadamente o JAR compatível, configurações e credenciais; trocar somente o JAR não reverte schema. O checksum detecta corrupção acidental; não autentica um pacote recebido de terceiros.

Com origem parada, provisione **outro container e volume**, com porta e nome próprios. Os nomes abaixo são uma recuperação explícita; se já existirem, escolha nomes novos. Não reutilize um container antigo sem conferir seu conteúdo. O `POSTGRES_DB=postgres` evita criar antecipadamente o banco do produto; `createdb` usa template0. O segredo é digitado sem eco e entregue por variável de ambiente ao Docker (administradores Docker continuam tendo acesso às credenciais do container).

```bash
NEWS_BACKUP="$HOME/news-backups/COLE-O-NOME-EXATO-RETORNADO"
NEWS_RESTORE_CONTAINER=news-personal-restore-b06
NEWS_RESTORE_VOLUME=news-personal-restore-b06-pg18
if docker container inspect "$NEWS_RESTORE_CONTAINER" >/dev/null 2>&1 || docker volume inspect "$NEWS_RESTORE_VOLUME" >/dev/null 2>&1; then
  printf 'Nome de destino já existe; escolha outro container e volume.\n' >&2
  exit 1
fi
read -r -s -p 'Senha NOVA do banco de destino: ' POSTGRES_PASSWORD
printf '\n'
export POSTGRES_PASSWORD
docker volume create "$NEWS_RESTORE_VOLUME"
docker run -d --name "$NEWS_RESTORE_CONTAINER" \
  --label news.recovery=B-06-personal \
  -e POSTGRES_USER=news_restore -e POSTGRES_DB=postgres -e POSTGRES_PASSWORD \
  -p 127.0.0.1:5435:5432 \
  -v "$NEWS_RESTORE_VOLUME:/var/lib/postgresql" \
  --health-cmd='pg_isready -U news_restore -d postgres' \
  --health-interval=2s --health-timeout=3s --health-retries=30 \
  postgres:18.3@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db
for NEWS_WAIT in $(seq 1 30); do
  NEWS_HEALTH=$(docker inspect --format '{{.State.Health.Status}}' "$NEWS_RESTORE_CONTAINER")
  test "$NEWS_HEALTH" = healthy && break
  test "$NEWS_HEALTH" = unhealthy && break
  sleep 2
done
test "$NEWS_HEALTH" = healthy || { printf 'Destino não ficou pronto.\n' >&2; exit 1; }
docker exec "$NEWS_RESTORE_CONTAINER" createdb -w -U news_restore -T template0 -O news_restore news_restored
python3 scripts/backup.py restore \
  --container "$NEWS_RESTORE_CONTAINER" --database news_restored --user news_restore \
  --jar backend/target/news.jar --package "$NEWS_BACKUP" \
  --receipt "$HOME/news-backups/restore-b06-verified.json" || { printf 'Restore recusado; não iniciar Java.\n' >&2; exit 1; }
# Só executar os próximos comandos se o restore acima terminou exit 0 e gerou recibo verified.
export JDBC_URL=jdbc:postgresql://127.0.0.1:5435/news_restored
export JDBC_USERNAME=news_restore
export JDBC_PASSWORD="$POSTGRES_PASSWORD"
export APP_BASE_URL=http://127.0.0.1:5173
unset POSTGRES_PASSWORD
java -jar backend/target/news.jar --spring.profiles.active=normal --app.collection.enabled=false &
NEWS_RESTORE_APP_PID=$!
curl --fail http://127.0.0.1:8080/health/ready
# Aguarde prontidão 200; em outro terminal execute frontend npm run dev como no README.
```

O restore recusa checksum/tamanho/inventário/build/major divergentes, o mesmo cluster+banco de origem, clientes conectados e destino com objetos. Usa `pg_restore --no-owner --no-acl --single-transaction --exit-on-error`, sem `--clean`/`--create`. O usuário destino recebe a posse, sem roles/grants antigos. Compara hashes de novas leituras de **todas as colunas**, contagens, sequências, colunas, constraints, índices e histórico Flyway antes de emitir o recibo. Não use Flyway repair nem edite checksums. Falha/ausência de recibo impede avançar para Java; falha do processo de restore reverte a transação. Divergência de conferência após restore exige investigar ou preparar **outro banco novo**. Nunca ligar coleta para tentar corrigir divergência.

Abra lista, detalhe (inclusive sem corpo), fontes pausadas/arquivadas e busca conhecida. Confira lido/salvo independentes e a nova marcação após renovação automática de CSRF: o cliente antigo recebe `403/CSRF_INVALID`, busca token novo e repete uma única vez o mesmo comando. Sessão/cursor/tentativa em memória não são backup. Com coleta desligada, pedidos pendentes, validadores e datas permanecem intactos. Para adotar o destino depois da conferência, pare/aguarde seu Java, mantenha a origem parada e inicie **somente o destino escolhido** com o mesmo ambiente acima e `--app.collection.enabled=true`. B-04 retoma pedidos explícitos e fontes ativas vencidas; pausa/arquivamento/Retry-After continuam valendo. Uma chamada pode repetir depois da recuperação; identidade e marcações persistidas são preservadas.

Para adotar o destino, depois de aprovar a conferência e ainda no terminal com o ambiente JDBC **do destino**:

```bash
kill -TERM "$NEWS_RESTORE_APP_PID"
wait "$NEWS_RESTORE_APP_PID" || test "$?" -eq 143
# A instalação de origem continua parada.
java -jar backend/target/news.jar --spring.profiles.active=normal --app.collection.enabled=true
```

Restauração volta ao instante do backup: artigos/marcações posteriores não reaparecem. Para voltar de atualização malsucedida, restaure o backup anterior em banco novo com seu JAR anterior; upgrade de schema é uma etapa posterior à prova do restore original. Mantenha backup, banco e build anteriores durante a conferência, sem juntar gravações das duas instalações. Frequência, retenção e cópia em disco separado são escolhas do operador, sem automação entregue; não há prova de recuperação de disco perdido, nuvem/WAL ou mudança major.

Ensaio sintético reproduzível: execute `./scripts/verify`. O cenário nominal obrigatório `BackupRestoreIT.t20RestoresCompleteArchiveRejectsUnsafeRecoveryAndResumesDurableCollection` usa PostgreSQL real isolado, credenciais efêmeras, template0, os mesmos comandos do auxiliar e JAR subprocessado com perfil normal. Captura/restaura antes de boot, prova negativos, prontidão/HTTP/busca/CSRF e retomada com relógio/transporte controlados, sem feed público nem espera de cooldown. Relatórios arquivam contagens/hashes/exits; o dump temporário sintético é removido, nunca copiado para artifacts. Docker/ferramenta ausente ou skip falha o gate. Para depurar somente T-20 após empacotar, use `cd backend && ./mvnw -DskipTests package && ./mvnw -Dit.test=BackupRestoreIT failsafe:integration-test failsafe:verify`; esse recorte não substitui o gate completo.
