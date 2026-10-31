# Contributing to Caderno

Use Linux/WSL amd64, JDK 25, Node 24.15–24.x, Python 3.11+, Docker Engine 28+, and Chromium system libraries. Full distribution acceptance also needs Compose. Exact executed versions and results belong to each run's `environment.json` and `summary.json`.

`backend/` contains the Maven app, feature packages, immutable Flyway migrations, and fixtures. `frontend/` contains React and browser tests. `scripts/` contains operational helpers and the single gate. `tools/quality/` isolates development tools; `config/` holds the delivery manifest, rules, download checksums, and audit policy. `docs/` is ignored local planning/evidence and is not required by a checkout. Public documentation lives in the tracked root Markdown files and operational guides in `scripts/`.

## Commands

From the repository root:

```bash
npm ci --prefix frontend
npm ci --prefix tools/quality
npm --prefix tools/quality run format:check
npm --prefix tools/quality run lint
python3 scripts/quality_tools.py ruff check .
python3 scripts/quality_tools.py ruff format --check .
python3 scripts/delivery.py
node scripts/check_links.mjs
```

Run the essential local/CI gate while developing:

```bash
python3 scripts/verify --mode ci
```

Run `python3 scripts/verify --mode full` before distribution acceptance; this remains the default when no mode is given. Automatic CI success does not substitute for that acceptance. The gate never loads personal `.env` files, stages files, commits, pushes, or publishes. See the [installation guide](scripts/install-guide.md) and [backup guide](scripts/backup-guide.md) for app/database operation.

## Verification scopes

| Check                                                                             | Automatic CI       | Full acceptance        |
| --------------------------------------------------------------------------------- | ------------------ | ---------------------- |
| Formatting, Java/frontend/Python lint, links, secrets, helper tests               | Required           | Required               |
| npm runtime/pipeline audits and final Java dependency inventory/audit             | Required           | Required               |
| All Java unit/integration/HTTP/security tests, PostgreSQL and T-20 backup/restore | Normal build, once | Normal and demo builds |
| SpotBugs/FindSecBugs and Java coverage                                            | Normal build       | Both builds            |
| Compiled normal/demo UI, JAR hashes/inventories, Vitest coverage/typecheck        | Required           | Required               |
| All Vite and packaged normal/demo browser scenarios                               | Required           | Required               |
| Negative linter probes, isolated source snapshot, diagnostic metrics              | Full acceptance    | Required               |
| Image export/audits and T-19 installation/upgrade/recovery                        | Full acceptance    | Required               |

The normal `sh backend/mvnw -B -f backend/pom.xml clean verify` executes every required Java integration class, including tests that explicitly boot demo configurations. Its reports and JAR are archived before building the demo with `-Pdemo -DskipTests clean package`. CI does not rerun the same Java suites: it verifies the separate demo UI/inventory and runs its browser scenarios. Maven `validate` owns Java formatting/conventions; packaging compiles both frontends. Missing or skipped essential scenarios, reports, inventories and operational failures remain blocking. Coverage is diagnostic and does not replace required tests; packaged subprocesses are not attributed to JaCoCo coverage.

The workflow uses one job on PRs and pushes to `main`; manual runs select `ci` or `full`. It avoids a separate frontend bootstrap step, uses Maven/npm/daily audit caches, and cancels superseded runs. Standalone Maven UI builds still install from the frontend lockfile. Tool caches contain verified archives/databases, not virtual environments tied to a previous runner's Python path. `--prepare-browser` explicitly permits installation of Chromium system libraries in the hosted runner. Without that flag, prepare libraries yourself using `cd frontend && npm ci && npm exec -- playwright install --with-deps chromium`. Gate/supervisor cleanup targets only resources created by started stages, including failure/cancellation.

Maven is invoked through `sh` because the Wrapper is tracked without an executable bit. The environment checker prints the failing prerequisite's diagnostic to the CI console. Artifacts include logs, runner reports, coverage and hashes under `artifacts/<run>/`; `summary.json` identifies scope and outcome. Generated source snapshots and raw coverage execution data are excluded from uploads. Retention is manual locally and 14 days for CI: preserve identified packages, images/JARs and backups needed for evidence/recovery before deleting any specific obsolete output. No automatic local artifact deletion is performed.

Voluntary fixes use `npm --prefix tools/quality run format`, `python3 scripts/quality_tools.py ruff format .`, and `cd backend && sh mvnw spotless:apply`. CI only checks. Prettier, ESLint/Hooks, Checkstyle, Spotless, Ruff, SpotBugs/FindSecBugs and ArchUnit rules/versions are fixed in their source configurations and lockfiles; new tools are development dependencies, not runtime additions.

## Dependency policy

Both modes audit npm production/build dependencies and the actual normal JAR's embedded libraries. Full mode additionally audits the demo JAR and the exact app/PostgreSQL images. High/critical applicable findings block unless an exact reviewed exception names advisory, package, version, target, actual owner, reason and expiration within 30 days. No blanket `ignore-unfixed` or automatic renewal is allowed.

Scanner errors and incomplete inventories fail. Trivy must successfully check database freshness or reuse identical metadata last confirmed within 24 hours; future, changed, unconfirmed or expired cache data fails. The upstream database build date is recorded separately. Tools are checksum-pinned and npm uses lockfiles. Audit JSON records target identities, findings and decisions. This detects known issues within the scanner's coverage and does not establish absolute absence of vulnerabilities.

## Change and review policy

Keep mechanical formatting, language translation, and responsibility/behavior changes separately reviewable through patches or authorized commits. Preserve unrelated changes already present. Do not change API contracts, synthetic fixture data, mandatory scenario names, or existing migration bytes to satisfy lint. Add tests when a changed boundary reveals a meaningful uncovered risk; preserve existing deterministic regressions and required discovery.

All explanatory source comments, Javadoc/JSDoc and docstrings, active project READMEs, and the distributed installation guide must be in clear English. Preserve UI/API strings, literals, commands, identifiers, URLs, and Caderno's name. Quote Portuguese UI labels exactly when explaining actions. Existing Flyway migrations, third-party/generated files, legal notices, and immutable historical evidence retain original bytes. New migration comments must be English. Record reviewed files and justified exceptions in the language inventory; word searches alone do not prove language quality.

Explicitly include new distributable source/configuration/documentation in `config/delivery-files.json`; check links and isolated snapshots. Never include personal configuration, archive contents, passwords, dumps, raw logs, or container archives. Vulnerability exceptions require an actual owner/reviewer, exact advisory/package/version/target, technical reason, and expiration within 30 days. Do not invent contacts or silently renew exceptions.

Explain observable changes, preserve transactions/SQL, examine Hook timing/cancellation, and report commands/exits. Distinguish passing checks, infrastructure failures, and diagnostic metrics. Workflow YAML does not establish branch protection or remote execution. License ownership and a functional private security channel remain owner decisions before public preparation can close.
