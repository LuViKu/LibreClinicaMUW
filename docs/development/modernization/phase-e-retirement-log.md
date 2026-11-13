# Phase E — legacy UI retirement log

The record [DR-018](decision-record.md) point 6 requires. Every retirement of a legacy screen or file is logged here: what went, what replaces it, when it was closed and deleted, and who signed it off. The sequence is in the [JSP retirement plan](jsp-retirement-plan-2026-09-30.md).

**How a screen retires:**

1. **Dead code** (nothing reaches it) is deleted directly; there is no bake-in.
2. **A covered or not-needed screen** is first **closed**. Its URL goes on the closed-paths list (`libreclinica.legacy.closedPaths`). After that, anyone who is not an administrator gets 410 with a pointer to the SPA route, and an administrator can still reach the screen through `/legacy/<path>`. Every hit is logged under the `legacy-access` logger.
3. **Four weeks after closing**, and only if the log shows no legitimate use, it is **deleted**. (Six months until the [DR-018 amendment of 2026-11-13](decision-record.md#amendment-2026-11-13--one-wave-and-a-four-week-bake-in), for the test phase.)

The clock starts when the closure reaches **production**, not when it merges.

---

## Deleted directly (dead code)

| Date | What | Evidence it was dead | PR | Signed off |
|---|---|---|---|---|
| 2026-09-30 | **36 JSPs** unreachable from any servlet, controller or page: `403.jsp`, `404.jsp`, `access_confirmation.jsp`, `admin/auditLogStudyOld.jsp`, `admin/createCRFConfirm.jsp`, `admin/createCRFVersionConfirmSQL.jsp`, `admin/showAuditEventStudyRow.jsp`, `admin/showSubjectRow.jsp`, `admin/systemStatus.jsp`, `admin/uploadCRFVersionFile.jsp`, `admin/viewScheduler.jsp`, `allSdvResult.jsp`, `extract/createDatasetApplyFilter.jsp`, `extract/createFilterConfirm.jsp`, `extract/selectDiscrepancyAttributes.jsp`, `extract/viewEmptyDatasets.jsp`, `include/breadcrumb.jsp`, `include/showEntities.jsp`, `include/showMessages.jsp`, `include/showPanel.jsp`, `include/showPresetValueText.jsp`, `include/showTableWithTabForDNotes.jsp`, `include/showTerms.jsp`, `include/showTextInput.jsp`, `include/userbox.jsp`, `login-include/footer.jsp`, `login-include/login-sidebar.jsp`, `login/contactPop.jsp`, `managestudy/listSubject.jsp`, `managestudy/showSectionWithoutComments.jsp`, `managestudy/showSubjectDiscNote.jsp`, `submit/initialDataEntry.jsp`, `submit/listEventsForSubjects.jsp`, `submit/showSection.jsp`, `viewSubjectAggregateSDV.jsp`, `viewSubjectSDV.jsp`; plus six `Page` constants that pointed only at them | No `Page` forward, no controller view name, and no include or link by path or by bare filename. A before/after crawl of 176 legacy URLs as two roles gave identical status codes, and the server log showed no missing JSP | #363 | pending (Lukas) |
| 2026-09-30 | `/pages/user` (`UserController`, `user.jsp`), the upstream Spring MVC demo page with four hard-coded names | nothing links to it; no feature | #369 | pending (Lukas) |
| 2026-11-13 | `BreadcrumbTrail`, `BreadcrumbBean` (the legacy breadcrumb builder) | its only call sites in `SecureController` and `CoreSecureController` are commented out, and the JSP that rendered a trail went with the 36 dead JSPs | #445 | pending (Lukas) |

## Closed (in bake-in)

| Wave | Closed in code | Reached production | Paths | SPA replacement | Delete not before | Signed off |
|---|---|---|---|---|---|---|
| W0 — heritage REST API (bucket HERITAGE_API) | 2026-09-30 (#371) | 2026-10-08 (1.5.0-beta.16-muw) | `/pages/auth` (seven OpenClinica 3.x controllers; API-key only, no caller in the app, the SPA or the deployment; reversible through `LIBRECLINICA_LEGACY_CLOSED_PATHS`) | none needed | 2026-11-05 (was 2027-04-08; four weeks since 2026-11-13) | pending (Lukas) |
| W2 — everything but the shell (one wave, folding the planned W2–W4) | 2026-11-13 | next release | 164 paths: every catalogue key outside the shell and the public forms — participants and visits, data entry and double data entry, notes, SDV, print, extract and export, the rest of the study build (event definitions, sites, groups, rules, `/ViewCRF`, `/ViewStudy`), study-user roles and the audit pages; see the [W2 record](#w2-record) | per screen where the catalogue names one; otherwise none (log evidence instead of a parity record) | four weeks after it reaches production | pending (Lukas) |

## Deleted after bake-in

| Wave | Deleted | Paths | Log review (hits by non-admins during bake-in) | PR | Signed off |
|---|---|---|---|---|---|
| W0 — not needed (the screens; the heritage REST API is still closed, above) | 2026-11-13 | the 17 paths closed in #371: `/Enterprise`, `/TechAdmin`, `/AdminSystem`, `/AuditDatabase`, `/ListSubject`, `/ListSubjectData`, `/ViewSubject`, `/UpdateSubject`, `/RemoveSubject`, `/RestoreSubject`, `/CreateJobImport`, `/UpdateJobImport`, `/ViewImportJob`, `/ViewLogMessage`, `/PrintoutCertificate`, `/DeleteEventCRF`, `/ConfigurePasswordRequirements` | none: the `legacy-hit` log from 2026-10-08 (the start of the bake-in) to 2026-11-13 holds 16 lines, all by `root` or before a login, none on a W0 or W1 path | #445 | pending (Lukas) |
| W1 — administration | 2026-11-13 | the 43 paths closed in #396, listed in the [W1 parity record](#w1-parity-record) | none (same log review) | #445 | pending (Lukas) |

---

## W0 and W1 deletion record

Deleted 2026-11-13, four weeks after both waves reached production (1.5.0-beta.16-muw, 2026-10-08), under the [DR-018 amendment](decision-record.md#amendment-2026-11-13--one-wave-and-a-four-week-bake-in).

**What went.** The 55 servlets behind the 55 servlet paths, with their `LegacyServletRegistry` entries (210 registrations → 155); the two Spring MVC controllers behind the five `/pages` routes (`ScheduledJobController`, `LdapUserController`); 60 JSPs, the ones only these screens rendered or included; 57 `Page` constants; the 60 catalogue keys and their `closedPaths` entries (234 keys → 174; the startup line now reads 165 of 174 closed); the CRF template spreadsheet that only `/DownloadVersionSpreadSheet` served, and its copy at startup; three row beans (`AuditEventRow`, `AuditEventStudyRow`, `TriggerRow`) whose last users were the deleted pages; and the tests of the deleted code. `LockCRFVersionServlet` (W2) now holds the default-version helper it used to call on `RemoveCRFVersionServlet`, with that helper's tests.

**What a request gets now.** A deleted path is no longer a catalogue key, so the telemetry filter neither logs nor closes it, and no servlet answers it: the container answers 404, for administrators too, also under `/legacy/`.

**Left in place.**
- The study metadata API test no longer compares its document with the deleted servlet's; the comparison passed at every run until the servlet went.
- The shell's task menu (`include/navBar.jsp`, served on the open `/MainMenu`) linked the deleted administration screens and the CRF list. Those links now point at the SPA routes the parity record names (`/app/admin/studies`, `/app/manage-users`, `/app/crf-library`, `/app/admin/jobs`); the link to the global subject registry is gone (W0: not needed).
- Closed W2 servlets that ended by forwarding to a deleted screen (`/ListCRF`, `/ListStudy`, `/CreateCRFVersion`, `/UpdateSubject`; 26 call sites through four `Page` constants) now end on `/MainMenu`. The four constants point there until the W2 deletion; a before/after crawl showed those forwards failing mid-response otherwise. Links on closed W2 pages to deleted screens answer 404 to the administrator using them under `/legacy/`. Both go with the W2 deletion.
- `ImportSpringJob` still writes a `/ViewLogMessage` link into its e-mail, but scheduled imports were dropped in W0 and can no longer be created.

**Verified on a running build** (compose, fresh database, `manual_dm` and `manual_admin`), against lc-develop at the branch point: 233 catalogue keys requested directly by both roles and under `/legacy/` by the administrator. The only status changes are the 60 deleted paths (410 or 307 → 404; under `/legacy/` 200 → 404) and `/legacy/pages/studymodule` (200 → 302), which depends on the order of the crawl, not on this change: a fresh administrator session gets 200 on both builds. The server logged 42 request errors against 62 on the base, none on a request that did not also fail there. No 5xx, no JSP compilation error.

## W2 record

Closed 2026-11-13 by the [DR-018 amendment](decision-record.md#amendment-2026-11-13--one-wave-and-a-four-week-bake-in): everything but the shell, in one wave, instead of the per-area waves W2 (export), W3 (study build) and W4 (data entry and review).

**Evidence instead of a per-screen parity record.** The `legacy-hit` log on the production VM, read 2026-11-13, holds 16 requests since telemetry began on 2026-10-08, on five screens, all by `root` or before a login: `/ReassignStudySubject` and `/FindSubjectsData` (2026-10-14, the HAE-005 reassign investigation), `/UpdateProfile` and `/ResetPassword` (2026-10-20, the password workaround before 1.5.0-beta.20-muw), and `/MainMenu`. Tomcat's access logs since 2026-09-28 add only `/Logout` (2026-09-28/29). No request for a closed screen (`action=closed`) at all. The study team works only in the SPA, and no device, uploader or sidecar calls a legacy path.

**Still open (the shell wave, W5):** `/MainMenu`, `/ChangeStudy`, `/Logout`, `/UpdateProfile`, `/ResetPassword`, `/MatchPassword`, `/RequestStudy`, and the public forms `/RequestAccount`, `/Contact`. They close once the four couplings in the plan (R1.3) are removed: logins, SSO and an expired session land on `/MainMenu`, and e-mail links use `sysURL`.

**What an administrator keeps.** Every closed screen stays reachable to a system administrator under `/legacy/<path>`. That covers the one function the SPA lacks, moving a participant to another site (`/ReassignStudySubject`): an edge case at a single site, worth an SPA feature for the multi-site setup (DR-040). Note that the legacy page does not offer the parent study as a target when the participant sits at a site.

**Also closed by this wave:** the legacy signature paths (`/SignStudySubject`, and signing through `/UpdateStudyEvent`), which admitted the director, the coordinator and any system administrator; the SPA admits the investigator only.

**Known leftovers:** a legacy scheduled extract job (created on the W1-closed `/CreateJobExport`) still e-mails a `/AccessFile` link, which now answers 410 to anyone but an administrator; no such download appears in the logs. `/SystemStatus` no longer answers an unauthenticated probe; nothing in the deployment or CI calls it (the health check is `/actuator/health`).

## W1 parity record

Wave W1 closes the Administration area: the screens under `jsp/admin` and their servlets, plus the study, jobs and LDAP pages the administrator catalogue ([phase-e/administrator-features.md](phase-e/administrator-features.md)) lists with them. Prepared in branch `chore/muw-retire-w1-admin` on top of the combined gate (R1.1 and R1.2: #377, #381, #387, #389). The closure is the `closedPaths` default in `application.yml`; `LegacyClosedPathsDefaultTest` asserts every path below as closed and every held-back screen as open.

**Roles.** The administration servlets closed first checked `isSysAdmin()` in `mayProceed` (a technical administrator satisfies it), with one exception: `/DownloadStudyMetadata`, recorded below. The SPA's `/me` projects every system or technical administrator as `Administrator` whatever study role the account holds, every administration replacement route is gated to `Administrator`, and the API enforces it again. For those, "every role that used the screen reaches the replacement" reduces to "the administrator does". The CRF library servlets and `/AuditLogStudy` also admitted the study director and the coordinator; they closed after D5 (branch `feature/muw-spa-coordinator-study-build`, which this branch sits on) made the SPA admit the coordinator (CRC), and are recorded per role below.

**Accepted under delegation.** The maintainer accepted these differences between the legacy administration screens and the SPA: a new user's password is shown once instead of e-mailed; a new user is bound to the administrator's active study (further studies are granted in the roles dialog); SPA export schedules have no job name, description or completion e-mail; a legacy XSLT export job can be deleted but not paused or edited in the SPA; nothing in the SPA interrupts a running legacy job.

### Closed

| Legacy screen | Role in legacy | SPA replacement | Parity and differences accepted |
|---|---|---|---|
| `/ListUserAccounts`, `/ViewUserAccount`, `/EditUserAccount` | system admin | `/manage-users` (`ManageUsersView`, `UserDetailsDialog`, `EditUserDialog`) | List, search, details (administrator flags, created and updated metadata), edit including user type, with phone and affiliation pre-filled and clearable (#377). Dropped by decision: the SOAP flag (module gone) and the two-factor authentication type (plan 3.1) |
| `/CreateUserAccount`, `/pages/admin/listLdapUsers`, `/pages/admin/selectLdapUser` | system admin | `InviteUserDialog` on `/manage-users` | Creates local and SSO users, with user type. Differences: the new user is bound to the administrator's active study (other studies are granted afterwards in the roles dialog, which lists every study); the password is shown once instead of e-mailed; LDAP is not offered (SSO replaces it, plan R1.5, so the two LDAP pages need no replacement); the Data Entry Person roles `ra` / `ra2` can no longer be granted (D2) |
| `/SetUserRole`, `/EditStudyUserRole`, `/DeleteStudyUserRole` | system admin | `UserRolesDialog` on `/manage-users` | Grant in any study (all-studies source, #377), change roles, remove and restore. Existing `ra` / `ra2` bindings stay visible as "Data Entry Person (legacy)" and survive an edit (D2) |
| `/DeleteUser`, `/UnLockUser` | system admin | Disable, Restore and Unlock row actions | Same effect. The legacy notification e-mail is not sent |
| `/Configure` | system admin | `/admin/password-policy` | Lockout on/off and failed-attempt count beside the password rules (#381) |
| `/SendTestEmail` | system admin | *Send test e-mail* on `/admin/system-status` (`POST /admin/test-email`) | Sends to the administrator's own address and shows the outcome (#381) |
| `/AuditUserActivity`, `/AuditUserActivityData` | system admin | `/admin/login-history` | Server-side filter by user, status and date range, paging, CSV export of the filtered set; replaces the unfiltered 500-row page (#381) |
| `/AuditLogUser` | system admin | `/system/audit-log` (actor filter) | The legacy page reads the heritage `audit_event` table, which is empty after the backfill; the SPA reads `audit_log_event` |
| `/ListStudy`, `/RemoveStudy`, `/RestoreStudy` | system admin | `/admin/studies` | All studies including unbound ones; removal preview and the same cascade as `RemoveStudyServlet`, in one transaction; restore returns the study to the status it had (#377) |
| `/DownloadStudyMetadata` | system admin, and every role that may view study data (study director, coordinator, investigator, `ra`, `ra2`, monitor) | SPA home page, *Study metadata* card (D7); also on `/admin/studies` | Role parity: the card and `GET /studies/{oid}/metadata` use the servlet's gate (`StudyAdminAuthorization.userMayViewStudyDesign`). The card downloads the active study, which is the site's metadata when the site is active. Linked from two open legacy pages, see "Linked from open pages" |
| `/ViewAllJobs`, `/ViewJob`, `/ViewSingleJob` | system admin | `/admin/jobs` | Every Quartz trigger with state and fire times; legacy XSLT export jobs are shown with what they were set to do (#389) |
| `/PauseJob` (pause, resume, delete) | system admin | SPA schedules: pause, resume and edit on `/export` (`PATCH /schedules/{id}`); legacy XSLT jobs: delete on `/admin/jobs` | Accepted difference: a legacy XSLT job can be deleted but not paused or edited in the SPA. Re-create it as a dataset schedule, then delete the old one |
| `/CreateJobExport`, `/UpdateJobExport` | system admin | dataset schedules on `/export` (`POST` and `PATCH` on `/datasets/{id}/schedules` and `/schedules/{id}`) | Accepted differences: SPA schedules have no job name, description or completion e-mail |
| `/pages/listCurrentScheduledJobs`, `/pages/listCurrentScheduledJobsData`, `/pages/cancelScheduledJob` | system admin | `/admin/jobs` (list); cancel of an SPA export job on `/export` | The running-jobs page was recorded as not rebuilt (plan R1.5). Accepted difference: nothing in the SPA interrupts a running legacy XSLT job; deleting it stops later runs and lets the running one finish |
| `/DeleteCRFVersion` | system admin only | *Hard remove* on `/crf-library` | Blocked with a usage report while the version is in use |
| `/ListCRF`, `/CreateCRF`, `/CreateCRFVersion`, `/InitCreateCRFVersion`, `/RemoveCRF`, `/RestoreCRF`, `/InitUpdateCRF`, `/UpdateCRF`, `/RemoveCRFVersion`, `/RestoreCRFVersion`, `/DownloadVersionSpreadSheet`, `/BatchCRFMigration` | system admin, study director, coordinator (`mayProceed`); `InitUpdateCRF`/`UpdateCRF`: system admin, or the owner holding a study director or admin binding | `/crf-library`, `/crf-library/:crfOid/migrate`, `/crf-authoring-canvas/:crfOid` (routes admit Data Manager, CRC, Administrator after D5) | Verified in the SPA and its API, per role. Create, upload a version, remove and restore a CRF or version: the write gate is `userMayManageCrfLibrary` (system admin, study director, coordinator), the legacy set. Edit name and description: `PUT /crfs/{oid}`, the legacy owner rule (a coordinator may not, as before). Version spreadsheet: `GET /crfs/{oid}/versions/{v}/xls`. Batch migration of existing event CRFs: `/crf-library/:crfOid/migrate` over `event-crf-migration`, admitting Data Manager and CRC of the study, as `BatchCRFMigrationController` did (#387). Not carried over by decision (plan R1.5): the blank CRF template download |
| `/AuditLogStudy` | system admin, study director, coordinator | `/audit-log` | The study audit API admits director, coordinator, monitor and system admin (D5 branch), a superset of the legacy set. The monitor, who never had this servlet, also gains the page |

### Held open

| Screen | Why it stays open | What closes it |
|---|---|---|
| `/ViewCRF` | Admits system admin, study director and coordinator, and now every one of them has `/crf-library/:crfOid` (metadata, versions, item table with the integrity check, studies using the CRF). Held back for one feature: its *Run all rules for this CRF* link (`/RunRule?crfId=&action=dryRun`) is not carried over (stated in `CrfDetailView`). `/RunRule` is a study-build servlet that stays open until W3, and is linked only from this page. It is also linked from open data-entry side bars (`eventOverviewSideInfo.jsp`, `submitSideInfo.jsp`) | Either a per-CRF rules run in the SPA or a decision that the per-rule-set dry run on `/rules` is enough; then close with W3 |
| `/ViewStudy` | Linked from the legacy side bar and Tasks menu for every role that may view study data (`include/sidebar.jsp`, `navBar.jsp`, `submitSideInfo.jsp`, `viewStudySubject.jsp` and other pages), and the SPA has no read-only study view for investigators, monitors or data managers | A read view of the study for those roles, or the closure of the pages that link it (W3, W4) |
| `/ListStudyUser`, `/ViewStudyUser`, `/AssignUserToStudy`, `/SetStudyUserRole`, `/RemoveStudyUserRole`, `/RestoreStudyUserRole` | Study-scoped user screens used by the study director and the coordinator; not administration | W3 |
| `/CreateStudy`, `/UpdateStudy`, `/UpdateStudyNew`, `/InitUpdateStudy`, `/CreateSubStudy`, `/ManageStudy` | Study build, not administration; `/UpdateStudyNew` is also used by the study director | W3 |
| `/SystemStatus` | Unauthenticated plain-text probe with no permission check; `/admin/system-status` is the screen, but monitoring may call the probe | Stays open until the probe has a successor, or W5 |

### Linked from open pages

A closed path is refused to a browser request only; a server-side forward to it still works. These open pages link to a path closed in W1:

| Closed path | Linking pages (still open) | Effect |
|---|---|---|
| `/DownloadStudyMetadata` | `managestudy/viewSite.jsp`, `include/viewRuleAssignmentStudySideInfo.jsp` (study director, coordinator) | The link answers 410 and names the SPA home page. W3 retires both pages |
| `/ListStudy` | the exit-confirm script in `includes/global_functions_javascript.js` and `managestudy/createStudy1-4.jsp` (system admin only) | Administrators are redirected (307) to the `/legacy/` alias |
| the user, jobs, study and CRF-version screens | `include/navBar.jsp`, `admin/showCRFRow.jsp` (the CRF list stays open) | Shown to system administrators only, who are redirected (307, method kept) to `/legacy/` |
| `/pages/listCurrentScheduledJobs` | the `SDVUtil` return-view table | Reached by forward only; no browser link |
| the CRF servlets closed after D5 | `managestudy/viewEventDefinition.jsp`, `viewEventDefinitionReadOnly.jsp` (`InitUpdateCRF`), `managestudy/viewCRFVersion.jsp`, `confirmLockingCRFVersion.jsp`, `confirmUnlockingCRFVersion.jsp`, `managestudy/managestudy_body.jsp` (`ListCRF`), `studymodule.jsp` (`CreateCRFVersion`), `admin/viewCRF.jsp` (`ListCRF`, kept open) | Study director and coordinator reach a 410 that names `/app/crf-library` instead of the legacy list; system administrators are redirected to `/legacy/`. W3 retires these pages |
| `/AuditLogStudy` | `managestudy/index.jsp`, `managestudy/managestudy_body.jsp` | 410 naming `/app/audit-log`; retired with W3 |

No SPA view, store or route links or redirects into a path closed here (searched `web/src/spa/src`; the only matches are code comments).

### Left for the maintainer (checklist items 4 to 7)

1. **Announce** to administrators that these pages moved, with the route list above, and that the legacy copies stay reachable for them under `/legacy/<path>` until deletion.
2. **Before closing in production:** list the legacy XSLT jobs on `/admin/jobs`, re-create each one as a dataset schedule on `/export`, then delete it (the SPA cannot pause or edit it), and confirm none is running.
3. **Crawl diff and server-log check** after deployment: every closed path should answer 410 to a non-administrator and 307 to `/legacy/` for an administrator, with no 5xx and no missing-JSP error in the Tomcat log.
4. **Start the six-month clock** when this reaches production, not when it merges.
5. **Review the `legacy-hit` log monthly.** Any hit with `action=gone` by a non-administrator means the parity record is wrong: reopen that path (set `LIBRECLINICA_LEGACY_CLOSED_PATHS` without it) and fix the SPA gap. A hit on `/DownloadStudyMetadata` from a study director or coordinator is the likeliest.
6. **Closing the held-back screens:** D5 is implemented and released the CRF library servlets and `/AuditLogStudy`, which are closed above. `/ViewCRF` and `/ViewStudy` stay open for the reasons in the held-open table. Watch the log for `action=gone` hits on the CRF servlets by study directors and coordinators: they would show a gap in the CRF library the check above missed.
