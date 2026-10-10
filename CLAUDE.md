# Claude — repo guide

Quick orientation for AI assistants working in this repo. Human contributors: see [README.md](README.md) and [MIGRATION.md](MIGRATION.md).

## What this repo is

**LibreClinicaMUW** — institutional fork of [LibreClinica](https://libreclinica.org) (community successor of OpenClinica 3.14) maintained by the Department of Ophthalmology and Optometry, Medical University of Vienna, for in-house clinical-trial eCRF use. As of 2026-06-26 it is a **released, independent fork**: it no longer syncs (merges or cherry-picks) from upstream LibreClinica. Authored/maintained by Lukas Kuchernig; LGPL v3.

**Currently undergoing a planned multi-phase backend modernization.** The Spring Boot 4 phase (DR-037) is built through stage 2 on `spike/muw-spring-boot-4`, not yet on lc-develop. Read [MIGRATION.md](MIGRATION.md) before suggesting structural changes. Strategic decisions live in [docs/development/modernization/decision-record.md](docs/development/modernization/decision-record.md).

## Stack at a glance

| Layer | Now | Target (post-modernization) |
|-------|-----|----|
| Java | **25** (build + runtime, per Dockerfile; 21→25 bump 2026-07) | (achieved — exceeds the original Java 21 target) |
| Framework | **Spring Boot 4.1.1** + Java config (Spring 7.0.x + Security 7.1.x; Jackson 3 / `tools.jackson`, one shared mapper in `core.util.Json`; residual security XML). Boot 4 is built on `spike/muw-spring-boot-4` (DR-037, stages 1–2; stage 3, live-stack verification and merge, open); lc-develop is still on Boot 3.5.16 / Spring 6.2.19 / Security 6.5.11 | (achieved on the branch — Phase C, then Phase F) |
| Web | JSP + Spring MVC + 214 servlet registrations; Vue 3 SPA live for several workspaces (Phase E); jmesa evicted | Phase E ongoing: listing-page SPA conversion (per-table) |
| Persistence | Hibernate 7.4.x (jakarta, Boot-managed; `save`/`saveOrUpdate` replaced by `SessionSaveSupport`, see [the call-site review](docs/development/modernization/spring-boot-4-hibernate-7-call-sites.md)) + Liquibase 4.31.1 (pinned; LAX parsing + serial shims) + PostgreSQL 14 in production, 17 in dev/test/CI | (Liquibase 4 achieved — Phase D-Libs, 2026-10) + PostgreSQL 17 (runbook: docs/operations/postgresql-17-upgrade.md) |
| Packaging | WAR in Tomcat 11 (Jakarta EE 11, jakarta servlet 6.1; the Docker base image is a Tomcat 11 image) | executable JAR (optional follow-up — WAR retained) |
| Namespace | `jakarta.*` | (achieved) |
| Java packages | `at.ac.meduniwien.ophthalmology.libreclinica.*` | (achieved — DR-010) |
| Build group | `at.ac.meduniwien.ophthalmology.libreclinica` | (unchanged) |
| Version | `1.5.0-beta.16-muw` | continues with `-muw` suffix |

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

**On Windows, do not build over the bind mount.** Docker Desktop's filesystem boundary makes the command above take ~20 min at ~10% CPU — the WAR step alone copies ~1,800 webapp files and ~200 jars across it. Copy the tree into container-local storage first and the same build takes ~2 min:

```sh
# wrapper: tar the source in, build there
mkdir -p /build && tar -C /src -cf - --exclude=.git --exclude=target --exclude=node_modules . | tar -C /build -xf -
cd /build && mvn "$@"
```

mounting the worktree read-only at `/src` and `.m2-cache` at `/root/.m2`.

Unit tests run by default (`mvn test`). On `spike/muw-spring-boot-4` (2026-10-09): **core 538, web 1451**. To skip: `mvn -DskipTests=true …` for fast iteration.

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

The **`web` module's database ITs are separate and much larger** (~1400 tests in `web/src/test/**/*DatabaseIT.java`). They use Testcontainers, which starts its own `postgres:14-alpine` per IT class, so they need no external database — only a Docker socket. Two steps, because step 1 must install the other modules first:

```sh
mvn -B -ntp -q -DskipTests=true -DskipSpa=true -pl odm,core,docs -am install
mvn -B -ntp -DskipSpa=true -P integration-tests -pl web test
```

`-DskipSpa=true` is required even for `mvn test`: `frontend-maven-plugin` binds to `generate-resources`, which runs before `test`.

**Run the gate on Linux.** Several tests fail natively on Windows for path reasons only and pass in a container — `PublicUploadControllerDatabaseIT` (its in-test DICOM stub builds JSON by string concatenation and embeds a raw path, so `E:\...` emits invalid JSON escapes), `DatasetExportCharacterisationDatabaseIT` (writes to a literal `/tmp/...`) and core's `DicomDescribeClientTest` (asserts a POSIX path string). To run them in a Linux container, add the host Docker socket:

```sh
-v "//var/run/docker.sock:/var/run/docker.sock" --add-host host.docker.internal:host-gateway -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true
```

## CI

`.github/workflows/build.yml` runs `mvn package` (single JDK 25 entry, matching the Dockerfile) on every push to `lc-develop`, `main`, `release/**`, `hotfix/**`, plus the three Python sidecar suites, a Testcontainers integration-test job and a compose smoke test. Surefire reports uploaded on failure.

`codeql.yml` and `security.yml` run on pushes to `lc-develop` and `main` and on a schedule. Two things about them are worth knowing: **code-scanning alerts reflect `main`**, so a fix only closes its alert after the release reaches `main` and is rescanned; and **gitleaks scans only the push range on a push event but the full history on a schedule**, so a finding in an old commit shows up on the nightly run and never on a push. `fix/**` and `gate/**` branches do not build automatically — dispatch with `gh workflow run <file> --ref <branch>`.

Dependabot updates weekly (`.github/dependabot.yml`), grouped by ecosystem (Spring, Jackson, Hibernate, Apache Commons, Logback).

## Repo layout

| Path | Contents |
|------|----------|
| [`core/`](core/) | Domain entities, services, DAOs, Hibernate mappings, Liquibase migrations (`core/src/main/resources/migration/`) |
| [`web/`](web/) | Spring MVC controllers, 214 servlet registrations, 413 JSPs, static assets — produces `LibreClinica-web.war` |
| [`odm/`](odm/) | CDISC ODM 1.3 JAXB bindings |
| [`docs/`](docs/) | Jekyll-style static documentation |
| [`docs/development/modernization/`](docs/development/modernization/) | Decision records, modernization-specific docs |
| [`docs/development/study-modules/`](docs/development/study-modules/) | SPA study-module SPI — start with [authoring.md](docs/development/study-modules/authoring.md) when writing a new module |
| [`docker/`](docker/) | Runtime config (`datainfo.properties`, logback.xml) |

Java packages live under `at.ac.meduniwien.ophthalmology.libreclinica.*` since Phase B.11 (2026-05-29). The heritage `org.akaza.openclinica.*` namespace was renamed in commit `4f531f9f7` per DR-010. Liquibase changelogs under `core/src/main/resources/migration/` keep historical `org.akaza.openclinica` references in column-comment strings — those are historical data, not class refs, and changing them would break checksum validation.

## Branching

git-flow: `master` (production), `lc-develop` (integration), short-lived `feature/*`, `release/*`, `hotfix/*`. Modernization branches: `feature/muw-modernization-<phase>-<topic>`.

## Things to know

- **Test coverage is uneven, not thin** — 1301 unit tests run by default, plus a Testcontainers database suite of ~1400 in `web/src/test/**/*DatabaseIT.java`. The modern SPA-facing controllers are well covered; the heritage servlets and JSPs are largely not. Check before assuming a legacy path is tested.
- **`@SuppressWarnings("all")` sits on ~1,244 main-source Java files, and javac ignores it; VS Code's compiler (Eclipse JDT) honours it.** "all" is not a javac lint key, so the annotation hides warnings only in the IDE, never from `javac -Xlint`. Removing it therefore cannot change javac's output, and doing so proves nothing (an earlier note here drew the opposite conclusion from exactly that test). Do not add more; remove it from a file when you work on it.
- **A default build hides deprecations.** It prints only "Some input files use or override a deprecated API". To measure, compile with `-Xlint:deprecation` and raise `-Xmaxwarns` (javac prints at most 100 by default). Measured on Hibernate 6.4.10, 2026-09-30: core has 128 deprecation warnings. They include exactly the 49 Hibernate calls CodeQL flags (`createNativeQuery(String)` 30, `Session.createQuery(String)` 15, `save` 2, `saveOrUpdate` 2) and 61 uses of `@GenericGenerator`'s `strategy()`. So the 49 are present-day deprecations, not forward-looking; the Hibernate 6.6 move (`chore/muw-libs-openpdf-boot-bom`) retires 47 of them.
- **The `pages` dispatcher has no `String` and no `Resource` message converter** (its list is `[ByteArray, Marshalling (JAXB), Jackson]`, `WebMvcConfig.apiMessageConverters`). A controller there must not take a text `@RequestPart`, nor return a `String` with a non-JSON content type or a `Resource`: those answer 500/415. Read multipart text fields with `@RequestParam`, write `byte[]`. Tests build MockMvc on the production list (`ProductionMvc` / `PagesDispatcherMvc`), not on MockMvc's defaults, which hide this. Do not add a global `StringHttpMessageConverter`: it changes how every `@ResponseBody String` is written.
- **JSON is Jackson 3 (`tools.jackson`) on one mapper** (`core.util.Json.mapper()`, `Json.strict()`), configured to read and write what Jackson 2 did; `JsonWireContractTest` and the golden tests pin the wire and the stored JSON. Do not `new ObjectMapper()`/`JsonMapper` elsewhere.
- **Database migrations are versioned** — every change adds a new Liquibase changeset under `core/src/main/resources/migration/`, never edit existing changesets. Institutional changes go in `migration/lc-muw-<yyyy-mm-dd>-<topic>.xml`.
- **Liquibase is pinned at 4.31.1 and must never go back to 3.x, nor to 4.33.0+ or 5.x without a re-check.** The first start on 4.x rewrites every stored checksum from `8:` to `9:`. Liquibase 3.6.3 does not recognise `9:` checksums: started on an upgraded database, it re-runs 25 `runOnChange` changesets and inserts duplicate data, so an upgraded database is rolled back only by restoring the pre-upgrade dump ([deploy runbook §5](docs/operations/deploy-runbook.md#5-rollback)). 4.33.0 changes the checksum of every `valueDate` changeset and fails startup on every existing database; `Liquibase363UpgradeDatabaseIT` fails on it. 5.x is FSL-licensed. Boot 4.1's BOM manages Liquibase 5.x, so on the Boot 4 branch the 4.31.1 pin is an explicit override that must stay. There is no Liquibase Maven plugin; only the app runs the changelog. Details: [liquibase-4-spike-2026-09-30.md](docs/development/modernization/liquibase-4-spike-2026-09-30.md).
- **Released, independent fork** — since 2026-06-26 the project no longer syncs from upstream LibreClinica (no cherry-picks; don't suggest pulling upstream changes). See [DR-003](docs/development/modernization/decision-record.md#dr-003--hard-fork-from-upstream-reliateclibreclinica) for the original fork rationale; the cherry-pick workflow it describes is now historical.
- **Clinical-data system** — don't ship unverified changes. Bump dependency versions one batch at a time, verify with `mvn compile` (or `mvn test` post Phase 0).
- **`docs/manuals/`** is for end-user documentation; **`docs/development/`** is for developers; **`MIGRATION.md`** is the modernization spine.
- **The retinal preprocess token is per-host.** `setup-ubuntu-host.sh` mints it and writes it to both `/etc/libreclinica/env` (`RETINAL_INFERENCE_PREPROCESS_TOKEN`, read by the sidecar) and `core.retinalInference.preprocessToken` (read by the app); `deploy/compose.production.yaml` has no fallback and refuses to start without it. Run the setup script before a restart, not after.
- **logback is pinned at 1.5.34 and must not go to 1.5.37+.** That release removed the Janino `<if condition=…>` attributes that every `logLocation` branch in `core/src/main/resources/logback.xml` depends on; moving past it needs that configuration migrated to `<condition>` elements first.
- **Retinal-inference can run remotely on a GPU host** — see [DR-022](docs/development/modernization/decision-record.md#dr-022--remote-stateless-gpu-sidecar-for-retinal-inference) + the [runbook](docs/development/modernization/retinal-inference-remote-deployment.md). Single-host dev compose keeps working when `core.retinalInference.remotePushUrl` is blank.

## When making suggestions

- Modernization moves: check whether the suggestion is in scope for the current phase per [MIGRATION.md](MIGRATION.md). Surface conflicts.
- New features: ask before adding — the team is mid-modernization and feature work increases the modernization surface area.
- Library bumps: cross-check against [MIGRATION.md § Phase A](MIGRATION.md#phase-a--spring-5x-hardening-cve-patches-no-namespace-migration) for the target version.
- Schema changes: append a Liquibase changeset under `migration/lc-muw-*`. Never edit existing changesets.
