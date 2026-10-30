# Caderno

A local RSS/Atom reader for one shared personal archive. Register feeds, read the content they provide, or open the original. PostgreSQL preserves articles, read/saved marks, collection schedules, and search indexes. Available features include source management, automatic/manual collection, filtering and pagination, archive search, logical backup/restore, and a compiled UI served by Java.

Feed content may contain only a summary or a title and link. Caderno does not retrieve full articles or load remote media to fill gaps. There are no accounts or login. The validated perimeter is the local computer through loopback; anyone who can reach the API can access the archive. Origin/CSRF checks do not authenticate users. Remote/public access, multiarch distribution, and major PostgreSQL upgrades have not been validated.

## Packaged installation

Operators need Docker Engine 28+, Compose with `--wait`, and Python 3.11+ on Linux/WSL amd64. Java and Node are not needed on the operator's host. Follow the [installation, upgrade, and recovery guide](scripts/install-guide.md). A passing gate produces an identified package in `artifacts/<run>/distribution/`, including the verified image, checksums, Compose, guides, and backup helper. There is no published registry release. Independent reproduction of the guide remains pending.

## Development

Prerequisites: JDK 25, Node 24.15–24.x, Python 3.11+, and an accessible Docker daemon with Compose. Run from a complete checkout in Linux/WSL. The first build needs network access. Copy the configuration and set both password entries to the same private local value:

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

In another terminal:

```bash
cd frontend
npm ci
npm run dev
```

Open [the development reader](http://127.0.0.1:5173). Select **Gerenciar fontes** to register a feed. **Importar agora** requests priority between attempts and respects cooldown/Retry-After. Rename, pause, reactivate, or archive a source without deleting its articles. Opening an article does not mark it as read. **Buscar no acervo** searches received titles/text, including paused/archived sources. Search filters and pagination stay in the URL.

The browser origin must exactly match `APP_BASE_URL`. The development database uses port 5434 and the separate `news-personal-local` project. `docker compose --env-file .env.local -f compose.local.yaml down` stops it while preserving the volume. Normal builds never import the demo fixture. To inspect data before allowing network collection, append `--app.collection.enabled=false` to Java.

To serve the compiled UI from the JAR, after loading the JDBC environment:

```bash
APP_BASE_URL=http://127.0.0.1:8080 java -jar backend/target/news.jar
```

For the synthetic demonstration: copy `.env.example` to `.env`, start `docker compose up -d --wait db`, build with `cd backend && ./mvnw -Pdemo clean package`, load `.env`, and run `java -jar backend/target/news.jar --spring.profiles.active=demo`. In the frontend terminal use `VITE_APP_MODE=demo npm run dev`. Use `clean` when switching profiles. Maven builds each UI from its lockfile before packaging.

## Verify and contribute

Prepare Chromium system libraries once with `cd frontend && npm ci && npm exec -- playwright install --with-deps chromium`. Then, from the root:

```bash
python3 scripts/verify
```

The gate runs read-only formatting/lint/link/secret checks, normal/demo tests and JAR audits, frontend typechecking and coverage, browser scenarios, image construction/audits, and installation/upgrade/recovery. Missing tools, reports, inventories, or essential scenarios cause failure. Per-run reports and hashes are under `artifacts/`; read that run's `summary.json`. A workflow file does not establish remote CI success.

See [CONTRIBUTING](CONTRIBUTING.md), [quality controls](docs/quality.md), the [development guide](docs/11-guia-de-desenvolvimento.md), and [technical documentation](docs/README.md). Maven `package` runs unit tests; integrations require `verify`.

## Backup and project status

Follow the [backup guide](scripts/backup-guide.md) for development or the installation guide for containers. Stop and identify the app before capture; keep backup packages outside the checkout on a private Linux filesystem. Restore only into a new database. Keep collection disabled until reading, marks, and search are checked. Retention and off-device copies are manual responsibilities.

Delivery history belongs in [CHANGELOG](CHANGELOG.md) and the linked evidence. [F-03 evidence](docs/evidencias/F-03.md) distinguishes implemented controls from executed acceptance. No stability, benchmark, remote gate, or independent installation claim is implied. [SECURITY](SECURITY.md) records the unresolved private reporting channel. A license, copyright holder, and year have not been supplied; no project license grant is asserted and public preparation remains open.
