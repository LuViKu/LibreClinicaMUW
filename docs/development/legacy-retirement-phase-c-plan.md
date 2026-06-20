# Legacy retirement — Phase C SPA build-out plan

Companion to PR #234 (Phase A: telemetry + banner + flags) and the
[2026-06-20 audit](legacy-retirement-2026-06-20.md). Phase C replaces
the **shim** + **keep-for-now** legacy surfaces with SPA equivalents
so Phase D can drop the underlying servlets + JSPs cleanly.

L1 (login redirect) ships in this PR — it's a contained one-file change
and the highest-traffic surface, so the win is biggest. L2–L5 are
documented here with file paths, contract sketches, and effort estimates
so the next session can pick any one up without re-discovery.

## L1 — Login redirect (SHIPPED IN THIS PR)

Replaces the legacy form-login GET `/pages/login/login` with a 302
redirect to `/LibreClinica/app/login`, preserving any query string
(`?error=…`, `?returnTo=…`). The legacy JSP at
`/WEB-INF/jsp/login/login.jsp` stays in the tree as a build-time
artifact but receives zero traffic after this PR.

- `WebMvcConfig.loginLoginRedirectController()` returns a small inline
  `Controller` that writes status 302 + Location header.
- `LegacyLoginRedirectTest` (3 cases): empty query, `?error=`, and
  `?returnTo=…` paths.
- POST `/j_spring_security_check` is unchanged; legacy form-auth still
  owns credential check — the redirect only moves the input UI.

## L2 — Contact form

**Why:** `ContactServlet` lets unauthenticated users email the
institutional admin. Low MUW volume (per memory
`project_production_scope` — single-site Vienna, users have direct
admin contact), so this is the smallest L slice.

**Files to add (~150 LOC total):**
- `web/src/main/java/.../controller/api/ContactApiController.java` —
  one `POST /api/v1/contact` endpoint. Body: `{name, email, subject,
  message}`. Auth: `permitAll()` (same as legacy).
  Reuses `EmailEngine.getAdminEmail()` for the to-address + the
  existing mail-sender bean. Returns 204 on success or 400 with field
  errors; rate-limited per IP (10 req / hour) via the existing
  `PublicOctUploadRateLimitFilter` pattern.
- `web/src/spa/src/views/ContactView.vue` — small form mirroring
  `BugReportDialog.vue` shape (subject + message). i18n keys under
  `contact.*` (currently EN/DE for the existing JSP — copy them).
- `web/src/spa/src/router/index.ts` — add `path: '/contact'`,
  `name: 'contact'`, `meta: { public: true }`.

**Catalog update:** add `/pages/Contact` to
`LegacyServletDeprecationCatalog` under a new `SUPPORT_FORMS` bucket
pointing at `/app/contact`.

**Tests:** `ContactApiControllerTest` (rejected on missing fields,
204 on happy path), `ContactView.spec.ts` (renders, submits, surfaces
422 errors).

**Effort:** ~3-4 hours.

**Out of scope:** `RequestPasswordServlet` + `RequestAccountServlet`.
Per memory `project_muw_workflow_decisions` (2026-06-05): "1 sysadmin
+ 2 service accounts → drop self-service forgot-password, ship
DM-initiated `/users/{username}/reset` only." Self-service request
forms get retired without replacement; legacy JSPs go directly to the
Phase D delete list.

## L3 — Admin tooling

**Why:** Sysadmin views — system status, scheduler, password policy,
app config. Read-mostly, low frequency, but operationally important
during incidents.

**Files to add (4 views, ~600 LOC total):**

### L3.1 — System status (`/app/admin/system-status`)
- Existing: `SystemStatusServlet` renders `admin/systemStatusReport.jsp`
- Pre-Phase E `SystemStatusServlet` reads JVM metrics + Postgres
  connection state + Quartz scheduler heartbeat
- New: `GET /api/v1/admin/system-status` returns
  `{jvm: {heapMb, freeMb, threads}, postgres: {connections, version},
  scheduler: {running, jobs}}`
- SPA view: 3 panels (JVM / Postgres / Scheduler) with refresh button
- Role gate: `Administrator` only

### L3.2 — Scheduler view (`/app/admin/scheduler`)
- Existing: `ViewSchedulerServlet` lists Quartz jobs + their cron
  + next-fire-time
- New: `GET /api/v1/admin/scheduler` returns `[{name, group, cron,
  nextFireAt, lastFireAt, durationMs, state}]`
