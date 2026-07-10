# Claude — repo guide

Quick orientation for AI assistants working in this repo. Human contributors: see [README.md](README.md) and [MIGRATION.md](MIGRATION.md).

## What this repo is

**LibreClinicaMUW** — institutional fork of [LibreClinica](https://libreclinica.org) (community successor of OpenClinica 3.14) maintained by the Department of Ophthalmology and Optometry, Medical University of Vienna, for in-house clinical-trial eCRF use. As of 2026-06-26 it is a **released, independent fork**: it no longer syncs (merges or cherry-picks) from upstream LibreClinica. Authored/maintained by Lukas Kuchernig; LGPL v3.

**Currently undergoing a planned multi-phase backend modernization.** Read [MIGRATION.md](MIGRATION.md) before suggesting structural changes. Strategic decisions live in [docs/development/modernization/decision-record.md](docs/development/modernization/decision-record.md).

## Stack at a glance

| Layer | Now | Target (post-modernization) |
|-------|-----|----|
| Java | **25** (build + runtime, per Dockerfile; 21→25 bump 2026-07) | (achieved — exceeds the original Java 21 target) |
| Framework | Spring Boot 3.5 + Java config (Spring 6.1.18 + Security 6.3.6; residual security XML) | (achieved — Phase C) |
| Web | JSP + SiteMesh + Spring MVC + ~295 legacy servlets; Vue 3 SPA live for several workspaces (Phase E); jmesa evicted | Phase E ongoing: listing-page SPA conversion (per-table) |
| Persistence | Hibernate 6.4 (jakarta) + Liquibase 3.6.3 + PostgreSQL 14 | Liquibase 4 (Phase D-Libs, deferred — heritage `modifyColumn` blocker) + PostgreSQL 14+ |
| Packaging | WAR in Tomcat 10 (jakarta servlet 6) | executable JAR (optional follow-up — WAR retained) |
| Namespace | `jakarta.*` | (achieved) |
| Java packages | `at.ac.meduniwien.ophthalmology.libreclinica.*` | (achieved — DR-010) |
| Build group | `at.ac.meduniwien.ophthalmology.libreclinica` | (unchanged) |
| Version | `1.5.0-beta.5-muw` | continues with `-muw` suffix |

## Build & run

Local dev uses Docker Compose:

```sh
docker compose up --build
```

App available at http://127.0.0.1:8080/ (redirects to `/LibreClinica/`). Mail UI at http://127.0.0.1:1080.

Maven build (no local mvn needed — use the Docker image declared in [Dockerfile](Dockerfile)):

```sh
docker run --rm \
  -v "$(pwd)":/app \
  -v "$(pwd)/.m2-cache":/root/.m2 \
  -w /app \
  maven:3-eclipse-temurin-25 \
  mvn -B -DskipTests=true -ntp clean compile
```

`.m2-cache/` is git-ignored and persists Maven downloads (first build ~10 min, subsequent ~2 min).

Unit tests run by default (`mvn test`) — 33 pass across 8 pure-unit test classes in `core/` and `web/`. To skip: `mvn -DskipTests=true …` for fast iteration.

Integration tests (11 DB-dependent test classes excluded from the default run) need a dedicated PostgreSQL **separate from the compose `db` service** — the compose `db` is for the app (DB name `libreclinica`); tests want `openclinica-TEST`. Run them on an isolated network:

```sh
docker network create lc-test-net 2>/dev/null || true
docker run -d --rm --name lc-test-pg --network lc-test-net \
  -e POSTGRES_USER=clinica -e POSTGRES_PASSWORD=clinica \
  -e POSTGRES_DB=openclinica-TEST \
  postgres:14-alpine

docker run --rm --network lc-test-net \
  -v "$(pwd)":/app -v "$(pwd)/.m2-cache":/root/.m2 -w /app \
  maven:3-eclipse-temurin-25 \
  mvn -B -ntp -pl core -am -P integration-tests -Ddb.test=lc-test-pg test

docker stop lc-test-pg && docker network rm lc-test-net
```

Schema bootstrap works via `SpringLiquibase` in `applicationContext-core-db.xml`. **Expected result: 63 tests pass, 0 errors, 0 failures, 0 skipped.** (Phase 0.2 + 0.3, 2026-05-28.) See [MIGRATION.md § Phase 0](MIGRATION.md).

## CI

`.github/workflows/build.yml` runs `mvn package` (JDK 8 + 11 matrix) on every push to `lc-develop`, `master`, `feature/**`, `release/**`, `hotfix/**`, plus a compose smoke test. Surefire reports uploaded on failure.

Dependabot updates weekly (`.github/dependabot.yml`), grouped by ecosystem (Spring, Jackson, Hibernate, Apache Commons, Logback).

## Repo layout

| Path | Contents |
|------|----------|
| [`core/`](core/) | Domain entities, services, DAOs, Hibernate mappings, Liquibase migrations (`core/src/main/resources/migration/`) |
| [`web/`](web/) | Spring MVC controllers, 295 legacy servlets, 413 JSPs, static assets — produces `LibreClinica-web.war` |
| [`odm/`](odm/) | CDISC ODM 1.3 JAXB bindings |
| [`docs/`](docs/) | Jekyll-style static documentation |
| [`docs/development/modernization/`](docs/development/modernization/) | Decision records, modernization-specific docs |
| [`docs/development/study-modules/`](docs/development/study-modules/) | SPA study-module SPI — start with [authoring.md](docs/development/study-modules/authoring.md) when writing a new module |
| [`docker/`](docker/) | Runtime config (`datainfo.properties`, logback.xml) |

Java packages live under `at.ac.meduniwien.ophthalmology.libreclinica.*` since Phase B.11 (2026-05-29). The heritage `org.akaza.openclinica.*` namespace was renamed in commit `4f531f9f7` per DR-010. Liquibase changelogs under `core/src/main/resources/migration/` keep historical `org.akaza.openclinica` references in column-comment strings — those are historical data, not class refs, and changing them would break checksum validation.

## Branching

git-flow: `master` (production), `lc-develop` (integration), short-lived `feature/*`, `release/*`, `hotfix/*`. Modernization branches: `feature/muw-modernization-<phase>-<topic>`.

## Things to know

- **Test suite is thin** — 21 unit tests, default-skipped. Don't assume code is well-tested. Phase 0 work in progress to flip the default + add critical-path integration tests.
- **Database migrations are versioned** — every change adds a new Liquibase changeset under `core/src/main/resources/migration/`, never edit existing changesets. Institutional changes go in `migration/lc-muw-<yyyy-mm-dd>-<topic>.xml`.
- **Released, independent fork** — since 2026-06-26 the project no longer syncs from upstream LibreClinica (no cherry-picks; don't suggest pulling upstream changes). See [DR-003](docs/development/modernization/decision-record.md#dr-003--hard-fork-from-upstream-reliateclibreclinica) for the original fork rationale; the cherry-pick workflow it describes is now historical.
- **Clinical-data system** — don't ship unverified changes. Bump dependency versions one batch at a time, verify with `mvn compile` (or `mvn test` post Phase 0).
- **`docs/manuals/`** is for end-user documentation; **`docs/development/`** is for developers; **`MIGRATION.md`** is the modernization spine.
- **Retinal-inference can run remotely on a GPU host** — see [DR-022](docs/development/modernization/decision-record.md#dr-022--remote-stateless-gpu-sidecar-for-retinal-inference) + the [runbook](docs/development/modernization/retinal-inference-remote-deployment.md). Single-host dev compose keeps working when `core.retinalInference.remotePushUrl` is blank.

## When making suggestions

- Modernization moves: check whether the suggestion is in scope for the current phase per [MIGRATION.md](MIGRATION.md). Surface conflicts.
- New features: ask before adding — the team is mid-modernization and feature work increases the modernization surface area.
- Library bumps: cross-check against [MIGRATION.md § Phase A](MIGRATION.md#phase-a--spring-5x-hardening-cve-patches-no-namespace-migration) for the target version.
- Schema changes: append a Liquibase changeset under `migration/lc-muw-*`. Never edit existing changesets.
