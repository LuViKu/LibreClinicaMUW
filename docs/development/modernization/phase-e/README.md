# Phase E — UI feature catalogue

Inventory of every UI feature reachable in LibreClinica today, organized per role, so the Phase E SPA rewrite has a complete checklist of features to preserve.

## Intentional retirements (do NOT reproduce in the SPA)

Three features captured by the live walkthrough are deliberately **not** reproduced in the Phase E SPA. They surfaced during the [post-Phase-D UI validation](post-phase-d-ui-validation.md) and are kept here as a flag so the SPA-feature-parity matrix doesn't accidentally re-implement dead paths.

| Retired feature | Replaced by | Source |
|---|---|---|
| **`/ListSubjectDiscNote`** — per-subject Notes & Discrepancies path (`listSubjectDiscNote.jsp`) | The cross-subject `/ViewNotes` workflow + the per-CRF inline discrepancy thread already mocked in [`notes-discrepancies.html`](ux-mockups/notes-discrepancies.html) | Phase B.4 PR #43 (commit `3d53ce430`) — "delete dead per-subject Notes-and-Discrepancies path" |
| **DataTables.net 2.x vendor bundle** at `includes/js/datatables/` | Vanilla `fetch` + DOM render per cohort 2a — see [`includes/js/datatables/README.md`](../../../web/src/main/webapp/includes/js/datatables/README.md) | Phase B.4 cohort 2a (incompatible with the legacy `prototype.js` `Element.prototype` mutations) |
| **jmesa dead endpoints** (cohorts 3b + 3c) | Already unmapped in `web.xml` since 2014; factories deleted without reaching any reachable URL | Phase B.4 cohort 3 work |

## Role catalogues

- **[investigator-features.md](investigator-features.md)** — physician / study-assistant: data entry, subject management, event scheduling, signing
- **[monitor-features.md](monitor-features.md)** — SDV, discrepancy management (Query authority, Close authority), Study Audit Log
- **[data-manager-features.md](data-manager-features.md)** — full study setup: Build Study, CRF design, Event Definitions, Rules, Users, Groups, plus all the above

## Why this exists

Per [MIGRATION.md § Phase E](../../../../MIGRATION.md), the planned UI modernization replaces the JSP + 295 legacy servlets + SiteMesh + GWT stack with an SPA. A frequent risk in eCRF re-platforms is **silently losing features** — a flag-icon workflow, a discrepancy status, a SDV bulk action — that a busy investigator or monitor was relying on. This catalogue prevents that by enumerating what exists today, with screenshots and code traces.

## How the catalogue was built

1. **Live walkthrough** of the public demo at `libreclinica.reliatec.de/lc-demo01` as each of the three roles (`user_demo`, `monitor_demo`, `dm_demo` — all password `LibreClinica`)
2. **Headless Playwright** drove the demo, captured a full-page screenshot of every top-nav link and every Tasks-menu item per role on the first pass (61 screens), then a second pass drilled one level deeper into the actual workflow pages — subject detail, single-CRF read-only render, Update Event Definition, Create CRF, Manage Sites, Test Rule, etc. (**99 screens total**) — plus structured metadata: page title, H1, links, forms (with input names), buttons, table headers
3. **Source code cross-reference**: every observed URL was mapped to its backing servlet class in [`web/src/main/webapp/WEB-INF/web.xml`](../../../../web/src/main/webapp/WEB-INF/web.xml) (213 servlet mappings total). Spring MVC routes (`/pages/*`) were traced to [`pages-servlet.xml`](../../../../web/src/main/webapp/WEB-INF/pages-servlet.xml)
4. **Existing manuals** at [docs/manuals/](../../../manuals/) (investigator, monitor, administrator) supplied workflow narrative — particularly important for the Monitor SDV details and Investigator data-entry flow

The demo runs the same upstream code that's vendored into this repo per [DR-003](../decision-record.md#dr-003--hard-fork-from-upstream-reliateclibreclinica), so URLs and servlet classes are authoritative for our codebase too.

## What's captured vs. what's not

**Captured (two passes):**

1. **Surface walk (61 screens)** — every top-nav link and every Tasks-dropdown entry per role
2. **Deep walk (38 screens)** — one click deeper into the actual workflow pages: subject detail, the per-event CRF list, the read-only CRF render that Monitor opens during SDV, Update Event Definition, Create CRF, Manage Sites, View Site Details, Test Rule, Create Subject Group Class, per-subject Audit Log drill-in

**Not yet captured (recommended third pass if Phase E scoping requires it):**

- The Add Query / Add Discrepancy Note modal contents (rendered in a child window via JS popup)
- The "View Within Record" two-window flow (SDV table → CRF + discrepancy side-by-side)
- Multi-step wizards: Create Dataset (event/CRF selection → filter → format → preview), Import CRF Data (upload → preview → confirm)
- Forced password change on first login (demo users already past it)
- 2FA flows (LETTER and APPLICATION)
- Locale switching (en only observed; LibreClinica supports de/fr/es/pt/zh)
- A handful of legacy edit URLs (`/UpdateStudy`, `/EditStudy`, `/CreateEventDefinition`, `/EditStudyUserRole?userName=…`) that redirected to home or 404'd — they need different query params or are only reachable via in-page Edit icons rather than direct URL (documented in [data-manager-features.md §16.10](data-manager-features.md))

Each role catalogue ends with an "Open follow-ups" section listing its specific gaps.

## Artifacts

- [screenshots/investigator/](screenshots/investigator/) — 32 full-page screenshots + JSON metadata (23 surface + 9 deep)
- [screenshots/monitor/](screenshots/monitor/) — 26 screenshots + JSON (19 surface + 7 deep)
- [screenshots/data-manager/](screenshots/data-manager/) — 48 screenshots + JSON (25 surface + 23 deep)
- [_explore/](_explore/) — Playwright crawler scripts (`pilot.mjs`, `crawl.mjs`, `deep-crawl.mjs`), raw `walk-out/` and `walk-out-deep/` output (git-ignored), and `servlet-map.json` (213 URL→Java class mappings)

## Re-running the crawl

The crawler is intentionally read-only — never submits a form, never clicks Save/Delete/Sign/Lock. To re-run (e.g. after a demo upgrade or to capture deeper sub-pages):

```sh
cd docs/development/modernization/phase-e/_explore
# First run only — installs Playwright + Chromium into the _explore/ folder:
# npm install playwright && npx playwright install chromium
node crawl.mjs
# screenshots and metadata land in walk-out/<role>/
```

The `_explore/` directory carries its own `node_modules/` and `package.json` so the install doesn't pollute the repo root. It is git-ignored via [_explore/.gitignore](_explore/.gitignore).

## How to use this catalogue in Phase E

1. **Scoping & estimation** — count features per role to size the SPA effort
2. **Acceptance criteria** — for each SPA component, the corresponding catalogue entry is the "definition of done" for feature parity
3. **Permission model** — the per-role feature lists are the source of truth for what each role needs to see in the SPA's authorization layer (replacing the current `security-config.xml` URL-pattern-based gating)
4. **Backend API extraction** — the servlets cross-referenced here are the endpoints that need to become JSON APIs in Phase D, before the SPA can replace the JSP UI in Phase E

See [MIGRATION.md](../../../../MIGRATION.md) for how Phase E fits into the broader modernization, and [decision-record.md](../decision-record.md) for the rationale behind a hybrid SPA approach (data entry + dashboards) versus a full rewrite.
