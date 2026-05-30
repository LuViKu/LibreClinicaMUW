# Phase E — post-Phase-D UI/UX regression validation

**Date:** 2026-05-30
**Status:** Validation complete. **1 × P0 + 2 × P1 regressions surfaced. Phase E entry blocked on the P0 fix.**
**Owner:** Lead Developer (Lukas Kuchernig)
**Scope:** Verify that every UI feature documented in the [Phase E feature catalogue](README.md) (captured 2026-05-28 on the upstream public demo + cross-referenced to `web.xml`) is still reachable and structurally intact on `lc-develop @ 5d4932481` (Phase D-Sec closure tag `phase-d-sec-closure`).

This document is the formal entry gate to Phase E execution: until the P0 finding is resolved, the SPA rewrite cannot use the running app as its acceptance-testing baseline (the legacy login page no longer renders, so end-to-end browser sessions cannot reach any post-login screen).

---

## TL;DR

| Category | Count | Phase E impact |
|---|---|---|
| ✅ **Preserved** — URL inventory, controllers, static assets, integration tests | n/a | Phase E SPA can target the existing backend URLs verbatim |
| ⚠️ **Intentional retirements** — captured in Phase B/C/D commits | 3 | Reflect in Phase E feature catalogue; **not** regressions |
| 🔴 **P0 regression — blocks browser sessions** | 1 | **Must fix before Phase E.0** |
| 🟡 **P1 regression — degrades scaffolds** | 2 | Fix during Phase E.0; not user-blocking yet |

---

## Method

1. **Source diff** of `web.xml` and `web/src/main/webapp/WEB-INF/jsp/` between commit `1090880ec` (the Phase E catalogue capture) and `5d4932481` (Phase D-Sec closure). Identified all deleted JSPs and the bulk-mapping migration of servlet declarations into [`LegacyServletRegistry`](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/LegacyServletRegistry.java).
2. **Source-tree inventory** of Spring MVC `@Controller` classes in `web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/` (28 classes).
3. **Runtime probe** of a Docker Compose deployment of `lc-develop @ 5d4932481`: started via `docker compose up --build -d`; reached `Server startup in [32 564] milliseconds`; HTTP-probed a representative cross-section of legacy servlet URLs, `/pages/*` Spring MVC routes, and static-asset paths.
4. **CI verification** via `gh run list`: lc-develop @ 5d4932481 is green across all three CI jobs (compile / unit / integration).
5. **Cross-reference** of probe results against the feature catalogue's role-by-role URL inventory ([investigator-features.md](investigator-features.md), [monitor-features.md](monitor-features.md), [data-manager-features.md](data-manager-features.md)) and the [Phase D execution playbook](../phase-d-execution-playbook.md).

---

## ✅ Preserved (no regression)

### URL inventory — 216/216 servlet mappings

The Phase E catalogue counted **216 servlet-mapping declarations** in `web/src/main/webapp/WEB-INF/web.xml` at commit `1090880ec`. Phase C.14 ([web-xml-inventory.md](../phase-c14-web-xml-inventory.md)) migrated 214 of those into Java configuration; the current `web.xml` contains 2 declarations (the `pages` DispatcherServlet + a default-mapping fallback), and [`LegacyServletRegistry`](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/LegacyServletRegistry.java) registers the remaining **216 servlets via Spring Boot `ServletRegistrationBean`** (exact 1:1 match by URL pattern, verified by `grep -c ServletRegistrationBean`). The user-facing URL space is unchanged.

### Spring MVC controllers — 28 classes present

All 28 `@Controller` / `@RestController` classes survive intact under `web/src/main/java/.../controller/`: `AccountController`, `AnonymousFormControllerV2`, `BatchCRFMigrationController`, `ChangeCRFVersionController`, `DiscrepancyNoteController`, `EditFormController`, `ExtractController`, `IdtViewController`, `ODMClinicalDataController`, `OdmController`, `OdmStudySubjectController`, `ReportController`, `RuleController`, `SDVController`, `ScheduledJobController`, `SsoReauthController` (new), `StudyController`, `StudyEventController`, `StudyModuleController`, `SystemController`, `TwoFactorController`, `TwoFactorPrintoutController`, `UserAccountController`, `UserController`, `UserInfoController`, plus the `helper/`, `openrosa/`, and `user/` sub-packages.

