# Plan — retiring the legacy JSP layer and clearing code scanning (2026-09-30)

**Status:** [DR-018](decision-record.md#dr-018--the-legacy-jsp-layer-is-retired-in-full-admin-screens-included) is accepted. Implementation is recorded in [§14](#14-implementation-status-2026-10-02).

**Goal:** the SPA is the only user interface. No JSP, no legacy servlet, no SiteMesh decorator, no jQuery 1.9 / Prototype / Scriptaculous / GWT and no Jersey remain in the WAR. Security-level code-scanning alerts on `main` are zero at every release, and every remaining quality alert is either fixed or dismissed with a reason.

**Inputs:**

- [phase-e/administrator-features.md](phase-e/administrator-features.md): the admin catalogue with an SPA-coverage column.
- The 2026-09-30 static JSP survey summarised in DR-018.
- A read-only survey of the modernization plan against the code, 2026-09-30. Its main claims were spot-checked (XML contexts, DBCP, iText, Trivy mode, `modifyColumn` count, commons-lang count, telemetry filter).
- Code-scanning snapshots of `main` and `lc-develop` taken on 2026-09-30.

---

## 1. Summary

The work runs in five stages, with code scanning in parallel as track S.

| Stage | What | Gate to leave it |
|---|---|---|
| **R0 Unblock** | Merge the pending branches; fix the retirement tracking; fix what is broken or unsafe in the legacy screens that are still live; remove the dead participant-form chain | Tracking records hits; the admin-only `/legacy/` alias exists; no security alert is open |
| **R1 Close SPA gaps** | Build what the catalogues mark *needed*; decide and record everything else as *not needed* | Every legacy screen has a recorded SPA replacement or a recorded "not needed" |
| **R2 Close in waves** | Move each area's legacy URLs behind the admin-only, logged `/legacy/` alias | Each wave closed and logged in the retirement log |
| **R3 Delete** | Six months after a wave closes, and only if the log shows no legitimate use, delete its servlets, JSPs and supporting code | The last wave is deleted; the legacy infrastructure is gone |
| **R4 Platform clean-up** | The library and configuration work that deleting the legacy code makes cheap | Separate plan items (§9) |

**Critical path:** fix the telemetry → close the first wave → six-month bake-in → delete. Bake-in cannot start until the telemetry records hits, because an empty log is only evidence of non-use once the filter works. So the tracking fix (R0.3) is the most time-critical item in this plan. **The last JSP can be deleted at the earliest six months after the last wave closes.**

---

## 2. Where things stand

| Metric | Value | How counted |
|---|---|---|
| JSPs | 421 under `WEB-INF/jsp` on `lc-develop` (+2 outside); **385** after DR-018 step 1 (`chore/muw-retire-dead-jsps`, `ea4693117`) | `git ls-tree`; step 1 is verified by build, 1,323 unit tests and a before/after crawl of 176 URLs × 2 users |
| JSPs by area (after step 1) | managestudy 119 · submit 68 · admin 63 · include 52 · extract 46 · root 16 · login 15 · login-include 5 · techadmin 1 | first path segment |
| Legacy servlets | **214** registrations, 213 classes, in `LegacyServletRegistry.java`: managestudy 85–86 · admin 56 · submit 37 · extract 21 · login 9 · other 5 | regex over `addServlet` / `addMapping` |
| Heritage Spring MVC controllers | 29 `@Controller` files; several render JSPs (SDV, study module, scheduled jobs, LDAP users) | `grep -rl` |
| SPA | ~52 routes, all on real APIs | `router/index.ts` |
| SPA → legacy couplings | `GET /LibreClinica/Logout` (`stores/auth.ts:367`), `/LibreClinica/CreateDataset` (`DatasetListView.vue:244`), and **login success redirecting to `/MainMenu`** (`applicationContext-security.xml:74`) | grep |
| Coverage | **Admin:** 66 features, 21 covered · 17 partial · 28 not covered. **Whole JSP layer (DR-018 survey):** 97 screens, 31 · 18 · 48. The in-code deprecation catalogue maps 125 servlets to an SPA route and 89 to none; the 125 is an upper bound (§R0.3) | catalogues |
| Retirement tracking | **Inert.** The telemetry log, the banner and the 410 switch have never recorded or blocked a request (§R0.3) | 0 `legacy-hit` lines after about 350 legacy requests |

**Code scanning** (open alerts):

| | `main` | `lc-develop` |
|---|---:|---:|
| Total | 2,125 | 1,169 |
| Security-level | 8: 4 CodeQL already fixed on `lc-develop`; 4 new Trivy | 9 new CodeQL (1 high, 8 medium), all log lines (§10) |
| Quality | 2,117 (1,701 note, 416 warning) | 1,160 |

`lc-develop` is 30 commits ahead of `main`. Merging it closes about 950 alerts.

---

## 3. Decisions

### 3.1 Taken (Lukas, 2026-09-30)

| Feature | Decision |
|---|---|
| Data Entry Person roles `ra` / `ra2` | Move holders to CRC or Investigator, then retire the roles. **Target role still to be confirmed**, see 3.2 |
| Two-factor authentication, including the LETTER certificate printout | Drop |
| Scheduled ODM imports (`/CreateJobImport`, `/UpdateJobImport`, `/ViewImportJob`, `/ViewLogMessage`) | Drop |
| Study login e-mail notification | Drop |

### 3.2 Open

1. **Accept DR-018.** It only changes Status. Nothing in R2 or R3 starts before this.
2. **The XForm / OpenRosa participant-form chain.** The recommendation is to **drop it** (R0.6). The chain was built for OpenClinica's participant portal and an Enketo form server; neither is deployed. It cannot work end to end on any build since the jakarta migration:
   - its form-delivery endpoints are Jersey resources, and Jersey no longer loads;
   - its portal calls need HttpClient 5, which the WAR does not ship.

   The MUW-built public pages (OCT, BCVA, image and combined upload) do not use it.
3. **Target role for `ra` / `ra2`.** Both options widen what these users can do. The backend still treats them as Data Entry Persons: they can enter and import data but cannot sign (`SubjectsApiController` sign preflight) or reopen CRFs (`CrfReopenAuthorization`).
   - **Investigator** adds e-signature and reopen.
   - **CRC** (`coordinator`) adds reopen. It also passes every Investigator route (`router/index.ts:689`) and is the role a study's creator receives. It does not sign.

   **Recommendation:** CRC for holders whose job is coordination. If any holder is pure data-entry staff, add a *Data Entry* value to the SPA's role list instead: one enum value, and nobody's rights change. Decide after the production check in §13.
4. **Production facts** that settle the remaining unknowns: the queries in §13.

---

## 4. Rules every step follows

- **DR-018 order:**
  1. dead code first;
  2. then close a covered screen behind `/legacy/<path>`, which is admin-only and logged;
  3. keep the six-month bake-in;
  4. delete only after parity is recorded and the log shows no legitimate use.

  Every step is recorded in `phase-e-retirement-log.md`.
- **Clinical-data gate on every PR:**
  - `mvn package` with unit tests, run in a Linux container;
  - the Testcontainers web DB ITs, also on Linux;
  - SPA vitest and a build.
  - **Retirement and deletion PRs** also get a before/after crawl diff of every GET-safe legacy URL as two roles, plus a server-log check for JSP errors. JSP includes resolve at runtime, so a green build is not enough.
  - **Behaviour fixes** need a red-then-green test.
  - CodeQL on the PR must show no new alert.
- **Schema:** never edit a changeset. Unused columns stay. Role migrations go in a new `migration/lc-muw-<date>-<topic>.xml`.
- **One area per PR.** A retirement PR never also adds a feature.
- **Public repository:** commit and PR text describes fixes factually and never as a recipe.

---

## 5. Stage R0 — unblock

| # | Item | Size | Notes |
|---|---|---|---|
| R0.1 | Push and PR the three session branches, then accept DR-018 | S | `fix/muw-spa-tristate-reason-routing` (tri-state reason pairing in entry and preview); `chore/muw-retire-dead-jsps` (36 dead JSPs, 6 `Page` constants); `docs/muw-dr-018-jsp-retirement` (DR-018, the admin catalogue, this plan) |
| R0.2 | Clear the security alerts before the next `lc-develop` → `main` merge | S | §10.2 S1–S2 |
| R0.3 | **Make retirement tracking work** | M | See below |
| R0.4 | **Build the `/legacy/<path>` alias** | M | See below |
| R0.5 | Make the remaining GET-mutating admin actions POST-only | S | Remove/restore user, unlock user, remove/restore study role, pause/resume/delete job and send test e-mail change state on GET. Require POST, as was done for the SDV, export and study-module actions. They are deleted in their wave anyway, but stay reachable until then |
| R0.6 | **Remove the participant-form chain** (if §3.2.2 = drop) | M | See below |
| R0.7 | Deal with the broken live pages | S | See below |
| R0.8 | Dead-code sweep 2: never reachable, so no bake-in | S | See below |
| R0.9 | Verify the authorization scoping of one legacy servlet | S | The plan survey flagged `CreateNewStudyEventServlet` for review; unverified. If a fix is needed, fix it or close the servlet early (the SPA schedules events) |
| R0.10 | Review the unauthenticated path list in `SecurityConfig` | S | Narrow the list to the paths that genuinely need anonymous access. R0.6 removes nine groups; R0.8 removes the dead Jersey and SOAP entries |
| R0.11 | One documentation-drift PR, so the next survey isn't misled | S | See below |

**R0.3 — make retirement tracking work.**

- **The filter sees no legacy servlet.** `LegacyServletTelemetryFilter` is registered on `/pages/*`, but the legacy servlets are mapped at the root (`/ViewStudy`, `/ListSubject`, …). Register it on `/*`, or on the registry's paths.
- **Its lookups never match.** `lookup()` compares `getRequestURI()`, which includes `/LibreClinica`, against context-free keys. Match on `getServletPath()` plus `getPathInfo()` instead, and add a test that runs with a non-empty context path.
- **The 410 kill switch is all-or-nothing and dead for the same reason.** Make it per path, since the alias in R0.4 builds on it.
- **The banner can never render.** It only renders in the SiteMesh `decorator.jsp`. Delete it together with the `sunsetDate` default, which has passed.
- **The catalogue's wrong mappings need correcting** (admin catalogue §16.3): `/Configure`, `/RestoreCRF`, `/InitUpdateCRF`, `/RemoveStudy`, `/RestoreStudy`, `/PauseJob`, `/AuditUserActivity`, `/AuditDatabase` and `/ViewLogMessage`.

**R0.4 — build the `/legacy/<path>` alias.**

- DR-018 point 2: a closed screen's original URL answers 410 with a link to its SPA route.
- `/legacy/<path>` forwards to the servlet only for a system administrator, and logs every hit with user, path and time.
- Build it into the fixed filter from R0.3, driven by a per-path "closed" list in configuration, so that closing a screen is a configuration change and not a code change.

**R0.6 — remove the participant-form chain.** Delete:

- `CreateXformCRFVersionServlet`;
- `OpenRosaServices` (Jersey, unreachable) and `OpenRosaSubmissionController`;
- the `web/pform` package;
- `AnonymousFormControllerV2`, `EditFormController`, `OdmController` (`/odmk`), `OdmStudySubjectController` (`/odmss`), `AccountController` (`/accounts`) and `UserInfoController`;
- `ParticipantPortalRegistrar`, and its 17 call sites.

Also remove their public entries from `SecurityConfig`, and hide the `participantPortal` study parameter in the SPA.

Removing the registrar calls also **fixes the `/ViewStudy` 500**. That page, the study-name link on every legacy page, has failed on every study since the Spring 6 upgrade. First check whether anything outside the chain uses the `/odmk/studies/{study}/metadata` endpoint; the study-metadata download (R1) may want it.

*If the chain is kept instead:* add `org.apache.httpcomponents.client5:httpclient5` (Boot-managed), and port the OpenRosa form-delivery resources from Jersey to Spring MVC.

**R0.7 — broken live pages.**

- **`/ConfigurePasswordRequirements`** renders blank because its JSP imports `javax.servlet`. The SPA page covers it, so close it now.
- **`/form`** (`FormServlet`) reads a demo `.xls` that isn't shipped. Delete it.
- **`/pages/listCurrentScheduledJobsData`** fails whenever any cron trigger exists, because it casts every trigger to `SimpleTrigger` (`ScheduledJobController.java:139`). It goes with the jobs wave; fix the cast only if the page must live longer.
- **An expired session displaced by a second login answers 500** (admin catalogue). This was not reproduced with an unknown cookie, so recreate it first.

**R0.8 — dead-code sweep 2.**

- `includes/allIcons.jsp`: JSTL 1.0 URIs, and nothing includes it.
- `decorator.jsp` and the GWT menu reference: SiteMesh has not been a dependency since 2020.
- The `ws` / `OpenClinicaJersey` / `OpenClinicaJersey2` zombie registrations, and the `/ws/**` and `/rest2/openrosa/**` public entries: none of it loads on jakarta.
- The four `Page` constants that point at deleted files but are still compared in `BreadcrumbTrail` / `StudyInfoPanel`. Remove the comparisons with them.

**R0.11 — documentation drift.** Found by the 2026-09-30 plan survey; the second bullet is verified in this plan.

- **`MIGRATION.md`:**
  - calls Phase C closed, although the archived Phase C playbook records it as partial;
  - its D-Libs table still lists POI 3.0.1 and FOP 1.0;
  - it links `ui-modernization-plan.md`, which does not exist;
  - it still describes an upstream-merge protocol.
- **`CLAUDE.md`:**
  - still says "~295 legacy servlets" and SiteMesh; it is 214, and SiteMesh has not been a dependency since 2020;
  - still lists the listing-page wave as open, although it has shipped.
- **Phase E playbook and decision lists:**
  - still show DR-008 and DR-019 as open;
  - the open-decisions list still carries DR-009 (obsolete), DR-012 (done) and DR-013 (Ehcache 3 in practice).
- **Heritage-debt audit:** still shows "TBD" for items fixed in `a3268c61b`.
- **Missing document:** `legacy-retirement-2026-06-20.md` is cited but does not exist.
- **DR-004** probably needs an amendment: its "no clinical onboarding before Phase D ends" clause is in tension with the imaging pilot while D-Libs is open. This one is inferred, not verified.

---

## 6. Stage R1 — close the SPA gaps

### R1.0 SPA-coverage columns for the other three catalogues

[investigator-features.md](phase-e/investigator-features.md), [monitor-features.md](phase-e/monitor-features.md) and [data-manager-features.md](phase-e/data-manager-features.md) predate the SPA and record legacy features only. Add the same SPA-coverage column the admin catalogue has, by the same method: a live walk as that role, checked against the servlet, JSP, view and API source. That produces the definitive gap list for the non-admin areas.

The survey's list of servlets with no mapped successor gives the areas to expect:

| Area | Servlets |
|---|---:|
| Data entry and CRF viewing | 21 |
| Study build and configuration | 16 |
| CRF versions | 8 |
| Rules | 8 |
| Account and login | 7 |
| Global subject registry | 6 |
| Admin tooling | 5 |
| Extract | 5 |
| Scheduled jobs | 4 |
| Study users | 3 |
| Data import | 2 |
| Other | 4 |

Several of these have an obvious SPA counterpart the catalogue simply omits.

### R1.1 Administrator gaps: needed, substantial

| Feature | Why it is needed | Notes |
|---|---|---|
| All-studies list for a system administrator, with remove and restore | An administrator must see and manage every study, including those they are not bound to. Today `/pick-study` is empty for an unbound administrator | `StudiesApiController.list` returns bindings only. `/disable` and `/restore` exist; check that `/disable` cascades like `RemoveStudyServlet` |
| Grant a role in any study | Same root cause: the *Add study* picker lists only the administrator's own studies | Use the all-studies source for system administrators |
| Login history over `audit_user_login` | The only UI over the login audit. Phase D extended the table for SSO (D.5), so the project already treats it as evidence | Server-side filter (user, status, date range) and paging; the legacy page is capped at 500 unfiltered rows |
| Batch migration of existing event CRFs to another CRF version | Moving existing data onto an amended CRF version mid-study is routine data management | The JSON API exists (`/pages/api/v1/forms/migrate/{preview,run}`). It also covers the single-event-CRF version change (`ChangeCRFVersionController`), which is uncovered today |

### R1.2 Administrator gaps: needed, small

- **Study create/edit:** bind contact e-mail, collaborators and detailed description (already in the API).
- **Study metadata (ODM) download:** needed for the archive at study close. Reuse an existing metadata endpoint if R0.6 keeps one, and compare its output with `DownloadStudyMetadataServlet` first.
- **Users:**
  - send `userType` from the create and edit dialogs, so the SPA can create, promote and demote administrators;
  - show the administrator flags and the created/owner/updated details, which periodic access reviews need;
  - fix two defects in `EditUserDialog.vue`: it does not pre-fill phone or affiliation, and it cannot clear a field.
- **Security settings:** show lockout on/off and the failed-attempt count on `/admin/password-policy`.
- **Test e-mail:** a POST action on `/admin/system-status` (optional).
- **CRFs:**
  - restore a disabled CRF — today disabling is one-way;
  - a read-only preview of a published version;
  - the item table and the studies-using list on the CRF view;
  - edit a CRF's name and description.
- **Jobs:**
  - edit an SPA export schedule (today it can only be deleted);
  - pause and resume (optional);
  - move any legacy XSLT export jobs to SPA schedules, then drop the legacy ones.

### R1.3 Other gaps known now

- **An SPA logout API.** The SPA calls the legacy `GET /Logout`.
- **Login success must not depend on `/MainMenu`.** Give the SPA login a JSON or 204 success response.
- **Replace the `CreateDataset` link** in `DatasetListView.vue:244`.
- **Remove an event CRF.** The SPA can restore a removed event CRF but has no way to remove one. This is a Data Manager gap: the legitimate path for data entered on the wrong subject or event.
- **Fixing a full date of birth.** The SPA edits sex and year of birth. If any study collects a full date of birth, it cannot correct that field.
- **DR-029:** retire the two older upload controllers that the SPA still calls.

### R1.4 Role consolidation (§3.2.3)

1. Count the holders.
2. Write the migration changeset: `study_user_role.role_name` `ra` / `ra2` → the target role.
3. Record the change in the user-access record: rights change, so it must be deliberate.
4. Remove the legacy-only grant path.

### R1.5 Not rebuilt

These close in wave W0:

- **2FA, scheduled imports and login notification** (§3.1).
- **The global subject registry**, which is superseded by MUW's own patient identity: the `patient` table, `link-patient` and `/patients`. Person-level cascade removal is not a GCP-appropriate operation.
- **Hard delete of an event CRF's data.**
- **The registry-style study sections:** dates, design, eligibility, facility, related information.
- **Delivery channels and flags nothing uses any more:** e-mailed passwords, the SOAP flag (the module is gone) and LDAP (SSO replaces it).
- **Legacy-only tooling:** the schema change log, the blank CRF template, the admin and TechAdmin home counts, the running-jobs page, and `/Enterprise`.

---

## 7. Stage R2 — close legacy screens in waves

Each wave closes one area once every screen in it has a recorded SPA replacement or a recorded "not needed".

| Wave | Area | Legacy size | Enters when | Risk |
|---|---|---|---|---|
| **W0** | Screens recorded as not needed (R1.5), plus screens already fully covered and admin-only | ~25 servlets | R0.3 and R0.4 done | Low |
| **W1** | Administration: users, studies, CRF library, jobs, system | admin 63 JSPs / 56 servlets | R1.1 and R1.2 shipped | Medium |
| **W2** | Extract and export | extract 46 / 21 | Export schedule edit; legacy XSLT jobs migrated | Medium |
| **W3** | Study build (`managestudy`): event definitions, sites, groups, rules, study module | 119 / 85 | R1.0 gaps closed for Data Manager | Medium |
| **W4** | Data entry and review (`submit`): entry, DDE, notes, SDV, sign, print | 68 / 37 | R1.0 gaps closed for Investigator and Monitor; **E.10 usability panel run on the SPA** (DR-019) | **High**: this is where wrong data gets written |
| **W5** | Shell and login: `MainMenu`, `ChangeStudy`, `Logout`, login and account JSPs, `include/`, error pages | login 20 / 9, include 52 | Everything else closed; R1.3 couplings removed | Medium |

**Per-wave checklist:**

1. Record parity per screen and per role in the retirement log.
2. Check that no SPA link or redirect leads into the area.
3. Add the area to the closed-paths list, making the original URL answer 410 and `/legacy/` open to administrators only.
4. Announce the change to users.
5. Crawl diff and server-log check.
6. The six-month clock starts.
7. Review the `legacy-hit` log monthly. Any hit by a non-administrator means the parity record is wrong: reopen that screen and fix the SPA gap.

---

## 8. Stage R3 — delete

Six months after a wave closes, and only if the log shows no legitimate use, delete the wave's:

- servlets and their `LegacyServletRegistry` entries;
- JSPs;
- `Page` constants;
- telemetry-catalogue entries;
- public-path entries;
- tests of deleted code;
- static assets used only by those pages;
- DAO, bean and service code whose last caller was a deleted servlet. The compiler proves this; use `jdeps` or a grep for anything reflective.

Remove i18n keys only when no remaining source references them. Run the full gate plus the crawl diff. The alerts in deleted files close automatically.

**After W5 is deleted:**

- remove `LegacyServletRegistry`, `SecureController` and its helpers, the telemetry filter and the alias;
- remove the JSP/JSTL dependencies, jQuery 1.9.1, Prototype, Scriptaculous, DataTables and the GWT leftovers;
- remove Jersey, after the ODM clinical-data export is extracted from the `web/restful` beans into a service. That extraction can happen any time before then.

---

## 9. Stage R4 — platform work that deletion makes cheaper

From the plan survey:

- **Do after R3.** Most of the affected files are in legacy code:
  - commons-lang 2 → 3 (54 files) and commons-collections 3 → 4 (5 files);
  - finishing Phase C: 10 XML contexts (~120 beans), typed configuration instead of `datainfo.properties`, DBCP 1.x → HikariCP (DR-011).
- **Independent, and can go any time:**
  - iText 2.1.2 → PDFBox (2 call sites, PDFBox already present);
  - a Liquibase 4 spike. There are 7 `modifyColumn`s, not 4; test the lax changelog parse mode before anything else;
  - aligning the Hibernate, Quartz, Liquibase, Jackson and Ehcache pins with the Boot BOM. Moving Hibernate makes the 49 forward-looking CodeQL deprecations real.
- **Time-critical, outside this plan:**
  - PostgreSQL 14 reaches end of life in November 2026;
  - check the open-source support window of Spring Boot 3.5 / Spring Framework 6.2.
- **Optional:** WAR → executable JAR.

---

## 10. Track S — code scanning

### 10.1 Rules

- **Security-level alerts on `main`: zero at every release.** Check after every `lc-develop` → `main` merge, because alerts reflect `main`.
- **Quality alerts** are fixed when they point at a real defect, fixed in batches when mechanical, and otherwise dismissed with a reason of at most 280 characters.

### 10.2 Security (now)

| # | Item | Action |
|---|---|---|
| S1 | 9 CodeQL alerts on `lc-develop` (1 `java/sensitive-log`, 8 `java/log-injection`), introduced by the 2026-09-29 quality waves | Delete the nine debug/info log lines in `ViewSiteServlet`, `InitUpdateSubStudyServlet`, `InitUpdateEventDefinitionServlet`, `ExportDatasetServlet`, `CreateFiltersTwoServlet`, `OdmController` (×2) and `AnonymousFormControllerV2`. The high one logs a configured base URL (a false positive in substance), but deleting is simpler than arguing. **Before the next merge to `main`** |
| S2 | 4 Trivy alerts in `web/src/spa/pnpm-lock.yaml`: `brace-expansion` 1.1.18 (3 CVEs) and `fast-uri` 3.1.7 (1) | Raise the `package.json` override `brace-expansion@1` to 1.1.21, and check whether the 2.x copy (2.1.4) needs 2.1.7. Raise `fast-uri` to 3.1.8. Then the SPA build and vitest |
| S3 | 4 CodeQL alerts on `main` (`OdmController:336`, `CreateXformCRFVersionServlet:283`, `ExportDatasetServlet:211`, `FileUploadHelper:121`) | Already fixed on `lc-develop` (`6a7683f85` and the 2026-09-29 fixes); they close with the merge |
| S4 | Trivy is report-only (`security.yml:46`, `exit-code: '0'`) | Switch to `1` once S2 is merged, so a new vulnerable dependency fails the build |
| S5 | Findings not yet raised as alerts | R0.5 (GET-mutating admin actions), R0.9 (authorization review), R0.10 (public-path review) |

### 10.3 Quality (1,160 on `lc-develop`)

**By area:**

| Area | Alerts |
|---|---:|
| Legacy servlets (`control/**`) | 219 |
| Heritage Spring MVC controllers | 145 |
| Other `web` Java | 132 |
| `core` | 609 (bean 158, dao 153, logic 92, service 80, domain 75, …) |
| SPA API (`controller/api`) | 47 |
| `odm` | 7 |
| Python sidecars | 9 |

**By rule** (top): `unused-parameter` 309, `uncaught-number-format-exception` 151, `dereferenced-value-may-be-null` 149, `internal-representation-exposure` 84, `deprecated-call` 82, `useless-tostring-call` 71, `inefficient-empty-string-test` 54, `abstract-to-concrete-cast` 50, `non-null-boxed-variable` 39, `useless-null-check` 29, `reference-equality-on-strings` 27.

**How to treat them, in this order:**

1. **Real-defect rules in code that stays:** fix with a red-then-green test, one package per PR, as the 2026-09-29 waves did. The rules: `dereferenced-value-may-be-null`, `uncaught-number-format-exception`, `reference-equality-on-strings`, `inconsistent-compareto-and-equals`, `constant-comparison` and `useless-null-check`. Earlier waves turned up real defects here: the ODM `NewValue` omission, the identity comparison, the post-dereference null guards.
2. **`deprecated-call`:** fix the non-Hibernate ones now. The Hibernate ones become real with the Hibernate bump (R4), so fix them there.
3. **Mechanical rules in code that stays:** batch PRs, applied only where the change is behaviour-neutral. Keep `"".equals(x)`, which is null-safe, and leave `.toString()` removals that change null behaviour. Dismiss `unused-parameter` on overridden or interface methods as a false positive, with that reason.
4. **Code a wave will delete:** don't fix it. Once its wave is **closed** (not before, so the reason is true), dismiss as *won't fix* with the reason "retired under DR-018 wave N; deleted after bake-in". The files' deletion closes the rest.
5. **Python sidecars and `odm`:** small, fix directly.

---

## 11. Sequence and indicative calendar

The dates are indicative estimates, not commitments. Only the six-month bake-in is fixed, by DR-018.

| When (indicative) | What |
|---|---|
| Oct 2026 | R0.1–R0.2 and S1–S4 (days); R0.3–R0.4 tracking and alias; R0.5–R0.10; W0 closed |
| Oct–Dec 2026 | R1.0 catalogue columns; R1.1–R1.4; W1 closed |
| Dec 2026 – Feb 2027 | W2 and W3 closed; E.10 usability panel on the SPA |
| Feb – Mar 2027 | W4 closed, then W5 |
| Apr 2027 onward | R3 deletions in wave order, each six months after its close: W0 about Apr 2027, W5 about Sep 2027 |
| Alongside | Track S quality batches; R4 independent items; the PostgreSQL upgrade plan before Nov 2026 |

---

## 12. Risks

- **Tracking stays broken:** there is then no evidence of non-use and nothing can be deleted. R0.3 comes first for that reason.
- **Hidden couplings:**
  - login success lands on `/MainMenu`, and the SPA logs out through the legacy `/Logout`;
  - JSP includes resolve at runtime;
  - DAOs are shared between servlets and SPA APIs.

  Mitigation: the crawl diff on every retirement and deletion PR, and the compiler for the Java side.
- **Rights change in the role consolidation:** see §3.2.3. It is decided explicitly and recorded.
- **Data-entry waves write clinical data:** W4 only after the E.10 panel and a full DDE/SDV/sign regression on the SPA.
- **Production unknowns:** the plan assumes the shipped configuration. §13 checks it.
- **Surveys have been wrong before:** three times in this effort. Each wave re-verifies its screens live before closing them.

---

## 13. Production checks

These are read-only. Lukas runs them, or asks for them to be run.

```sql
-- Data Entry Person role holders (decides §3.2.3)
SELECT role_name, count(*) FROM study_user_role
 WHERE role_name IN ('ra','ra2') AND status_id = 1 GROUP BY role_name;

-- Any scheduled import or legacy XSLT export jobs still defined?
-- (lc-muw-retention and exportJobPoller are MUW's own jobs; any other group is legacy)
SELECT trigger_group, count(*) FROM oc_qrtz_triggers GROUP BY trigger_group;

-- Studies using the participant portal or login notification
SELECT count(*) FILTER (WHERE mail_notification = 'ENABLED') AS login_mail
  FROM study;
SELECT parameter, value, count(*) FROM study_parameter_value
 WHERE parameter IN ('participantPortal') GROUP BY parameter, value;

-- Event-definition CRFs marked as participant forms
SELECT count(*) FROM event_definition_crf WHERE participant_form = true;
```

Plus `2fa.activated`, `xform.enabled` and `portalURL` in the production `datainfo.properties`.

*The queries ran against the dev schema on 2026-09-30. The participant-portal query only finds studies where the setting was saved; an unset parameter means disabled.*

## 14. Implementation status (2026-10-02)

The plan was implemented in one pass starting on 2026-09-30, as a set of pull requests against `lc-develop`. This section records what shipped, what was decided along the way, what was added because the work found it, and what is deferred and why. Where there is no PR, the branch is named instead.

### 14.1 Decisions taken while implementing

These were delegated ("make the best educated choice") and are recorded here so each can be reviewed.

| # | Decision | Reason |
|---|---|---|
| D1 | **The XForm / OpenRosa / participant-portal chain is dropped** (R0.6, §3.2.2) | It cannot work end to end: Jersey no longer loads, HttpClient 5 is not shipped, no portal or Enketo is deployed, `xform.enabled` is unset, and the MUW public pages do not use it |
| D2 | **`ra` / `ra2` holders are not migrated automatically** (§3.2.3) | Both targets widen rights: CRC passes study-admin and dataset gates, Investigator adds e-signature and reopen. The roles stay as they are, are shown in the SPA as "Data Entry Person (legacy)", and a role change needs an explicit choice (the API answers 409 without one). The migration waits for the production count (§13) and a deliberate GCP decision |
| D3 | DR-018 is accepted | The whole plan depends on it |
| D4 | The six-month bake-in is kept | DR-018 requires it; R3 deletions are therefore deferred by design |
| D5 | **Open:** the role model for the legacy `coordinator` ("Data Manager" in legacy, CRC in the SPA) | The backend already lets `coordinator` administer the study; the SPA routes do not. Recommendation: make the SPA gates follow the backend. Blocks W3 |
| D6 | **The heritage REST API under `/pages/auth` closes in wave 0** | Seven OpenClinica 3.x controllers, API-key only, with no caller in the app, the SPA, the legacy pages or the deployment. Closing is a configuration change and every call is logged during the bake-in |
| D7 | Investigators, monitors and data-entry users download the study metadata from the SPA home page | So `/DownloadStudyMetadata` can close in W1; no SPA page for the current study existed for these roles |
| D8 | **Export schedules: only the creator or a system administrator changes one** | Its runs execute as the creator |
| D9 | **The server-side notes CSV neutralises formula-like text cells; clinical data exports are unchanged** | A spreadsheet opens a cell that begins with a formula character as a formula; changing clinical data exports would alter their content |

### 14.2 What shipped

| Plan item | What | PR |
|---|---|---|
| R0.1 | tri-state reason, dead JSPs, DR-018 + catalogue + plan | #362, #363, #364 |
| R0.2 / S1–S4 | log lines; SPA overrides; Trivy blocking with an accepted-findings gate | #365, #366 |
| R0.3 / R0.4 | working legacy-access log (220-day retention), per-path closure, `/legacy/` alias | #370 |
| R0.5 / R0.9 | admin actions POST-only; event scheduling checks the study | #367 |
| R0.6–R0.8, R0.10 | participant chain removed; broken pages fixed; dead code; public paths narrowed (session migrate endpoints need a login; the public rule-timezone helper deleted) | #373, #374 |
| R0.11 | documentation drift | #364 |
| R1.0 | SPA-coverage columns for the investigator, monitor and data-manager catalogues | #364 |
| R1.1 / R1.2 (studies, users) | all-studies list, remove/restore cascades, grant in any study, study fields, metadata download, admin types, account details, legacy roles explicit | #377 |
| R1.1 / R1.2 (audit, security) | login history, lockout settings, test e-mail | #381 |
| R1.1 / R1.2 (CRFs) | CRF edit, restore, view with item table and studies, version preview, event-CRF version migration (batch and single) | #387 |
| R1.2 (jobs) | export schedule edit, pause/resume; legacy XSLT jobs; cancel; dataset wizard link | #389 |
| R1.3 | SPA login answers JSON; SPA logout API | #384 |
| R1.3 (DR-029) | old SPA upload pages deleted | #378 |
| R2 W0 | 17 not-needed screens and the heritage REST API closed | #371 |
| R4 | PostgreSQL 17 for dev/test/CI + production runbook | #376 |
| R4 | OpenPDF (DR-007), Hibernate 6.6, Quartz 2.5 | #385 |
| R4 | Liquibase 4 | #388 |
| R4 | Spring Boot 4 spike | branch `spike/muw-spring-boot-4` (report in this branch) |
| Track S | quality alerts: core dao, bean, logic, service, web support | #372, #375, #379, #382 (core service), #383 (web support) |

### 14.3 Added because the work found it

The R1.0 catalogue walks and the reviews found gaps the plan did not list. Each was evaluated and fixed with red-then-green tests:

- **Server-side role checks on the SPA's clinical writes** and SDV integrity (only complete CRFs; a data change withdraws SDV; un-verify needs a reason) — #386.
- **Every legacy action that changes data takes a POST** (107 servlets), and the legacy data-entry servlets require a data-entry role — #380.
- **Clinical data-integrity gaps in the SPA data-entry path** (reason for change after reopen, required items at completion, subject identifiers, rules on save) — #391.
- **The monitor's SPA gaps** — #390.
- **The data manager's SPA gaps** (import commit, audit paging, cascades) — #392.
- **Item-data provenance** survives status cascades (`ItemDataDAO.updateStatusOnly`) — #377 for the SPA paths; the legacy remove, restore, lock and unlock servlets use `updateStatusOnly` (or `updateStatusAndOldStatusOnly` where a restore reads `old_status_id`) — branch `fix/muw-legacy-status-provenance`.
- **Dependabot:** 20 open alerts on the SPA lockfile; 3 fixed, 17 dismissed with the reason on each alert — #366.

### 14.4 Deferred, with the reason

| Item | Reason | Next step |
|---|---|---|
| R3 deletions | The six-month bake-in (DR-018, D4) | W0 deletion no earlier than six months after #371 reaches production |
| W1–W3 closure | Each wave closes only once its SPA gaps have landed and parity is recorded | Close per the retirement log once the packages above are merged |
| W3 | Also blocked by D5 (role model) | Maintainer decision |
| W4 | Needs the E.10 usability panel on the SPA (DR-019), a human study | Schedule the panel |
| W5 | Needs every other wave closed, and four couplings removed first: the expired-session redirect targets `/MainMenu` (also for SPA API calls); SSO logins land on `/MainMenu` and get their session set-up there; e-mail links use `sysURL`; the legacy logout's success target | A follow-up package before W5 |
| `ra` / `ra2` migration | D2 | Production count (§13), then an explicit choice |
| DR-029 backend endpoints | The combined upload controller delegates to both older controllers, so only their HTTP mappings are unused | Remove the mappings with W4 |
| SPA toolchain (vitest 4, Vite 6+, Histoire 1.x) | Dependabot #23/#24 need vitest 4, which needs Vite 6; dev-only and not exploitable as used | A separate toolchain PR |
| Liquibase 4 | done (#388) | none |
| Spring Boot 4 | DR-037; see the spike result | Per the spike |
| commons-lang/collections, Phase C finish | Most affected files are legacy (§9) | After R3 |
| Production checks (§13) | No production access in this pass | Lukas runs the queries |
