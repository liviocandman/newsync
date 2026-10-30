# Security

Caderno is a local personal reader with one shared archive and no user authentication. The validated configuration publishes only on loopback. Anyone able to reach the API can access that archive. Origin/CSRF checks, sanitization, and SSRF protections do not authenticate users. Public exposure is outside the supported perimeter.

No released versions or security maintenance window have been designated. Development uses JDK 25, Node 24, and PostgreSQL 18; these toolchain choices do not constitute a supported release policy.

## Private reports: decision pending

A functioning private reporting channel and responsible maintainer have not been supplied. No email address, private advisory endpoint, or response-time commitment is asserted. Public preparation remains open until the repository owner supplies and verifies that channel. Do not put credentials, personal feed contents, dumps, or private installation data in public issues or artifacts. This document does not claim an unspecified channel is usable.

## Dependency checks

The gate inventories npm runtime/build dependencies, final normal/demo JAR libraries, and exact app/PostgreSQL images selected by distribution Compose. Applicable high/critical findings block acceptance unless an exact reviewed, unexpired exception exists. Scanner/inventory failure and stale vulnerability data also block. [Quality controls](docs/quality.md) describe reports and detection limits. A passing audit does not prove absolute absence of vulnerabilities.
