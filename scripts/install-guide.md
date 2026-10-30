# Installation, upgrade, and recovery — Linux/WSL amd64

Requirements: Docker Engine 28+, Compose with `--wait`, and Python 3.11+. Operators do not need JDK or Node. HTTP access is through the local computer's loopback interface. Compose does not encrypt the password file; Docker administrators can read it.

## Package and identity

`python3 scripts/verify` builds and verifies separate normal/demo JARs, tests the UI without Vite, and builds the runtime with **the same normal JAR**, without recompiling it. The package in `artifacts/<run>/distribution/` contains `image.json`, JAR inventory/hash, Compose, helpers, this guide, and `image.tar` with its checksum. To package an already approved JAR:

```bash
# Development machine, after a passing gate; use exact paths and hashes.
python3 scripts/build_image.py --jar artifacts/EXECUCAO/normal/news.jar \
  --sha256 HASH_DO_JAR --revision REVISAO --output /tmp/news-release-IDENTIFICADA --save
```

This does not publish to a registry. Transfer the package, check `image.json`, and load `image.tar` on the destination. Local images may have no RepoDigest. The image ID and JAR hash establish identity; mutable tags do not replace them. The Dockerfile `artifact` target builds a candidate in pinned builders (`docker build --target artifact --output type=local,dest=/tmp/news-candidate .`). This alone does not approve it: tests and inventory checks remain necessary.

## Fresh installation

Run these Bash blocks with `set -euo pipefail`, one step at a time, checking results. Replace uppercase path/hash markers with your identified values. `--env-file /dev/null` prevents loading personal `.env` files. Use a new project/volume. Never run `down -v` on an installation.

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
# Check the manifest image ID and user 10001:10001.
export NEWS_APP_PORT=8080
export NEWS_COLLECTION_ENABLED=false
export NEWS_DB_SECRET_FILE="$HOME/.local/share/news-app/password"
test -z "$(docker ps -aq --filter label=com.docker.compose.project=news-personal-app)"
if docker volume inspect news-personal-app_app_pg18 >/dev/null 2>&1; then
  printf 'Volume already exists; stop and identify the installation.\n' >&2; exit 1
fi
test ! -e "$NEWS_DB_SECRET_FILE"
install -d -m 700 "$(dirname "$NEWS_DB_SECRET_FILE")"
# Group 10001 needs read access in the container; keep the parent directory private.
# Create the file as administrator, UID/GID 10001, mode 0400 (or group 10001/0440).
# Use a Linux filesystem and verify permissions; DrvFS is not suitable here.
sudo install -o 10001 -g 10001 -m 400 /dev/null "$NEWS_DB_SECRET_FILE"
IFS= read -r -s -p 'NEW password: ' NEWS_NEW_PASSWORD
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
# App: ONLY 127.0.0.1:8080. Database: 5432/tcp without host publication.
```

Open `http://127.0.0.1:8080`: UI and API share one origin. Select **Gerenciar fontes** to register a feed. With collection disabled it remains pending. Check the installation, then deliberately enable collection, keeping only one installation active:

```bash
export NEWS_COLLECTION_ENABLED=true
docker compose --env-file /dev/null -f compose.app.yaml up -d --wait --no-deps app
```

Read, search, and mark an article; restart only the app and check persistence. Readiness checks startup/Flyway/database access. The bundled Java probe checks internal loopback every 5s, with a 5s timeout, 60s startup period, and up to 12 failures. Shutdown has a 45s deadline. Confirm `exited`, with Running/Restarting/Paused false, before capture. Failure is an error.

## Stop, extract, and back up

Keep the previous image/JAR/configuration. Backups must be outside the checkout on a Linux filesystem, owned by the current user, mode 0700.

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

Set `NEWS_BACKUP` to the unique returned package path. The helper binds app/database identity through project, service, network/alias, JDBC, image ID, and extracted JAR hash; it rejects a running/unrelated app and other clients. Manifest format 1 adds optional `deployment` metadata and supports earlier B-06 packages. Do not store dumps or secrets in artifacts. Backup packages include the helper and instructions from the revision that created them.

## Upgrade and recovery

