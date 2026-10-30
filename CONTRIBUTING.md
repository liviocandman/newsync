# Contributing to Caderno

Use Linux/WSL amd64, JDK 25, Node 24.15–24.x, Python 3.11+, Docker Engine 28+, Compose, and Chromium system libraries. Versions actually exercised and limitations are recorded in [F-03 evidence](docs/evidencias/F-03.md).

`backend/` contains the Maven app, feature packages, immutable Flyway migrations, and fixtures. `frontend/` contains React and browser tests. `scripts/` contains operational helpers and the single gate. `tools/quality/` isolates development tools; `config/` holds the delivery manifest, rules, download checksums, and audit policy. Design/specifications/reviewed history are under `docs/`.

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

Run the complete local/CI gate before acceptance:

```bash
python3 scripts/verify
```

The [quality guide](docs/quality.md) covers voluntary formatting, coverage boundaries, audits, metrics, snapshots, and retention. The [development guide](docs/11-guia-de-desenvolvimento.md) covers app/database operation. The gate never loads personal `.env` files, stages files, commits, pushes, or publishes.

## Change and review policy

Keep mechanical formatting, language translation, and responsibility/behavior changes separately reviewable through patches or authorized commits. Preserve unrelated changes already present. Do not change API contracts, synthetic fixture data, mandatory scenario names, or existing migration bytes to satisfy lint. Add tests when a changed boundary reveals a meaningful uncovered risk; preserve existing deterministic regressions and required discovery.

All explanatory source comments, Javadoc/JSDoc and docstrings, active project READMEs, and the distributed installation guide must be in clear English. Preserve UI/API strings, literals, commands, identifiers, URLs, and Caderno's name. Quote Portuguese UI labels exactly when explaining actions. Existing Flyway migrations, third-party/generated files, legal notices, and immutable historical evidence retain original bytes. New migration comments must be English. Record reviewed files and justified exceptions in the language inventory; word searches alone do not prove language quality.

Explicitly include new distributable source/configuration/documentation in `config/delivery-files.json`; check links and isolated snapshots. Never include personal configuration, archive contents, passwords, dumps, raw logs, or container archives. Vulnerability exceptions require an actual owner/reviewer, exact advisory/package/version/target, technical reason, and expiration within 30 days. Do not invent contacts or silently renew exceptions.

Explain observable changes, preserve transactions/SQL, examine Hook timing/cancellation, and report commands/exits. Distinguish passing checks, infrastructure failures, and diagnostic metrics. Workflow YAML does not establish branch protection or remote execution. License ownership and a functional private security channel remain owner decisions before public preparation can close.