### Integration tests pinning the auth chain

[`LoginFlowIT`](../../../core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/it/LoginFlowIT.java) and `OpenClinicaPasswordEncoderTest` together pin the auth DAO + password-encoder + audit-write contracts through the Spring 5 → 6 cliff. Phase D.0 added characterisation tests for the broader auth filter chain. All three layers green on `lc-develop @ 5d4932481`.

### Static-asset paths

Spot-probed `/includes/jmesa/`, `/includes/new_cal/`, `/includes/repetition-model/`, `/includes/wz_tooltip/` — all serve 302 (security-gated, but resolve through the auth filter). No 404s on legacy static-asset paths.

---

## ⚠️ Intentional retirements (not regressions)

These are documented changes that should be **reflected in the Phase E feature catalogue** rather than treated as bugs.

### 1. `listSubjectDiscNote.jsp` — per-subject Notes & Discrepancies list (deleted)

**Commit:** `3d53ce430` "chore(web): delete dead per-subject Notes-and-Discrepancies path (B.4 PR 5b) (#43)" (2026-05-29).
**Phase E catalogue impact:** [monitor-features.md](monitor-features.md) and [investigator-features.md](investigator-features.md) reference a per-subject discrepancy view at `/ListSubjectDiscNote?id=<subject-id>`. That route is now removed; the cross-subject `/ViewNotes` workflow + the per-CRF inline discrepancy thread (already mocked up in [notes-discrepancies.html](ux-mockups/notes-discrepancies.html)) supersede it.
**Action:** Update the catalogue entries to remove the per-subject route; Phase E does not need to reproduce it.

### 2. DataTables 2.x vendor bundle — removed mid-Phase-B.4 ([includes/js/datatables/README.md](../../../web/src/main/webapp/includes/js/datatables/README.md))

The original B.4 plan vendored a DataTables.net 2.x JS/CSS bundle at `web/src/main/webapp/includes/js/datatables/`. It conflicted with the legacy `prototype.js` library (Prototype monkey-patches `Element.prototype` in a way DataTables 2.x cannot tolerate). The bundle was removed; cohort 2a (`AuditUserActivity`) ships with a **vanilla-JS `fetch` + DOM render pattern** instead, documented in the directory's README.
**Phase E catalogue impact:** the `DataTableRequest` / `DataTableResponse` Java side of the protocol stays — the SPA can post the standard DataTables AJAX query params and parse the same response shape. No JSP changes needed for Phase E to consume the same endpoint.
**404 on `/includes/js/datatables/*`:** expected; not a regression.

### 3. jmesa-dead-endpoint eviction — cohorts 3b + 3c

Per [memory: project_jmesa_dead_endpoints](../../../) and the Phase B.4 PRs, several jmesa-backed servlets were already unmapped in `web.xml` since 2014 (the JSP files existed but no URL pattern ever pointed at them). Phase B.4 removed the orphan factories without removing any reachable route.
**Phase E catalogue impact:** none — the catalogue was captured via a live walkthrough of the public demo and so never recorded these dead pages.

---

## 🔴 P0 regression — Phase E entry blocker

### `/pages/login/login` returns HTTP 404 — login JSP entry path unreachable in a browser

**Symptom**

- `curl -I http://127.0.0.1:8080/LibreClinica/` → `302 Location: /LibreClinica/pages/login/login`.
- Following the redirect (`curl -L` or any browser session) → **`HTTP 404`**, empty body.
- The same 404 hits `/pages/sso/reauth` (Phase D.10 scaffold) and `/pages/odmk/odm/v1/Studies` (ODM REST).
- By contrast, `/pages/denied`, `/pages/studymodule`, `/pages/listStudies`, `/pages/idtView`, `/pages/userInfo`, `/pages/listSubjectsRest`, and **every** legacy servlet URL (`/MainMenu`, `/ListStudySubjects`, etc.) all return `302` (Spring Security redirect to login) — i.e. the `pages` DispatcherServlet is wired into `web.xml`, but it is not resolving `@Controller`-mapped routes nor `BeanNameUrlHandlerMapping`-mapped routes whose security configuration is `permitAll`.

