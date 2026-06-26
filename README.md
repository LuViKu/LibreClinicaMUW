LibreClinica MUW Ophthalmology
==============================

_Institutional eCRF / EDC platform for the Department of Ophthalmology and Optometry, Medical University of Vienna._

This repository is an institutional fork of [LibreClinica](https://libreclinica.org) — the community-driven open-source successor of OpenClinica — adapted for use by the **Department of Ophthalmology and Optometry, Medical University of Vienna (MUW)** as the department's eCRF (electronic Case Report Form) and Clinical Data Management platform.

It is authored and maintained by **Lukas Kuchernig** (MUW Ophthalmology). As of June 2026 it is an **independent (released) fork** — it no longer tracks, merges, or cherry-picks changes from upstream LibreClinica.

This fork is undergoing a planned multi-phase backend modernization. See [MIGRATION.md](MIGRATION.md) for the technical plan, target stack, and phase status, and [docs/development/modernization/decision-record.md](docs/development/modernization/decision-record.md) for the strategic decisions behind it.

### Status

| | |
|---|---|
| Maintainer | **Lukas Kuchernig** — lead developer, MUW Ophthalmology backend modernization |
| Modernization | Phases B–D complete (Java 21 · Spring 6 · Jakarta · Shibboleth SSO); Phase E (Vue SPA) in progress |
| Current stack | Java 21 · Spring 6.1.18 · Spring Security 6.3.6 · Hibernate 5.6.15 · Tomcat 10 (WAR, Jakarta Servlet 6) · PostgreSQL 13/14 · `jakarta.*` |
| Target stack | Spring Boot 3 · Hibernate 6 · embedded Tomcat (executable JAR) · PostgreSQL 14+ |
| Posture | **Independent (released) fork** — no longer tracks upstream LibreClinica; no cherry-picks planned |
| License | LGPL v3 (see [COPYING.LESSER](COPYING.LESSER) and [LICENSE](LICENSE)) |
| Build version | `1.5.0-beta.4-muw` |

### Quick start (local development)

```sh
docker compose up --build
```

Then open http://127.0.0.1:8080/ — Tomcat will redirect to the application at `/LibreClinica/`. The bundled `marlonb/mailcrab` SMTP service exposes its inbox UI at http://127.0.0.1:1080.

For the heritage installation model (Tomcat configuration, Postgres setup, LDAP integration, reverse-proxy TLS) see [LibreClinica's original documentation](https://libreclinica.org/documentation/install.html); MUW-specific deployment notes live under [`docs/`](docs/). Shibboleth SSO is the supported authentication path for this fork (see the modernization decision records).

### System requirements

| Version          | Application Server | Java       | Database                     |
|------------------|--------------------|------------|------------------------------|
| 1.5.0-beta.4-muw | Tomcat 10          | OpenJDK 21 | PostgreSQL 13, PostgreSQL 14 |

The post-modernization target deployment is an executable JAR with embedded Tomcat.

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

### Authorship & licensing

The MUW institutional fork is authored and maintained by **Lukas Kuchernig** (Department of Ophthalmology and Optometry, Medical University of Vienna). Institutional contributions are copyright © 2026 Medical University of Vienna; see [AUTHORS](AUTHORS).

This fork **retains the GNU Lesser General Public License (v3)** and all upstream copyright notices. As a derivative work of LibreClinica / OpenClinica it remains LGPL-licensed. As of June 2026 it is an **independent (released) fork**: it no longer tracks, merges, or cherry-picks changes from upstream LibreClinica.

### Security

To report a security issue privately, see [SECURITY.md](SECURITY.md).
