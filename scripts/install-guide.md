# Instalação, atualização e recuperação B-08 — Linux/WSL amd64

Referência: Docker Engine >=28, Compose com `--wait`, Python >=3.11. O operador
não precisa de JDK/Node. Acesso HTTP somente pelo loopback do próprio computador.
O arquivo de senha não é cifrado pelo Compose; administradores Docker podem lê-lo.

## Pacote e identidade

O gate `python3 scripts/verify` gera os JARs normal/demo verificados, testa o JAR
sem Vite e constrói o runtime **com o mesmo JAR normal**, sem recompilar. O pacote
em `artifacts/<execução>/distribution/` contém `image.json`, inventário/hash do JAR,
Compose, auxiliares, este guia e `image.tar` com checksum. Para gerar outro pacote
a partir de um JAR já aprovado:

```bash
# Máquina de desenvolvimento, depois do gate aprovado; use seus caminhos/hashes exatos.
python3 scripts/build_image.py --jar artifacts/EXECUCAO/normal/news.jar \
  --sha256 HASH_DO_JAR --revision REVISAO --output /tmp/news-release-IDENTIFICADA --save
```

Esse comando não publica em registry. Transfira o pacote, confira `image.json` e
carregue `image.tar` na máquina de uso. Uma imagem local pode não ter RepoDigest.
Image ID e hash do JAR são as identidades; uma tag mutável não as substitui.
O target `artifact` do Dockerfile permite gerar um candidato em builders fixados
(`docker build --target artifact --output type=local,dest=/tmp/news-candidate .`).
Isso sozinho não aprova o candidato: os testes e o inventário continuam exigidos.

## Instalação nova

Execute os blocos em Bash com `set -euo pipefail`, um passo por vez, conferindo
cada resultado. `--env-file /dev/null` impede carregar `.env` pessoal. Não reutilize
projetos/volumes antigos silenciosamente. Nunca use `down -v` em uma instalação.

```bash
set -euo pipefail
cd /CAMINHO/DO/PACOTE
python3 - <<'PY'
import hashlib, json
from pathlib import Path
manifest = json.loads(Path('image.json').read_text())
archive = Path('image.tar')
with archive.open('rb') as stream:
    assert hashlib.file_digest(stream, 'sha256').hexdigest() == manifest['imageArchive']['sha256']
assert archive.stat().st_size == manifest['imageArchive']['bytes']
PY
docker load --input image.tar
NEWS_APP_IMAGE=$(python3 -c 'import json; print(json.load(open("image.json"))["imageId"])')
export NEWS_APP_IMAGE
docker image inspect "$NEWS_APP_IMAGE" --format '{{.Id}} {{.Config.User}}'
# Confira image ID do manifesto e usuário 10001:10001.
export NEWS_APP_PORT=8080
export NEWS_COLLECTION_ENABLED=false
export NEWS_DB_SECRET_FILE="$HOME/.local/share/news-app/password"
test -z "$(docker ps -aq --filter label=com.docker.compose.project=news-personal-app)"
if docker volume inspect news-personal-app_app_pg18 >/dev/null 2>&1; then
  printf 'Volume já existe; interrompa e identifique a instalação.\n' >&2; exit 1
fi
test ! -e "$NEWS_DB_SECRET_FILE"
install -d -m 700 "$(dirname "$NEWS_DB_SECRET_FILE")"
# Grupo 10001 precisa de leitura no contêiner; diretório pai continua privado.
# Prepare o arquivo como administrador, UID/GID 10001, modo 0400 (ou grupo 10001/0440).
# Não use DrvFS; verifique permissões reais no filesystem Linux.
sudo install -o 10001 -g 10001 -m 400 /dev/null "$NEWS_DB_SECRET_FILE"
IFS= read -r -s -p 'Senha NOVA: ' NEWS_NEW_PASSWORD
printf '\n'
test -n "$NEWS_NEW_PASSWORD"
printf '%s' "$NEWS_NEW_PASSWORD" | sudo tee "$NEWS_DB_SECRET_FILE" >/dev/null
unset NEWS_NEW_PASSWORD
docker compose --env-file /dev/null -f compose.app.yaml config --quiet
docker compose --env-file /dev/null -f compose.app.yaml up -d --wait db
docker compose --env-file /dev/null -f compose.app.yaml up -d --wait --no-deps app
NEWS_APP_ID=$(docker compose --env-file /dev/null -f compose.app.yaml ps -q app)
NEWS_DB_ID=$(docker compose --env-file /dev/null -f compose.app.yaml ps -q db)
docker inspect --format '{{.Id}} {{json .NetworkSettings.Ports}} {{.State.Health.Status}}' "$NEWS_APP_ID" "$NEWS_DB_ID"
# App: SOMENTE 127.0.0.1:8080. Db: 5432/tcp sem publicação host.
```

