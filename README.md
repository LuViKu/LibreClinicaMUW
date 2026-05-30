LibreClinica MUW Ophthalmology
==============================

_Institutional eCRF / EDC platform for the Department of Ophthalmology and Optometry, Medical University of Vienna._

This repository is an institutional fork of [LibreClinica](https://libreclinica.org) — the community-driven open-source successor of OpenClinica — adapted for use by the **Department of Ophthalmology and Optometry, Medical University of Vienna (MUW)** as the department's eCRF (electronic Case Report Form) and Clinical Data Management platform.

This fork is undergoing a planned multi-phase backend modernization. See [MIGRATION.md](MIGRATION.md) for the technical plan, target stack, and phase status, and [docs/development/modernization/decision-record.md](docs/development/modernization/decision-record.md) for the strategic decisions behind it.

### Status

| | |
|---|---|
| Modernization phase | **Phase 0 — Safety net** *(in progress)* |
| Current stack | Spring 5.1.4 · Hibernate 5.4.2 · Java 11 · Tomcat 9 · PostgreSQL 13/14 · `javax.*` |
| Target stack | Spring Boot 3 · Hibernate 6 · Java 21 · embedded Tomcat · PostgreSQL 14+ · `jakarta.*` |
| Posture | Hard fork from `reliatec-gmbh/LibreClinica:lc-develop` (manual cherry-picks) |
| License | LGPL (see [COPYING.LESSER](COPYING.LESSER) and [LICENSE](LICENSE)) |
| Build version | `1.4.0rc1-muw` |

### Quick start (local development)

```sh
docker compose up --build
```

Then open http://127.0.0.1:8080/ — Tomcat will redirect to the application at `/LibreClinica/`. The bundled `marlonb/mailcrab` SMTP service exposes its inbox UI at http://127.0.0.1:1080.

For full installation guidance (Tomcat configuration, Postgres setup, LDAP integration, reverse-proxy TLS) see [LibreClinica's upstream documentation](https://libreclinica.org/documentation/install.html) — the deployment model is unchanged in this fork during Phase 0/A.

### System requirements (current, pre-modernization)

| LibreClinica | Application Server | Java       | Database                     | Schema Changeset |
|--------------|--------------------|------------|------------------------------|------------------|
| v1.4.0       | Tomcat 9           | OpenJDK 11 | PostgreSQL 13, PostgreSQL 14 | lc-1.4.0         |

After Phase C the deployment model changes to executable JAR + embedded Tomcat + Java 21.

> **Note:** the LibreClinica SOAP web API (`ws/` module) was removed in Phase B.4 (PR #31, 2026-05-29) — upstream had it marked "legacy, not tested, not actively developed", and there is no active SOAP consumer at MUW Ophthalmology. See [MIGRATION.md § Phase B](MIGRATION.md#phase-b--java-21--spring-6--jakarta-cliff).

### Contribution & development

The institutional team follows the upstream git-flow branching strategy:

- `master` — production-equivalent
- `lc-develop` — integration
- `feature/*`, `release/*`, `hotfix/*` — short-lived

For modernization work, branch names follow `feature/muw-modernization-<phase>-<topic>`.

CI runs on every push (`.github/workflows/build.yml`) — Maven build + unit tests across JDK 8 + 11, plus a Compose smoke test. Dependabot manages weekly dependency updates (`.github/dependabot.yml`).

### Acknowledgements

This software is built on the work of:

- **LibreClinica community** — primarily maintained by ReliaTec GmbH (Ralph Heerlein, Christian Hänsel, Otmar Bayer), with contributions from:
  - Julia Bley, University Hospital RWTH Aachen
  - Thomas Hillger, University Hospital RWTH Aachen
  - Gerben Rienk Visser, Trial Data Solutions
  - Tomas Skripcak, DKFZ Partner Site Dresden — member of the German Cancer Consortium (DKTK)
- **OpenClinica** — LibreClinica was forked in 2019 from [OpenClinica 3.14](https://github.com/OpenClinica/OpenClinica/commit/425de43caf8e7afcbf66713ad2fb6b83062d66ef)

The institutional MUW fork retains the LGPL license and all upstream copyright notices.

### Security

To report a security issue privately, see [SECURITY.md](SECURITY.md).