1. Preserve the previous package and verified backup. Rehearse a successful restore before treating the installation as recoverable.
2. With the app stopped and database running, select the verified candidate image ID. Set `NEWS_COLLECTION_ENABLED=false`, run `up -d --wait --no-deps app`, and stop on errors. Flyway must validate/migrate before readiness.
3. Check reading, search, read/saved marks, paused/archived sources, requests, and schedules. Enable collection only after checking and only in the chosen installation.
4. On failure, stop the candidate and restore into **another new project/volume**. Do not point an old JAR at a migrated database, run repair, or undo SQL manually.

For recovery, preserve all source IDs. Prepare another password file as above and a separate working directory with a Compose copy. The destination keeps the package's database/user names; change project, host port, and volume identity.

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
# POSTGRES_DB creates an empty database; no destination app has started.
# Extract the JAR from the previous image without starting Java.
NEWS_EXTRACT_ID=$(docker create "$NEWS_APP_IMAGE")
docker cp "$NEWS_EXTRACT_ID:/app/news.jar" "$HOME/news-backups/recovery-PREVIOUS.jar"
docker rm "$NEWS_EXTRACT_ID"
python3 "$NEWS_BACKUP/backup.py" restore --container "$NEWS_TARGET_ID" \
  --database news_personal --user news_personal --jar "$HOME/news-backups/recovery-PREVIOUS.jar" \
  --package "$NEWS_BACKUP" --receipt "$HOME/news-backups/recovery-RECIBO_NOVO.json"
# Only after exit 0 and a verified receipt:
docker compose --env-file /dev/null -p "$NEWS_RECOVERY_PROJECT" -f compose.app.yaml up -d --wait --no-deps app
```

Check the UI on port 8081, marks, and schedules before choosing that destination. Name services explicitly during recovery. The source stays stopped. The packaged helper works outside the original checkout. Never substitute hashes.

For development installations, first follow `backup-guide.md` using the host PID, then restore to a new destination with the original JAR. A legacy B-06 JAR uses loopback and does not recognize container-local mode: run it in an isolated JRE on the destination database network, without a published port, collection disabled, and a probe in the same namespace (`docker exec`). Upgrade to the current image afterward. Do not pass new flags to the old binary.

T-19 accepts `NEWS_LEGACY_JAR` for this additional proof. Its absence leaves historical compatibility unproven. The rehearsal needs Git object `dfeabb75dc6c25994d768c5aa8b700876b04fe9c` for the original B-06 helper and the matching legacy JAR. Shallow checkouts must fetch that revision; the historical helper is not obtained from current HEAD.

After restoring the B-06 package into a new database and receiving `verified`, this block checks the original JAR in the current image's runtime. The development source stays stopped. `NEWS_APP_IMAGE` identifies the loaded current image; `NEWS_LEGACY_JAR` points to the JAR whose hash is in the backup manifest:

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
# Wait for startup; the probe must exit 0. The port is not published on the host.
docker exec "$NEWS_LEGACY_CHECK" java -cp /app ReadyProbe
# In a synthetic rehearsal, check a known search in the same namespace:
docker exec "$NEWS_LEGACY_CHECK" java -cp /app ReadyProbe '/api/v1/search?q=PostgreSQL' 'Legacy PostgreSQL'
docker stop --time 45 "$NEWS_LEGACY_CHECK"
docker inspect --format '{{.State.Status}} {{.State.Running}} {{.State.Restarting}} {{.State.Paused}}' "$NEWS_LEGACY_CHECK"
docker rm "$NEWS_LEGACY_CHECK"
# Only after checking the legacy app, upgrade the destination to the B-08 image,
# keeping collection disabled. Preserve the original package/JAR/database for recovery.
docker compose --env-file /dev/null -p "$NEWS_RECOVERY_PROJECT" -f compose.app.yaml up -d --wait --no-deps app
```

## Acceptance and limits

`scripts/verify` preserves existing tests/T-20 and runs packaged browser scenarios, image build/audits, and T-19: installation, upgrade between identified artifacts, test-only migration failure, and recovery into a new database. Test resources have a run identity and are removed without touching personal installations. The distributed guide comes directly from the current source and is included in implementation hashes.

A third person still needs to reproduce installation, upgrade, and recovery and record the result in B-08 evidence. Automation does not replace that report. There is no published release, validated multiarch support, major PostgreSQL upgrade, or stability claim. License ownership and a functional private reporting channel remain unresolved; this guide grants no license.