Abra `http://127.0.0.1:8080`: UI e API têm a mesma origem. Cadastre uma fonte;
com coleta desligada ela fica pendente. Confira a instalação e habilite coleta
deliberadamente, mantendo uma única instalação ativa:

```bash
export NEWS_COLLECTION_ENABLED=true
docker compose --env-file /dev/null -f compose.app.yaml up -d --wait --no-deps app
```

Leia, busque e marque um artigo; reinicie somente o app e confira persistência.
Readiness verifica boot/Flyway/banco; o probe Java incluído consulta o loopback
interno a cada 5s, timeout 5s, carência 60s, até 12 falhas. Parada tem prazo 45s;
confirme `exited`, sem Running/Restarting/Paused, antes de capturar. Falha é erro.

## Parada, extração e backup

Guarde a imagem/JAR/configuração anteriores. O diretório de backup deve ficar
fora do checkout, no filesystem Linux, dono atual, modo 0700.

```bash
install -d -m 700 "$HOME/news-backups"
NEWS_APP_ID=$(docker compose --env-file /dev/null -f compose.app.yaml ps -q app)
NEWS_DB_ID=$(docker compose --env-file /dev/null -f compose.app.yaml ps -q db)
docker compose --env-file /dev/null -f compose.app.yaml stop app
docker inspect --format '{{.Id}} {{.State.Status}} {{.State.Running}} {{.State.Restarting}} {{.State.Paused}}' "$NEWS_APP_ID"
docker cp "$NEWS_APP_ID:/app/news.jar" "$HOME/news-backups/deployed-PREVIOUS.jar"
python3 backup.py backup --container "$NEWS_DB_ID" --database news_personal --user news_personal \
  --jar "$HOME/news-backups/deployed-PREVIOUS.jar" --stopped-container "$NEWS_APP_ID" \
  --directory "$HOME/news-backups"
```

Copie o caminho único retornado para `NEWS_BACKUP`. O helper vincula app/db por
projeto, serviço, rede/alias, JDBC, image ID e hash do JAR extraído; recusa app
vivo/alheio e outros clientes. O manifesto formato 1 ganha `deployment` opcional;
restaura também pacotes B-06 anteriores. Não guarde dumps/segredos em artifacts.

## Atualização e recuperação

1. Preserve o pacote anterior e seu backup verificado. Tenha um ensaio de restore
   bem-sucedido antes de considerar a versão recuperável.
2. Com app parado e db ligado, escolha o image ID candidato verificado. Exporte
   `NEWS_COLLECTION_ENABLED=false`, execute `up -d --wait --no-deps app` e pare
   se houver erro. Flyway deve validar/migrar antes da prontidão.
3. Confira leitura, busca, lido/salvo, fontes pausadas/arquivadas, pedidos e agenda.
   Habilite coleta somente depois da conferência e na instalação escolhida.
4. Se falhar, pare o candidato e restaure o backup em **outro projeto/volume novo**.
   Nunca aponte o JAR antigo ao banco alterado, use repair ou desfaça SQL à mão.

Recuperação concreta em projeto novo: preserve todos os IDs da origem. Prepare
outro arquivo de senha como acima e um diretório de trabalho próprio com cópia
do Compose. O destino conserva database/user do pacote; mude projeto/porta/volume.

```bash
export NEWS_BACKUP="$HOME/news-backups/NOME_EXATO"
export NEWS_APP_IMAGE=sha256:IMAGE_ID_ANTERIOR
export NEWS_DB_SECRET_FILE="$HOME/.local/share/news-recovery/password"
export NEWS_APP_PORT=8081
export NEWS_COLLECTION_ENABLED=false
NEWS_RECOVERY_PROJECT=news-recovery-IDENTIFICACAO_NOVA
test -z "$(docker ps -aq --filter label=com.docker.compose.project=$NEWS_RECOVERY_PROJECT)"
if docker volume inspect "${NEWS_RECOVERY_PROJECT}_app_pg18" >/dev/null 2>&1; then exit 1; fi
docker compose --env-file /dev/null -p "$NEWS_RECOVERY_PROJECT" -f compose.app.yaml up -d --wait db
NEWS_TARGET_ID=$(docker compose --env-file /dev/null -p "$NEWS_RECOVERY_PROJECT" -f compose.app.yaml ps -q db)
# POSTGRES_DB cria banco vazio; nenhum app destino foi iniciado.
# Extraia o JAR da imagem anterior sem iniciar Java.
NEWS_EXTRACT_ID=$(docker create "$NEWS_APP_IMAGE")
docker cp "$NEWS_EXTRACT_ID:/app/news.jar" "$HOME/news-backups/recovery-PREVIOUS.jar"
docker rm "$NEWS_EXTRACT_ID"
python3 "$NEWS_BACKUP/backup.py" restore --container "$NEWS_TARGET_ID" \
  --database news_personal --user news_personal --jar "$HOME/news-backups/recovery-PREVIOUS.jar" \
  --package "$NEWS_BACKUP" --receipt "$HOME/news-backups/recovery-RECIBO_NOVO.json"
# Só depois de exit 0 e recibo verified:
docker compose --env-file /dev/null -p "$NEWS_RECOVERY_PROJECT" -f compose.app.yaml up -d --wait --no-deps app
```

