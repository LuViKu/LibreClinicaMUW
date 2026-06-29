# LibreClinicaMUW — Application Manual (SPA)

End-user manual for the **LibreClinicaMUW** web application (the Vue single-page
app, Phase E UI), organized by role. Each role chapter walks its day-to-day
workflows as: **goal → numbered steps → screenshot → notes**.

> **Status: handoff draft.** This markdown + the generated screenshots are the
> source for a design agent to produce the final, branded manual. Prose is
> deliberately plain and screenshot references are stable filenames so the
> design pass can restyle without rewriting.

## Audience & roles

The app maps the seven legacy LibreClinica roles onto **five end-user roles**.
Read the chapter(s) for your role; Administrators may want all of them.

| Role | Chapter | In one line |
|------|---------|-------------|
| Administrator | [role-administrator.md](role-administrator.md) | System + study setup: users, sites, studies, CRFs, rules, config, audit. Superset of every other role. |
| Data Manager | [role-data-manager.md](role-data-manager.md) | Builds and runs the study: CRFs, events, rules, groups, discrepancy oversight, data export. |
| Monitor | [role-monitor.md](role-monitor.md) | Source data verification, discrepancy notes/queries, read-only review, audit log. |
| Investigator | [role-investigator.md](role-investigator.md) | Enrolls subjects, enters CRF data, signs casebooks, reviews retinal results. |
| CRC (Clinical Research Coordinator) | [role-crc.md](role-crc.md) | Day-to-day data entry; inherits the Investigator surface. |

Start with [00-getting-started.md](00-getting-started.md) for login, navigation,
and the study/site context shared by every role.

## How the screenshots are generated

Figures live under [`screenshots/<role>/`](screenshots/) and are produced by a
repeatable Playwright harness, so the design agent can regenerate every figure
(e.g. after a restyle or with different demo data) without manual capturing.

Harness: [`web/src/spa/tests/manual/capture-manual.spec.ts`](../../../web/src/spa/tests/manual/capture-manual.spec.ts).

**Dedicated manual accounts.** A `context="demo"` Liquibase seed
([`lc-muw-2026-06-28-seed-manual-accounts.xml`](../../../core/src/main/resources/migration/lc-muw-2026-06-28-seed-manual-accounts.xml))
creates one account per role on the Default Study, all with password `12345678`:

| Username | Password | Role |
|----------|----------|------|
| `manual_admin` | `12345678` | Administrator |
| `manual_dm` | `12345678` | Data Manager |
| `manual_monitor` | `12345678` | Monitor |
| `manual_investigator` | `12345678` | Investigator |
| `manual_crc` | `12345678` | CRC |

These seed **only** when Liquibase runs with the `demo` context (dev / demo /
capture environments) — never in production. After pulling this branch,
restart the stack (or re-run Liquibase) so the accounts exist, then:

```sh
cd web/src/spa
pnpm dev          # serve the SPA at http://127.0.0.1:5173 (backend reachable via proxy, demo data seeded)

# in another shell — credentials come from env, one real login per role:
MANUAL_CAPTURE=1 \
  MANUAL_ADMIN_USER=manual_admin     MANUAL_ADMIN_PASS=12345678 \
  MANUAL_DM_USER=manual_dm           MANUAL_DM_PASS=12345678 \
  MANUAL_MONITOR_USER=manual_monitor MANUAL_MONITOR_PASS=12345678 \
  MANUAL_INV_USER=manual_investigator MANUAL_INV_PASS=12345678 \
  MANUAL_CRC_USER=manual_crc         MANUAL_CRC_PASS=12345678 \
  pnpm exec playwright test tests/manual/capture-manual.spec.ts
```

A role with no credentials set is skipped, so you can capture a subset.
Screens that need a record id (subject detail, CRF entry, retinal job) are
captured by clicking through from their list pages, so they depend on the demo
study having data.

## Conventions in these chapters

- **Screenshot refs** use stable ids matching the harness, e.g.
  `![Subject Matrix](screenshots/monitor/01-subject-matrix.png)`.
- **Role gating** notes which roles can reach each screen (from the SPA router).
- German UI labels are quoted as they appear (e.g. *Modalitäten*); MUW runs a
  German-first UI for clinical staff.
