# Phase E — legacy UI retirement log

The record [DR-018](decision-record.md) point 6 requires. Every retirement of a legacy screen or file is logged here: what went, what replaces it, when it was closed and deleted, and who signed it off. The sequence is in the [JSP retirement plan](jsp-retirement-plan-2026-09-30.md).

**How a screen retires:**

1. **Dead code** (nothing reaches it) is deleted directly; there is no bake-in.
2. **A covered or not-needed screen** is first **closed**. Its URL goes on the closed-paths list (`libreclinica.legacy.closedPaths`). After that, anyone who is not an administrator gets 410 with a pointer to the SPA route, and an administrator can still reach the screen through `/legacy/<path>`. Every hit is logged under the `legacy-access` logger.
3. **Six months after closing**, and only if the log shows no legitimate use, it is **deleted**.

The six-month clock starts when the closure reaches **production**, not when it merges.

---

## Deleted directly (dead code)

| Date | What | Evidence it was dead | PR | Signed off |
|---|---|---|---|---|
| 2026-09-30 | **36 JSPs** unreachable from any servlet, controller or page: `403.jsp`, `404.jsp`, `access_confirmation.jsp`, `admin/auditLogStudyOld.jsp`, `admin/createCRFConfirm.jsp`, `admin/createCRFVersionConfirmSQL.jsp`, `admin/showAuditEventStudyRow.jsp`, `admin/showSubjectRow.jsp`, `admin/systemStatus.jsp`, `admin/uploadCRFVersionFile.jsp`, `admin/viewScheduler.jsp`, `allSdvResult.jsp`, `extract/createDatasetApplyFilter.jsp`, `extract/createFilterConfirm.jsp`, `extract/selectDiscrepancyAttributes.jsp`, `extract/viewEmptyDatasets.jsp`, `include/breadcrumb.jsp`, `include/showEntities.jsp`, `include/showMessages.jsp`, `include/showPanel.jsp`, `include/showPresetValueText.jsp`, `include/showTableWithTabForDNotes.jsp`, `include/showTerms.jsp`, `include/showTextInput.jsp`, `include/userbox.jsp`, `login-include/footer.jsp`, `login-include/login-sidebar.jsp`, `login/contactPop.jsp`, `managestudy/listSubject.jsp`, `managestudy/showSectionWithoutComments.jsp`, `managestudy/showSubjectDiscNote.jsp`, `submit/initialDataEntry.jsp`, `submit/listEventsForSubjects.jsp`, `submit/showSection.jsp`, `viewSubjectAggregateSDV.jsp`, `viewSubjectSDV.jsp`; plus six `Page` constants that pointed only at them | No `Page` forward, no controller view name, and no include or link by path or by bare filename. A before/after crawl of 176 legacy URLs as two roles gave identical status codes, and the server log showed no missing JSP | #363 | pending (Lukas) |
| 2026-09-30 | `/pages/user` (`UserController`, `user.jsp`), the upstream Spring MVC demo page with four hard-coded names | nothing links to it; no feature | #369 | pending (Lukas) |

## Closed (in bake-in)

| Wave | Closed in code | Reached production | Paths | SPA replacement | Delete not before | Signed off |
|---|---|---|---|---|---|---|
| W0 — not needed | 2026-09-30 (#371) | pending | `/Enterprise`, `/TechAdmin`, `/AdminSystem`, `/AuditDatabase`, `/ListSubject`, `/ListSubjectData`, `/ViewSubject`, `/UpdateSubject`, `/RemoveSubject`, `/RestoreSubject`, `/CreateJobImport`, `/UpdateJobImport`, `/ViewImportJob`, `/ViewLogMessage`, `/PrintoutCertificate`, `/DeleteEventCRF`, `/ConfigurePasswordRequirements` | none needed: SPA home; patient model (`/patients`) and the SPA subject page; imports and two-factor dropped (2026-09-30); `/admin/password-policy` | six months after production | pending (Lukas) |
| W0 — heritage REST API (bucket HERITAGE_API) | 2026-09-30 (#371) | pending | `/pages/auth` (seven OpenClinica 3.x controllers; API-key only, no caller in the app, the SPA or the deployment; reversible through `LIBRECLINICA_LEGACY_CLOSED_PATHS`) | none needed | six months after production | pending (Lukas) |
| W1 — administration (prepared in branch `chore/muw-retire-w1-admin`) | on merge | pending | 43 paths, per screen in the [W1 parity record](#w1-parity-record): `/ListUserAccounts`, `/CreateUserAccount`, `/ViewUserAccount`, `/EditUserAccount`, `/SetUserRole`, `/EditStudyUserRole`, `/DeleteStudyUserRole`, `/DeleteUser`, `/UnLockUser`, `/pages/admin/listLdapUsers`, `/pages/admin/selectLdapUser`, `/Configure`, `/SendTestEmail`, `/AuditUserActivity`, `/AuditUserActivityData`, `/AuditLogUser`, `/ListStudy`, `/RemoveStudy`, `/RestoreStudy`, `/DownloadStudyMetadata`, `/ViewAllJobs`, `/ViewJob`, `/ViewSingleJob`, `/PauseJob`, `/CreateJobExport`, `/UpdateJobExport`, `/pages/listCurrentScheduledJobs`, `/pages/listCurrentScheduledJobsData`, `/pages/cancelScheduledJob`, `/DeleteCRFVersion`, `/ListCRF`, `/CreateCRF`, `/CreateCRFVersion`, `/InitCreateCRFVersion`, `/RemoveCRF`, `/RestoreCRF`, `/InitUpdateCRF`, `/UpdateCRF`, `/RemoveCRFVersion`, `/RestoreCRFVersion`, `/DownloadVersionSpreadSheet`, `/BatchCRFMigration`, `/AuditLogStudy`. Held open: `/ViewCRF`, `/ViewStudy` | `/manage-users`, `/admin/password-policy`, `/admin/system-status`, `/admin/login-history`, `/system/audit-log`, `/admin/studies`, SPA home (metadata card), `/admin/jobs`, `/export`, `/crf-library`; LDAP pickers: none needed | six months after production | pending (Lukas) |

## Deleted after bake-in

| Wave | Deleted | Paths | Log review (hits by non-admins during bake-in) | PR | Signed off |
|---|---|---|---|---|---|
| — | | | | | |

---

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
