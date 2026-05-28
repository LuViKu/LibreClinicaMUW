# Phase E — known issues to investigate

Runtime bugs caught by the modernization safety net that touch the UI surface and should be characterised + fixed before (or as part of) the SPA rewrite. Companion to [README.md](README.md) (the per-role feature catalogue) — these are the bugs in the existing features.

Each entry should resolve to either:
- a fix that lands before Phase E starts (so the SPA inherits a clean baseline), or
- a documented "intentionally retired" decision (the SPA doesn't reproduce the broken feature).

---

## 1. `/pages/login/login` returns HTTP 500

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

## How to add entries here

Append new `## N. <one-line summary>` sections following the same shape: surfaced-date / symptom / what we know / investigation steps / Phase E framing. Keep the entries small enough that a developer can pick one up in a sitting.