Confira a UI na porta 8081 e as marcações/agenda antes de escolher esse destino.
Não execute `up` sem serviço explícito durante recuperação. A origem continua
parada. O helper empacotado funciona fora do checkout. Não substitua hashes.

Para migrar a instalação de desenvolvimento, siga primeiro `backup-guide.md`
(PID host) e restaure em destino novo com seu JAR original. Um JAR B-06 legado
usa loopback e desconhece container-local: execute-o em JRE isolado, conectado à
rede do db destino, sem porta publicada, com coleta desligada e probe no mesmo
namespace (`docker exec`); só depois faça a atualização para B-08. Não passe flags
novas ao binário antigo. O ensaio T-19 aceita `NEWS_LEGACY_JAR` para essa prova;
ausência da prova é pendência, não aceite de compatibilidade histórica.
Esse ensaio requer o objeto Git B-06 `dfeabb75dc6c25994d768c5aa8b700876b04fe9c`
para capturar com o helper histórico, além do JAR legado correspondente; checkout
raso precisa ter essa revisão disponível. O helper não é obtido do HEAD atual.

Depois de restaurar o pacote B-06 em um db novo pelo helper e de obter o recibo
`verified`, este bloco confere seu JAR original no runtime da imagem B-08. A
origem de desenvolvimento continua parada. `NEWS_APP_IMAGE` identifica a imagem
B-08 já carregada; `NEWS_LEGACY_JAR` aponta para o JAR cujo hash está no pacote:

```bash
NEWS_LEGACY_JAR="$HOME/news-backups/deployed-B06.jar"
NEWS_LEGACY_CHECK=news-legacy-check-IDENTIFICACAO_NOVA
docker run -d --name "$NEWS_LEGACY_CHECK" --entrypoint java \
  --network "${NEWS_RECOVERY_PROJECT}_default" --read-only --tmpfs /tmp \
  --mount "type=bind,source=$NEWS_LEGACY_JAR,target=/legacy.jar,readonly" \
  --mount "type=bind,source=$NEWS_DB_SECRET_FILE,target=/run/secrets/spring.datasource.password,readonly" \
  -e JDBC_URL=jdbc:postgresql://db:5432/news_personal -e JDBC_USERNAME=news_personal \
  -e APP_BASE_URL=http://127.0.0.1:8080 \
  "$NEWS_APP_IMAGE" -jar /legacy.jar --spring.profiles.active=normal \
  --spring.config.import=configtree:/run/secrets/ --app.collection.enabled=false
# Aguarde o boot; o probe deve sair 0. A porta não é publicada no host.
docker exec "$NEWS_LEGACY_CHECK" java -cp /app ReadyProbe
# Em ensaio sintético, confira uma busca conhecida no mesmo namespace:
docker exec "$NEWS_LEGACY_CHECK" java -cp /app ReadyProbe '/api/v1/search?q=PostgreSQL' 'Legacy PostgreSQL'
docker stop --time 45 "$NEWS_LEGACY_CHECK"
docker inspect --format '{{.State.Status}} {{.State.Running}} {{.State.Restarting}} {{.State.Paused}}' "$NEWS_LEGACY_CHECK"
docker rm "$NEWS_LEGACY_CHECK"
# Só depois da conferência do legado: atualizar o destino para a imagem B-08,
# ainda com coleta desligada. Preserve pacote/JAR/banco originais para recuperação.
docker compose --env-file /dev/null -p "$NEWS_RECOVERY_PROJECT" -f compose.app.yaml up -d --wait --no-deps app
```

## Aceite e limites

`scripts/verify` exige os testes anteriores/T-20 e os percursos empacotados, build
da imagem e T-19 (instalação, atualização entre artefatos identificados, falha de
migração exclusiva de teste e recuperação em banco novo). Recursos da prova têm
identidade de execução e são removidos sem tocar a instalação pessoal.
O relato de instalação/atualização/recuperação por outra pessoa ainda precisa ser
registrado em `docs/evidencias/B-08.md`; o gate não o substitui. Sem esse relato,
B-08 não está completamente aceita. Não há publicação, multiarch validado,
upgrade major do PostgreSQL nem declaração de estabilidade.