**Why this matters**

- Any browser session that lands on the LibreClinica context root is bounced to a 404 page. **The app is not usable end-to-end through a browser**, even with correct credentials.
- The auth-flow integration tests (`LoginFlowIT`, `OpenClinicaPasswordEncoderTest`, Phase D.0 characterisations) pass because they go through `MockMvc` / `UserAccountDAO` directly and never traverse the `pages` DispatcherServlet's child context.
- The institutional memory note "pages dispatcher @Bean handler registration broken — auth flow unaffected" is **partially wrong**: the DAO + encoder + audit paths are unaffected; the user-facing browser path is broken at the very first hop. The Phase D-Sec closure (10/11 sub-phases shipped) ratified this as acceptable on the assumption that the unshipped reconciliation step (D.11) would close the gap. It did not.

**Root cause (confirmed by reading [WebMvcConfig.java](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/webmvc/WebMvcConfig.java) + [pages-servlet.xml](../../../web/src/main/webapp/WEB-INF/pages-servlet.xml))**

- `web.xml` declares the `pages` DispatcherServlet (`org.springframework.web.servlet.DispatcherServlet`) mapped to `/pages/*`, with no explicit `contextConfigLocation` init-param.
- The dispatcher therefore looks for its child-context config at the default path `/WEB-INF/pages-servlet.xml`, which is now a one-line stub:
  ```xml
  <context:annotation-config/>
  <bean class="…libreclinica.webmvc.WebMvcConfig"/>
  ```
- [`WebMvcConfig`](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/webmvc/WebMvcConfig.java) is a `@Configuration` class with `@ComponentScan("…libreclinica.controller")` that registers:
  - URL-mapped `UrlFilenameViewController` beans for `/login/login` and `/denied` (via `BeanNameUrlHandlerMapping`).
  - `RequestMappingHandlerMapping` + `RequestMappingHandlerAdapter` for `@Controller`-annotated classes.