- SPA view: DenseTable of jobs with a "Trigger now" button per row
  (POST `/api/v1/admin/scheduler/{name}:fire`)
- Role gate: `Administrator` only

### L3.3 — Password policy (`/app/admin/password-policy`)
- Existing: `ConfigurePasswordRequirementsServlet` reads/writes a few
  `system_settings` rows (`min_length`, `complexity`, `expiry_days`,
  `rotation_grace`)
- New: `GET/PUT /api/v1/admin/password-policy` returning/accepting
  the same shape
- SPA view: form (3-4 fields). Reuse `FieldLabel` / `TextInput` /
  `NumberInput`. Audit hook on save.
- Role gate: `Administrator` only

### L3.4 — App config (`/app/admin/config`)
- Existing: `ConfigureServlet` reads/writes deployment-time settings
  (timezone, locale, max-upload-size). At MUW these come from env vars
  per `project_production_scope` ("config + 2FA settings are
  deployment-time concerns"), so this view is **read-only** and just
  surfaces the resolved values for the sysadmin to verify
- New: `GET /api/v1/admin/config` (read-only) returning
  `{timezone, defaultLocale, maxUploadMb, sysUrl, supportEmail}`
- SPA view: definition list. No write path.
- Role gate: `Administrator` only

**Catalog update:** add 4 legacy paths to
`LegacyServletDeprecationCatalog` under a new `ADMIN_TOOLING` bucket.

**Tests:** one controller test + one vitest spec per L3.x; ~12 specs
total.

**Effort:** ~1-2 weeks.

## L4 — Print PDF

**Why:** High MUW value per CLAUDE.md — paper-first workflow needs
print before nurse data-entry.

**Decision before implementation:** the legacy "print" servlets
(`PrintEventCRFServlet`, `PrintCRFByIdServlet`,
`PrintAllSiteEventCRFServlet`) render print-friendly HTML, NOT real
PDFs. Two options for replacement:

**Option A — Print-friendly HTML view (faster, ~3 days)**
- New `GET /api/v1/event-crfs/{id}/printable` returns the same DTO
  the SPA's `CrfEntryView` already consumes, but with all sections
  expanded + no input UI (read-only spans).
- SPA route `/app/event-crfs/:eventCrfOid/print` renders the DTO
  with a `@media print` stylesheet that hides nav / sidebar / app
  chrome. Operators trigger via the browser's File → Print → PDF.
- Print button on `CrfEntryView` opens the print route in a new tab.

**Option B — Server-rendered PDF (slower, ~1-2 weeks)**
- Pull in `openhtmltopdf` or `flying-saucer` (already a transitive
  dep via the existing dataset-export pipeline).
- New `GET /api/v1/event-crfs/{id}/print.pdf` returns
  `application/pdf` bytes.
- Larger code change + asset-resolution headache (fonts, logos), but
  gives consistent output independent of browser.

**Recommendation:** Option A. MUW operators already use browser
"Print to PDF" for institutional forms; Option A matches that flow
+ ships fast.

**Catalog update:** add the 6 print servlet paths to
`LegacyServletDeprecationCatalog` under a new `PRINT_PDF` bucket
pointing at `/app/event-crfs/:eventCrfOid/print`.

**Effort:** ~3-4 days (Option A) / ~1-2 weeks (Option B).

## L5 — Job admin

**Why:** Sysadmin needs to see background jobs (data import, data
export, retinal-inference queue). Currently `ViewAllJobsServlet`
renders the list as a JSP table.

**Files to add (~250 LOC total):**
- `GET /api/v1/admin/jobs` returns `[{jobId, kind, status, startedAt,
  finishedAt, owner, errorMessage}]` from the `quartz` job tables +
  the `export_job` / `retinal_inference_job` queues
- SPA view `JobsAdminView.vue` — DenseTable. Per-row "Retry" button
  for failed rows; per-row "Cancel" button for running rows.
- Role gate: `Administrator` only

**Catalog update:** add 9 job-related paths to
`LegacyServletDeprecationCatalog` under a new `JOB_ADMIN` bucket.

**Effort:** ~1 week.

## Cross-cutting follow-up after all 5 slices ship

Once L1-L5 are merged + the grace-period telemetry log is empty for
4 weeks, Phase D opens 5-7 small PRs each removing one bucket:
servlet registration deletion + JSP file deletion + last touches to
any audit/security configs that referenced the deleted paths.
Expected savings: ~2k LOC of Java + ~300 JSP templates.
