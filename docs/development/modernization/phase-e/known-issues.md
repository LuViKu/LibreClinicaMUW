# Phase E — known issues to investigate

Runtime bugs caught by the modernization safety net that touch the UI surface and should be characterised + fixed before (or as part of) the SPA rewrite. Companion to [README.md](README.md) (the per-role feature catalogue) — these are the bugs in the existing features.

Each entry should resolve to either:
- a fix that lands before Phase E starts (so the SPA inherits a clean baseline), or
- a documented "intentionally retired" decision (the SPA doesn't reproduce the broken feature).

---

## 1. `/pages/login/login` returns HTTP 500 → HTTP 404 → **RESOLVED 2026-05-30 evening**

**Status as of 2026-05-30 evening:** **RESOLVED.** The Phase 0-D backend agent landed [`fix(phase-e.0): pages dispatcher routing — /pages/login/login + /pages/sso/reauth + JSP context-bean exposure restored`](https://github.com/LuViKu/LibreClinicaMUW/commit/dccfc86e2) on `lc-develop @ dccfc86e2` (tag `phase-e0-pages-dispatcher-fix`, CI green). The fix re-binds the `pages` DispatcherServlet to its `WebMvcConfig` child context, adds an `SsoConfigInterceptor`, and exposes the JSP context beans (`ssoProperties` etc.) so the institutional-SSO button renders correctly. All three URLs from the post-Phase-D validation (`/pages/login/login`, `/pages/sso/reauth`, `/pages/odmk/odm/v1/Studies`) resolve in a browser session. **The narrative below is retained for historical context.** The arc from 500 → diagnosis-corrected 404 → fix is documented in the [post-Phase-D UI validation report](post-phase-d-ui-validation.md); see also the Phase E execution playbook §E.0 update.

**Surfaced:** 2026-05-28, by the compose smoke test fix verification ([commit `b75a2c287`](../../..)).
**Symptom:** `docker compose up --build` deploys the WAR successfully; `curl -I http://127.0.0.1:8080/LibreClinica/` returns `HTTP 302 Location: /LibreClinica/pages/login/login`; following the redirect (`curl -L`) yields **HTTP 500** with a 6 165-byte error body.
**Smoke-test impact:** none. The CI smoke job uses `curl --silent --fail http://127.0.0.1:8080/LibreClinica/` without `-L`, so it sees only the 302 and exits 0. The login page failure is invisible to that gate.

### What we know

- Tomcat starts cleanly; Spring application context boots; Liquibase migrations apply (all `lc-1.4.0` changesets ran).
- The `OCContextLoaderListener` initialises without throwing (the Quartz "DataSource name not set" + `qrtz_locks does not exist` errors from before `b75a2c287` are gone).
- The 500 is therefore happening *during request handling* on the login JSP path, not during context startup. Suspects:
  - `login-include/login-header.jsp` references `${pageContext.request.contextPath}` and pulls `org.akaza.openclinica.i18n.*` resource bundles. A missing bundle key would 500.
  - The page does a `<jsp:useBean scope='session' id='userBean' class='org.akaza.openclinica.bean.login.UserAccountBean'/>` — bean construction failure would 500.
  - The `decorator.jsp` SiteMesh decorator wraps every page and references `${resword}` (loaded from `org.akaza.openclinica.i18n.words`). Bundle loading on a fresh start with no logged-in user can fail.

### Investigation steps (when picked up)

1. `docker compose up --build --detach && docker logs -f libreclinica-muw-libreclinica-1 | grep -A 50 "GET /pages/login/login"` while running `curl -L http://127.0.0.1:8080/LibreClinica/` — capture the actual stack trace.
2. Confirm against upstream LibreClinica's demo at `libreclinica.reliatec.de/lc-demo01` whether this 500 reproduces there too. If yes, it's an upstream bug, not introduced by Phase 0/A modernization.
3. If introduced by modernization, bisect against the Phase A.2 commits (Spring 5.1 → 5.3, Spring Security 5.1 → 5.8, Hibernate 5.4 → 5.6, Quartz). The Spring Security 5.8 + Hibernate 5.6 are the most likely suspects (CSRF default + HQL strictness).

### Phase E framing

Even if the 500 is fixed upstream, **the SPA rewrite must preserve the *features* of the login flow** as documented in the role catalogues (`investigator-features.md`, `monitor-features.md`, `data-manager-features.md`). The bug is therefore both a Phase E feature to investigate (catch any feature-parity gap caused by the 500) and a Phase 0 follow-up (the smoke test should evolve to catch 500s on key pages, not just the root context).

### Smoke-test follow-up

Suggested CI improvement (not blocked by this investigation): tighten the compose smoke step to also `curl -fL http://127.0.0.1:8080/LibreClinica/pages/login/login` and require both to succeed. That would have caught this 500 the moment it landed. The work item belongs in Phase 0 nice-to-haves, not in Phase E itself.

---

## 2. Long-running studies — Subject Matrix + per-subject event view at 20+ visits

**Surfaced:** 2026-05-31, by the GA-cohort feasibility probe (H2 of the hardening plan; see [plan: polished-jumping-swan](https://example/n/a)).
**Context:** MUW Ophthalmology's geographic-atrophy cohort: each patient has 10–20 visits over multiple years (6-month follow-up). The probe seeded one StudySubject with 20 events at ordinals 1–20, dates spanning 2019–2028, then exercised the two listing surfaces a clinician hits day-to-day. **No regression found.**

### What we measured

| Surface | URL | Status | TTFB | Total | Notes |
|---|---|---|---|---|---|
| Subject Matrix | `/ListStudySubjects` (page shell) | 200 | 320 ms | 486 ms | The page is a shell — events load via XHR to `/FindSubjectsData`. |
| Subject Matrix data | `/FindSubjectsData?draw=1&start=0&length=500` | 200 | 105 ms | 106 ms | **JSON aggregates events per (subject, SED) into `{statusName, count}`** — the table renders one cell per (subject, SED), not 20. Scalable to N events/subject by design. |
| Per-subject drill | `/ViewStudySubject?id=1` (page 1) | 200 | 290 ms | 472 ms | Shows 10 most recent events (ordinals 11–20, descending). Paginates via `ebl_page=N` URL params. |
| Per-subject drill | `/ViewStudySubject?...&ebl_page=2&...` | 200 | 110 ms | 151 ms | Page 2 → ordinals 10–1. Pagination handles long visit lists cleanly. |

### What we know

- The Subject Matrix render path is from the Phase B.4 jmesa eviction (PR #41); event data comes from a JSON endpoint that aggregates per (subject, study-event-definition) into status + count. Cell render cost is O(N_SEDs) per subject, not O(N_visits). 100+ visits per subject would still render as one cell per SED.
- `ViewStudySubject` is the legacy OpenClinica per-subject view. It paginates the event list at 10/page via `ebl_page`/`ebl_paginated` URL params (the "event browser list" prefix). Default page is the most-recent 10. Older visits live on subsequent pages.
- Per-subject HTML for one page is ~60 KB; this should not degrade further with more visits because pagination caps the per-page event count.

### Phase E framing

The SPA rewrite will replace `ViewStudySubject` with the modernised per-subject drill-down. The current pagination cap of 10 events/page is small for a 20-visit cohort — clinicians have to click "next" once to see all visits. **Phase E enhancement (P1):** the SPA per-subject view should default to showing all visits in a single scrollable timeline (or paginate at a higher cap like 25/page). The visit-timeline mockup is already an opportunity flagged in [post-phase-d-ui-validation.md](post-phase-d-ui-validation.md) §"Other observations".

### Smoke-test follow-up

The H1 integration test (`StudyEventScheduleIT.testRepeatingEventScalesTo15Visits`) pins the data-model side. A Phase E browser-level smoke could exercise the full path: seed → log in → navigate Subject Matrix → drill into subject → assert all 20 visits reachable across pages. Not blocking; tracked as Phase E nice-to-have.

---

## How to add entries here

Append new `## N. <one-line summary>` sections following the same shape: surfaced-date / symptom / what we know / investigation steps / Phase E framing. Keep the entries small enough that a developer can pick one up in a sitting.