- Observed behaviour: `BeanNameUrlHandlerMapping` resolves `/denied` (which returns 302 because Spring Security intercepts before the dispatcher fires) but **fails to resolve `/login/login`**, and `RequestMappingHandlerMapping` fails to resolve `/sso/reauth` (`SsoReauthController#reauth`) and `/odmk/odm/v1/Studies` (`OdmController`).
- Hypothesis (to be confirmed during the fix): Spring Boot's root context is also component-scanning `…libreclinica.controller` (via `LibreClinicaApplication`'s `@SpringBootApplication` at the `…libreclinica` package), which causes the `@Controller` beans to register in the root context. When the `pages` DispatcherServlet starts its child context, the `@ComponentScan` in `WebMvcConfig` sees no new beans to register (or registers duplicates that the root context's handler mapping consumes). Routes that Spring Security gates with `permitAll` (login, sso-reauth, healthcheck) reach the `pages` dispatcher with no handler available → 404; routes that Spring Security gates with `authenticated` are 302-redirected before the dispatcher gets to fail.

**Recommended fix (sub-phase E.0)**

Two options, ratification needed before execution:

1. **Exclude the controller package from the root context's component-scan.** Add `excludeFilters` to `LibreClinicaApplication`'s `@SpringBootApplication` (or use `scanBasePackages` narrowed to non-controller packages) so the controllers are only registered in the `pages` DispatcherServlet's child context. Surgical; preserves the legacy contract that the `pages` dispatcher owns `/pages/*`.
2. **Promote everything to the root context, retire the `pages` DispatcherServlet.** Remove the `web.xml` servlet declaration; let Boot's default DispatcherServlet at `/` handle `/pages/*` via the same `@Controller` mappings. Larger blast radius (every existing `<form action="pages/...">` in 413 JSPs needs no change because Boot will still match those URLs at the same path; but `pages-servlet.xml` would need deleting and `WebMvcConfig` lifting up to a Boot `WebMvcConfigurer`).

Recommendation: **Option 1** for Phase E.0 — minimal touch, single config-file change, fastest path to a green browser session.

---

## 🟡 P1 regressions — Phase E.0 cleanup items

### A. `/pages/sso/reauth` returns 404

**Affected feature:** Phase D.10 e-signature re-auth scaffold ([SsoReauthController.java](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/SsoReauthController.java)).
**Same root cause as the P0** — fix lands together.
**User impact today:** none (the feature is `libreclinica.sso.reauth.enabled=false` default; e-signature flow falls back to local password challenge per DR-014 §4 and the Sign Subject mockup pattern).
**Phase E impact:** the SPA's e-signature design (already mocked in [investigator-sign-subject.html](ux-mockups/investigator-sign-subject.html)) needs this endpoint reachable before §11.50 ratification with legal/regulatory.

### B. `/pages/odmk/odm/v1/Studies` returns 404

**Affected feature:** ODM REST API for studies ([OdmController.java](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/OdmController.java)).
**Same root cause as the P0** — fix lands together.
**User impact today:** unknown extent — programmatic ODM consumers (export pipelines, partner-site integrations, biostatistics) cannot reach this endpoint. Internal tests pass because they invoke the controller through `MockMvc`.
**Phase E impact:** the SPA may want to consume some of these ODM endpoints; they need to be reachable.

---

## Other observations

- **CI is green** on `lc-develop @ 5d4932481` despite the P0 regression. The CI smoke-test step (`.github/workflows/build.yml`) hits `http://127.0.0.1:8080/LibreClinica/` without following redirects, so it sees only the initial 302. **Phase E.0 should tighten the smoke step** to require `curl -fL http://.../pages/login/login` to succeed — the same recommendation as `known-issues.md §1`'s smoke-test follow-up, now elevated to mandatory.
- **Logback collisions** in the libreclinica container logs at startup (~14 `FileNamePattern option has the same value … as that given for appender X defined earlier`). Cosmetic only; appenders fall back to a single shared file. Phase D-Libs candidate for cleanup; not a regression vs. pre-Phase-D behaviour.
- **Login default credentials.** A `POST /j_spring_security_check` with `root/password` produced `?action=errorLogin`. This is expected behaviour after Phase D.1's `DelegatingPasswordEncoder` migration; the seeded admin user's password hash may have rotated. Not a regression — the password seeding is owned by the institutional validation plan, not by the codebase. For Phase E browser-based regression testing, the operator needs to run the password-reset endpoint or seed a fresh user.

---

## Phase E entry checklist

Phase E.0 (known-issues triage) must close all of the following before Phase E.1 begins:

- [ ] **P0** — fix `/pages/login/login` 404 + `/pages/sso/reauth` 404 + `/pages/odmk/odm/v1/Studies` 404. One change set; root cause is shared.
- [ ] **Phase E feature catalogue refresh** — mark the 3 intentional retirements (`listSubjectDiscNote.jsp`, DataTables vendor bundle, jmesa dead endpoints) in the relevant feature pages so the SPA-feature-parity matrix doesn't re-implement them.
- [ ] **CI smoke-test step tightening** — `curl -fL /pages/login/login` mandatory, not just `/`.
- [ ] **Operator-facing instructions** for seeding a password-reset / fresh admin user in a local Phase-D-complete environment, so the smoke-test harness can drive end-to-end sessions during Phase E acceptance.

Once these close, the Phase E execution playbook ([phase-e-execution-playbook.md](../phase-e-execution-playbook.md)) ratifies entry and the SPA work proceeds per its sub-phase ordering.

---

## Reference

- [Phase E feature catalogue (README)](README.md)
- [Phase E known issues (companion to this report)](known-issues.md)
- [Phase D execution playbook](../phase-d-execution-playbook.md)
- [Phase C.14 web.xml inventory](../phase-c14-web-xml-inventory.md)
- [DR-014 — Institution-agnostic SSO via reverse-proxy pre-authentication](../decision-record.md)
- Phase D-Sec closure tag: `phase-d-sec-closure` @ `lc-develop @ 5d4932481`
- CI run for closure: GitHub Actions run `26684367135` (all 3 jobs ✅).
