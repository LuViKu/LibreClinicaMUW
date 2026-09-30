# Phase E — Administrator role UI feature catalogue

**Source:** a live walkthrough of the local dev stack (`http://127.0.0.1:8081`, build `1.5.0-beta.15-muw` of 2026-09-30 08:25 UTC) as `manual_admin`, a *business administrator* with no study binding, on 2026-09-30. The walk was read-only: every screen was fetched with GET and its HTML read, no form was submitted and no action link was followed. It was cross-referenced with:

- [LegacyServletRegistry.java](../../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/LegacyServletRegistry.java), which has held every legacy URL mapping since Phase C.16;
- the servlet and JSP source under `control/admin/`, `control/techadmin/` and `jsp/admin/`, plus the Spring MVC controllers that render admin pages;
- the SPA [router](../../../../web/src/spa/src/router/index.ts), views, stores and `/pages/api/v1/**` controllers (the admin endpoints were also read live with GET);
- the [administrator manual](../../../manuals/administrator-manual.md);
- the 2026-09-30 static coverage survey summarised in [DR-018](../decision-record.md#dr-018--the-legacy-jsp-layer-is-retired-in-full-admin-screens-included).

**Purpose:** this is the administrator counterpart of [investigator-features.md](investigator-features.md), [monitor-features.md](monitor-features.md) and [data-manager-features.md](data-manager-features.md). Those three left admin out because [DR-004](../decision-record.md#dr-004--clinical-use-deferred-until-modernization-completes) kept admin screens on JSP. DR-018 reverses that decision:

- under its point 5, no admin screen can be retired until this catalogue exists;
- under its point 4, a screen is deleted only once its SPA replacement is recorded here for every role that uses it.

**New in this catalogue: an *SPA coverage* column on every feature table.** It names the SPA route and view that replace each feature (or `—`) and gives a verdict. The other three catalogues were written before the SPA existed and record legacy features only. DR-018 retires screens against exactly this column, so it is the part to keep current. Where a verdict is PARTIAL, the notes under the table say which fields or actions are missing.

> **Administrator** here means a *system* administrator: `user_account.user_type_id` 1 (*business administrator*) or 3 (*technical administrator*).
>
> - `UserAccountBean.isSysAdmin()` is true for both; a technical administrator also has `isTechAdmin()`.
> - This is not the study-level role `admin` that a user can hold in one study. The SPA labels both "Administrator": `RoleMapper.toSpaRole` maps the study role to it, and `UsersApiController` projects sysadmin and techadmin users to it.
> - Study-scoped work that an administrator also does is catalogued in [data-manager-features.md](data-manager-features.md). §13 lists those screens with their SPA routes, so DR-018 can record the admin role against them too.

> **Verdicts**
>
> - **COVERED**: an SPA route does the same job, checked in the view and in its API.
> - **PARTIAL**: an SPA route does part of the job; the notes say what is missing.
> - **NOT COVERED**: no SPA route does the job. This includes features the backend offers but no view calls ("API only").
>
> Where the evidence was thin, the verdict is NOT COVERED rather than COVERED.

---

## 1. Authentication & the administrator identity

Login, logout, forced password change and profile editing are the same for every role; see [investigator-features.md §1](investigator-features.md#1-authentication--profile). The administrator-specific points:

- **`manual_admin` has no usable study role.** Its only `study_user_role` row has a NULL `study_id`. The consequences:
  - The legacy header shows the account's `active_study` (Default Study) with the role **"(invalid)"**.
  - Legacy pages that check the study role refuse the account. `/pages/studymodule`, for example, redirects to `MainMenu?message=authentication_failed`.
  - The SPA's `GET /pages/api/v1/me` reports the role `Administrator` on Default Study.
  - `GET /pages/api/v1/studies` returns `[]`, because that list is built from the caller's own study bindings (§4).
- **The two administrator user types collapse into one SPA role.** A technical administrator can do everything a business administrator can (`UserAccountBean.addUserType`). Only a technical administrator can open `/TechAdmin` or create another technical administrator.
- **An expired session answers with HTTP 500.** Sessions are single per user, so a second login as the same account expires the first. During this walk that happened three times. Each time, the expired session answered **HTTP 500** instead of redirecting to login: a `NullPointerException` in Spring Security's `ConcurrentSessionFilter`, whose `redirectStrategy` is null.

---

## 2. Top navigation (Administrator)

Captured live for `manual_admin`:

| Position | Label | URL | Backing servlet | SPA coverage |
|---|---|---|---|---|
| Header-left | Study "Default Study" | `/ViewStudy?id=1&viewFull=yes` | `control.admin.ViewStudyServlet` | see §4 |
| Header-left | Change Study/Site | `/ChangeStudy` | `control.login.ChangeStudyServlet` | `/pick-study` → `StudyPickerView.vue` |
| Header-right | `manual_admin (invalid) en` | `/UpdateProfile` | `control.login.UpdateProfileServlet` | see [investigator-features.md §1.3](investigator-features.md#13-update-profile--change-password) |
| Header-right | Log Out | `/j_spring_security_logout` | Spring Security | `TopBar.vue` sign-out |
| Top nav | Tasks ▾ | — | `include/navBar.jsp` | `TopBar.vue` |
| Top nav | Study Subject ID search | `/ListStudySubjects` (GET) | `control.submit.ListStudySubjectsServlet` | `/subjects` |

Without a study role, the administrator's top bar has no Home, Subject Matrix or Notes buttons; it shows only Tasks and the search box.

### Tasks dropdown (Administrator)

Captured live:

- **Administration** — Studies · Users · CRFs · Jobs · Subjects
- **Other** — Update Profile · Log Out

What `navBar.jsp` renders beyond that:

- The Administration group appears when `userBean.sysAdmin || userBean.techAdmin`.
- An administrator who also holds a study role gets that role's groups as well: Submit Data, Monitor and Manage Data, Extract Data and Study Setup (see the other three catalogues).
- A **Manual Download** group (administrator, investigator and monitor manual PDFs) appears when `display.manual=true`. It is `false` on this stack and in the default `datainfo.properties`.

| Tasks entry | URL | Backing servlet | SPA coverage |
|---|---|---|---|
| Studies | `/ListStudy` | `control.managestudy.ListStudyServlet` | see §4 |
| Users | `/ListUserAccounts` | `control.admin.ListUserAccountsServlet` | `/manage-users` — see §5 |
| CRFs | `/ListCRF?module=admin` | `control.admin.ListCRFServlet` | `/crf-library` — see §8 |
| Jobs | `/ViewAllJobs` | `control.admin.ViewAllJobsServlet` | `/admin/jobs` — see §9 |
| Subjects | `/ListSubject` | `control.admin.ListSubjectServlet` | — see §10 |

Five live admin pages are in **no menu**: `/AdminSystem`, `/TechAdmin`, `/AuditDatabase`, `/AuditLogUser` and `/Enterprise`. They are reached only by typing the URL or as the target of a redirect.

### SPA navigation for the Administrator

- **Top bar** (`TopBar.vue`, `lib/primaryNav.ts`): Home · Manage Users · Sites · Data Export · Audit Log, plus **System** (Administrator only), which opens `/admin/system-status`.
- **System rail** (`SystemRail.vue`): System status · System audit trail · Password policy · App configuration · Scheduled jobs.
- **Home cards** (`HomeView.vue`, Administrator): Sites · Edit study · Manage users · Create study · Modalities · Patients overview · Data export · Audit log · Switch study.
- **Build Study rail** (`BuildStudyRail.vue`): shown on the study-setup pages listed in §13.

The navigation rows above are not counted in §15; the features they lead to are.

---

## 3. Administration landing pages

| Label | URL | Backing servlet | SPA coverage |
|---|---|---|---|
| Administration home ("Business Administrator") | `/AdminSystem` | `control.admin.AdminSystemServlet` → `admin/index.jsp` | **PARTIAL** — `/` → `HomeView.vue` + System rail |
| Technical Administrator home | `/TechAdmin` | `control.techadmin.TechAdminServlet` → `techadmin/index.jsp` | **NOT COVERED** — — |

**`/AdminSystem`** (live; gate `isSysAdmin()`; not linked from the menu)

- A sidebar counts what is in the application: Subjects 10 · Users 10 · Studies 4 · CRFs 3.
- Four *Recent Activity* boxes each list the five most recently updated entities, with *Show All* and *Add New*:
  - Subjects: Person ID, Date Updated, Status → `/ListSubject`
  - Users → `/ListUserAccounts`, `/CreateUserAccount`
  - Studies → `/ListStudy`, `/CreateStudy`
  - CRFs → `/ListCRF`, `/CreateCRFVersion?module=admin`
- *Missing in the SPA:* the counts and the four recent-activity lists. The entry points exist as Home cards, except Subjects, whose target has no SPA equivalent (§10).

**`/TechAdmin`** (gate `isTechAdmin()`)

- `manual_admin` is a business administrator, so the request fell through to the main menu. The page is described from source.
- It holds a heading, one sentence ("As a Technical Administrator you have privileges to …") and the same four counts. It links nowhere.
- Retiring it loses only the counts.

---

## 4. Studies (Tasks → Administration → Studies)

| Label | URL | Backing servlet | SPA coverage |
|---|---|---|---|
| Administer Studies (all studies and sites) | `/ListStudy` | `control.managestudy.ListStudyServlet` → `managestudy/studyList.jsp` | **NOT COVERED** — `/pick-study` lists the caller's own bindings only |
| Create a New Study | `/CreateStudy` | `control.managestudy.CreateStudyServlet` | **PARTIAL** — `/studies/new` → `CreateStudyView.vue` |
| View Study (full record) | `/ViewStudy?id=<n>&viewFull=yes` | `control.admin.ViewStudyServlet` → `admin/viewFullStudy.jsp` | **PARTIAL** — `/studies/:oid/edit`, `/studies/:oid/parameters`, `/build-study` |
| Update Study (one page, collapsible sections) | `/UpdateStudyNew?id=<n>` (also `/UpdateStudy`, `/InitUpdateStudy`) | `control.managestudy.UpdateStudyServletNew` (+ `UpdateStudyServlet`, `InitUpdateStudyServlet`) | **PARTIAL** — `/studies/:oid/edit` → `StudyIdentityEditView.vue` + `/studies/:oid/parameters` → `StudyParametersEditView.vue` |
| Set study status (Available / Pending / Frozen / Locked) | `statusId` on `/UpdateStudyNew`; Build Study *Set Study Status* | `UpdateStudyServletNew`; `controller.StudyModuleController` | **COVERED** — `/build-study` → `BuildStudyView.vue` |
| Remove Study (cascades to sites, roles, subjects, events, CRF data) | `/RemoveStudy?action=confirm&id=<n>` | `control.admin.RemoveStudyServlet` | **NOT COVERED** — API only (`POST /studies/{oid}/disable`) |
| Restore Study | `/RestoreStudy?action=confirm&id=<n>` | `control.admin.RestoreStudyServlet` | **NOT COVERED** — API only (`POST /studies/{oid}/restore`) |
| Download study metadata (ODM) | `/DownloadStudyMetadata?studyId=<n>` | `control.admin.DownloadStudyMetadataServlet` | **NOT COVERED** — — |

### `/ListStudy`

Live; gate `isSysAdmin()`.

- H1 "Administer Studies", with a keyword filter and a *Create a New Study* link.
- Columns: Name · Unique Identifier · OID · Principal Investigator · Facility Name · Date Created · Status · Actions. Actions are View and Remove, or Restore on removed rows. Sites are listed under their parent.
- Sidebar: Studies 4, Sites 0.

The SPA has no list of all studies. `StudiesApiController.list` returns only the caller's own `study_user_role` bindings. For `manual_admin` it returned `[]`, so `/pick-study` would be empty.

### `/CreateStudy`

Live. The form is one page in practice:

- `createStudy1.jsp` renders only *Save*, which creates the study as **Pending** from the page-1 fields.
- `createStudy2..8.jsp` are reached only through `action=next` with `pageNum` ≥ 2, and no rendered button sends that. This was read from `CreateStudyServlet` and not exercised, because trying it would create a study.

Legacy fields:

- Unique Protocol ID*, Brief Title*, Official Title, Secondary IDs, Principal Investigator*
- Protocol Type* (interventional / observational)
- Login E-Mail Notification* (disabled / enabled) and Project Manager Contact E-mail (required when notification is enabled)
- Brief Summary*, Detailed Description, Sponsor*, Collaborators
- **Select User** — offers users holding a coordinator or director role (live: datamanager, manual_crc, manual_dm, root). The chosen user gets a coordinator binding on the new study, and so does the creator.

`CreateStudyView.vue` binds unique protocol ID, brief title, official title, secondary IDs, principal investigator, protocol type, brief summary, sponsor and phase. *Missing in the SPA:*

- **Login E-Mail Notification** — not in `CreateStudyRequest` at all.
- **Contact E-mail**, **Collaborators** and **Detailed Description** — in `CreateStudyRequest`, but not bound in the view.
- **Select User** — the SPA binds only the caller as coordinator.

The SPA adds **Phase**, which the legacy create page lacks.

### `/ViewStudy`

This page returns **HTTP 500 for every study on this build**, so it is described from `viewFullStudy.jsp`. It shows:

- Overview.
- Sections for description, status and dates, conditions and eligibility, facility, related information, and parameter configuration.
- Sites (→ `/ViewSite`), Event Definitions (→ `/ViewEventDefinitionReadOnly`) and Users (→ `/ViewStudyUser`).
- A *Download study metadata* link.

Cause of the 500: `ViewStudyServlet.processRequest` calls `ParticipantPortalRegistrar.getCachedRegistrationStatus` unconditionally. That builds a Spring `HttpComponentsClientHttpRequestFactory`, which needs Apache HttpClient 5, and no `httpclient5` jar is in the WAR (`NoClassDefFoundError: org/apache/hc/client5/http/classic/HttpClient`). This page is the study-name link in the header of every legacy page.

*Missing in the SPA:*

- a read view of the dates, eligibility, facility and related-information sections;
- the inline sites, event-definition and users lists (each has its own SPA route);
- the metadata download.

### `/UpdateStudyNew?id=1`

Live; the form was not submitted. The servlet only edits the session's current study. The form has 60 inputs:

| Section | Legacy fields | SPA |
|---|---|---|
| Study description | uniqueProId, name, officialTitle, secondProId, prinInvestigator, description (brief summary), protocolDescription, sponsor, collaborators, phase, protocolType, mailNotification, contactEmail, statusId | `StudyIdentityEditView.vue` binds name, briefSummary, principalInvestigator, sponsor, officialTitle, secondaryProtocolId, protocolType, phase. **Missing:** uniqueProId (create-only in the SPA); protocolDescription, collaborators, contactEmail (in `UpdateStudyRequest`, not bound in the view); mailNotification (not in the API). statusId is handled by Build Study. |
| Status, dates and design | protocolDateVerification, startDate, endDate; observational: purpose, duration, selection, timing; interventional (from the JSP; Default Study is observational): purpose, allocation, masking, control, assignment, endpoints, interventions | **None.** `StudyIdentityDto` reads `datePlannedStart` only. |
| Conditions and eligibility | conditions, keywords, eligibility, gender, ageMin, ageMax, healthyVolunteerAccepted, expectedTotalEnrollment | **None.** |
| Facility information | facName, facCity, facState, facZip, facCountry, facConName, facConDegree, facConPhone, facConEmail | **None.** |
| Related information | medlineIdentifier, resultsReference, url, urlDescription | **None.** |
| Study parameter configuration | collectDob, discrepancyManagement, genderRequired, subjectPersonIdRequired, personIdShownOnCRF, subjectIdGeneration, interviewerNameRequired / Default / Editable, interviewDateRequired / Default / Editable, secondaryLabelViewable, adminForcedReasonForChange, eventLocationRequired | **All covered** by `StudyParametersEditView.vue`, which also adds subjectIdPrefixSuffix, participantPortal and randomization. |

### Set study status

`StudiesApiController.setStatus` allows these transitions, and each cascades to the study's sites:

- PENDING → AVAILABLE
- AVAILABLE → LOCKED, FROZEN or PENDING
- LOCKED → AVAILABLE or FROZEN
- FROZEN → AVAILABLE or LOCKED

AVAILABLE → LOCKED and AVAILABLE → FROZEN require a reason.

### `/RemoveStudy`

Live for `id=104`, confirmation page only.

- The page shows name, brief summary, sites, users and roles, subjects and event definitions, then *Remove Study* (a POST with `action=submit`).
- Submitting marks the study Removed and auto-removes its sites, role bindings, subjects, events, event CRFs and item data.
- `StudiesApiController` has `POST /{oid}/disable` and `/restore`, but no view calls them: `stores/study.ts` wraps only `/status`.
- Whether `/disable` cascades the same way was not checked.

### `/RestoreStudy` and `/DownloadStudyMetadata`

Neither was fetched: no study on the stack is removed, and the metadata link is a file download. Both are described from source.

---

## 5. Users & accounts (Tasks → Administration → Users)

| Label | URL | Backing servlet | SPA coverage |
|---|---|---|---|
| Administer Users (all accounts, every study role inline) | `/ListUserAccounts` | `control.admin.ListUserAccountsServlet` | **COVERED** — `/manage-users` → `ManageUsersView.vue` |
| Create a User Account | `/CreateUserAccount` | `control.admin.CreateUserAccountServlet` | **PARTIAL** — `InviteUserDialog.vue` on `/manage-users` |
| View User Account | `/ViewUserAccount?userId=<n>` | `control.admin.ViewUserAccountServlet` | **PARTIAL** — `/manage-users` row + `UserRolesDialog.vue` |
| Edit a User Account | `/EditUserAccount?userId=<n>` | `control.admin.EditUserAccountServlet` | **PARTIAL** — `EditUserDialog.vue` |
| Reset password (checkbox on Edit; e-mail it or show it) | `/EditUserAccount` (`resetPassword`, `displayPwd`) | `EditUserAccountServlet` | **PARTIAL** — *Reset password* row action |
| Set User Role (grant a study role) | `/SetUserRole?action=confirm&userId=<n>` | `control.admin.SetUserRoleServlet` | **PARTIAL** — `UserRolesDialog.vue` (*Add study*) |
| Modify a role within a study | `/EditStudyUserRole?studyId=<n>&userName=<u>` | `control.admin.EditStudyUserRoleServlet` | **PARTIAL** — `UserRolesDialog.vue` |
| Remove / restore a study role | `/DeleteStudyUserRole?…&action=3` / `&action=4` | `control.admin.DeleteStudyUserRoleServlet` | **COVERED** — `UserRolesDialog.vue` (`DELETE /users/{u}/roles/{studyOid}`; restore is a new grant) |
| Remove user | `/DeleteUser?action=3&userId=<n>` | `control.admin.DeleteUserServlet` | **COVERED** — *Disable* row action |
| Restore user | `/DeleteUser?action=4&userId=<n>` | `DeleteUserServlet` | **COVERED** — *Restore* row action (shows a one-time password) |
| Unlock user | `/UnLockUser?userId=<n>` | `control.admin.UnLockUserServlet` | **COVERED** — *Unlock* row action |
| Two-factor (LETTER) certificate printout | `/PrintoutCertificate?userId=<n>` | `control.admin.PrintoutCertificateServlet` | **NOT COVERED** — — |

### Study-role labels differ between the two UIs

The legacy UI and the SPA use the same words for different roles, so a retirement check that matches on labels will mis-map them:

| `study_user_role.role_name` | Legacy admin label on this build | SPA label |
|---|---|---|
| `coordinator` (2) | Data Manager | CRC |
| `director` (3) | Study Director | Data Manager |
| `Investigator` (4) | Data Specialist | Investigator |
| `ra` (5) | Data Entry Person | shown as Investigator; **cannot be granted** |
| `monitor` (6) | Monitor | Monitor |
| `ra2` (7) | site-level Data Entry Person 2 | shown as Investigator; **cannot be granted** |
| `admin` (1) | not offered | Administrator |

`RoleMapper.fromSpaRole` documents the `ra`/`ra2` gap itself: "The RA / RA2 roles can only be granted via the legacy JSP admin surface".

### User list

Live.

- H1 "Administer Users".
- Side links: Create New User · Audit User Activity (`/AuditUserActivity?restore=true`) · Lockout Configuration (`/Configure`) · Configure Password Requirements · Send Test Email (AJAX, §6).
- Columns: User Name · First Name · Last Name · Status · Authentication Type · Actions.
- Per-user actions:
  - View, Edit, Set Role and Remove.
  - Restore when the user is removed, Unlock when locked.
  - Print when two-factor LETTER is active and the user is marked for it.
- Per-study-role actions: Edit and Remove, or Restore when the role is removed.
- A technical administrator's row shows no actions to a business administrator.

The SPA gives a sysadmin the global list (`UsersApiController.list`, `globalList`). It has search, role and authentication filters, an active-only toggle, and per-row Edit, Roles, Disable, Restore, Reset password and Unlock.

### Create user

Live. Legacy fields:

- User Name*, First Name*, Last Name*, Email*, Institutional Affiliation*
- Active Study* (any study) and Role* (the five legacy labels above)
- User Type: user or business administrator. Technical administrator is offered only when the creator is a technical administrator.
- Authorize SOAP web services
- Password delivery: e-mail it, or show it to the admin
- When `ldap.enabled`: a local/LDAP user source and an LDAP lookup (`pages/admin/listLdapUsers`)
- When `2fa.activated`: Authentication Type (Standard / Marked for 2FA / 2FA)

`InviteUserDialog.vue` collects username, first and last name, e-mail, affiliation, phone, role, and local or SSO with an eppn. *Missing in the SPA:*

- **User type.** The SPA cannot create an administrator: the API accepts `userType`, but the dialog never sends it.
- **Study choice.** The new user is always bound to the administrator's own active study.
- **The Data Entry Person roles** (`ra`/`ra2`).
- **The SOAP flag** and **authentication type**.
- **E-mail delivery of the password.** The dialog always sends `sendEmail:false` and shows a one-time password, because `UsersApiController` has no mail path wired.
- **LDAP as a source.** The API refuses `userSource=ldap`.

The SPA adds phone and SSO pre-binding, which the legacy form lacks.

### View user

Live. The legacy page shows first and last name, e-mail, phone, institutional affiliation, business administrator yes/no, technical administrator yes/no, status, date created, owner, date updated, updated by, SOAP authorisation and roles.

*Missing in the SPA:* affiliation, phone, both administrator flags, the created/owner/updated metadata and the SOAP flag are shown nowhere. `StudyUserDto` carries only id, username, displayName, email, role, siteLabel, auth, lastLoginAt, active and locked. Roles are shown in `UserRolesDialog.vue`.

### Edit user

Live. Legacy fields: First Name*, Last Name*, Email*, Institutional Affiliation*, User Type (user / business administrator), SOAP, reset password with its delivery choice, and Authentication Type when two-factor is active. Phone is shown on View but cannot be edited here; the SPA adds phone editing.

*Missing in the SPA:*

- **User type**, so an administrator cannot be promoted or demoted. `UpdateUserRequest.userType` exists, but the dialog does not send it.
- The SOAP flag and the authentication type.

`EditUserDialog.vue` has two further problems:

- It does not pre-fill phone or affiliation; it only splits `displayName` into first and last name.
- It omits blank fields from the PUT, so it cannot clear them.

### Reset password

Legacy e-mails the new password or shows it. The SPA always shows a one-time password.

### Set User Role

Live for `manual_dm`. The study list offers every study the user is not yet bound to (three here), with the same role list as Create.

*Missing in the SPA:*

- The *Add study* picker is `auth.availableStudies`, which holds only studies the **administrator** is bound to. For `manual_admin` that list is empty.
- The `ra`/`ra2` roles.

### Modify role

Live: one select with the same five roles. *Missing in the SPA:* `ra`/`ra2`.

### Actions that were not followed

Remove/restore user, unlock, and remove/restore study role were not followed. Their links perform the action on a plain GET, with a JavaScript `confirm()` as the only guard (§16.2), so they are described from source. Legacy Remove, Restore and Unlock also e-mail the user; the SPA sends no e-mail.

### PrintoutCertificate

Needs `2fa.activated=true` with the LETTER type. Two-factor is off on this stack, so it was not exercised.

---

## 6. Security configuration & mail

| Label | URL | Backing servlet | SPA coverage |
|---|---|---|---|
| Lockout Configuration (on/off, failed attempts before lock) | `/Configure` | `control.admin.ConfigureServlet` → `admin/configuration.jsp` | **NOT COVERED** — — |
| Configure Password Requirements | `/ConfigurePasswordRequirements` | `control.admin.ConfigurePasswordRequirementsServlet` | **COVERED** — `/admin/password-policy` → `AdminPasswordPolicyView.vue` |
| Send Test Email (to the administrator's own address) | `/SendTestEmail` (AJAX, from the users list) | `control.admin.SendTestEmailServlet` | **NOT COVERED** — — |

### `/Configure`

Live. The page is titled "Lockout Configuration" and has two fields:

- *Lockout Enabled*: TRUE/FALSE, stored as `user.lock.switch`.
- *# of Failed Attempts*: live value 3, stored as `user.lock.allowedFailedConsecutiveLoginAttempts`.

It is **not** an application-configuration screen. `/admin/config` (`AdminConfigView.vue`) is a read-only mirror of timezone, locale, encoding, OS, SSO and the retinal push URL. Neither it nor `/admin/password-policy` shows the lockout settings. Earlier notes paired `/Configure` with `/admin/config`; see §16.3.

### `/ConfigurePasswordRequirements`

**Broken on this build.** Its JSP imports `javax.servlet.http.HttpServletRequest`, which does not compile on Tomcat 10. Tomcat's `localhost.<date>.log` records "Only a type can be imported. javax.servlet.http.HttpServletRequest resolves to a package", and the page returns HTTP 200 with an empty body.

The SPA form carries the same eight settings and is the only working surface:

- lower case, upper case, digits, specials;
- minimum and maximum length;
- expiry days;
- change required on first login.

### `/SendTestEmail`

A GET sends a mail, so it was not called. The servlet returns `{type, message, recipient}` JSON, which the page shows in an `alert()`. There is no SPA or API equivalent.

---

## 7. System status & audit

| Label | URL | Backing servlet | SPA coverage |
|---|---|---|---|
| System status probe | `/SystemStatus` | `control.admin.SystemStatusServlet` | **COVERED** — `/admin/system-status` → `AdminSystemStatusView.vue` |
| Audit User Activity (login, logout and failed-login history) | `/AuditUserActivity` (+ `/AuditUserActivityData` JSON) | `control.admin.AuditUserActivityServlet`, `AuditUserActivityDataServlet` | **NOT COVERED** — — |
| Audit log for one user | `/AuditLogUser?userLogId=<n>` | `control.admin.AuditLogUserServlet` | **COVERED** — `/system/audit-log` → `SystemAuditLogView.vue` (actor filter) |
| Audit Database (schema change log) | `/AuditDatabase` | `control.admin.AuditDatabaseServlet` | **NOT COVERED** — — |
| View Study Log (every subject of the current study) | `/AuditLogStudy` | `control.admin.AuditLogStudyServlet` | **COVERED** — `/audit-log` → `StudyAuditLogView.vue` |

### `/SystemStatus`

It has no screen:

- The servlet writes plain text: `OK` and the Liquibase changelog count (`1202` live).
- It checks no permission beyond being logged in.
- Its JSP is unreachable.

The SPA page reports JVM, database and application status and the retinal cluster, and its changelog count matches.

### `/AuditUserActivity`

Live.

- Columns: User Name · Login Attempt Date · Status (Successful Login / Successful Logout / Failed Login …) · Details · View (→ `/ViewUserAccount`). The date header renders as `???attempt_date???`, a missing i18n key.
- It showed 43 rows. Since jmesa was evicted, `/AuditUserActivityData` loads a fixed 500-row page with no filter.
- It reads the `audit_user_login` table.

**Nothing in the SPA or in `/pages/api/v1/**` reads `audit_user_login`**; `/system/audit-log` reads `audit_log_event` only. This page is the only UI for login history.

### `/AuditLogUser`

Linked from nowhere: the link on View User is commented out.

- It reads the heritage `audit_event` table, which has **0 rows** on this stack. Its rows were backfilled into `audit_log_event` by `lc-muw-2026-06-12-audit-event-backfill.xml`.
- As a result, for `root`, which has 88 rows in `audit_log_event`, it shows "There are no rows to display".

The SPA's actor filter does this job, with one limit: `/audit/system` reads the **newest 500** `audit_log_event` rows and filters after that limit, so older events for a given user cannot be reached.

### `/AuditDatabase`

Live, 780 KB. It lists the Liquibase `databasechangelog`: every changeset, with Id, Author, File Name, Date Executed, md5 sum, Description, Comments, Tag and Liquibase version. It is not linked from any menu. The SPA shows only the changelog count.

### `/AuditLogStudy`

Live. For each subject of the current study it shows IDs, date of birth, person ID, creator, status, recent activity, events, and a link to `/ViewStudySubjectAuditLog`.

- Gate: sysadmin, or study director/coordinator.
- SPA `/audit-log` covers the same data for the active study. The Monitor and Data Manager catalogues also record it.

---

## 8. CRF library (Tasks → Administration → CRFs)

These are the servlets behind the Data Manager's *Manage CRFs* ([data-manager-features.md §6](data-manager-features.md#6-crf-management-data-manager-only)), entered with `module=admin`. `showCRFRow.jsp` renders the permanent version *Delete* only when `userBean.sysAdmin` is true, in either module.

| Label | URL | Backing servlet | SPA coverage |
|---|---|---|---|
| Administer CRFs (list with nested versions) | `/ListCRF?module=admin` | `control.admin.ListCRFServlet` | **COVERED** — `/crf-library` → `CrfLibraryView.vue` |
| Create CRF (name, description) | `/CreateCRF` | `control.admin.CreateCRFServlet` | **COVERED** — *Create* on `/crf-library` |
| Create a new CRF from a spreadsheet | `/CreateCRFVersion?module=admin` (crfId 0) | `control.admin.CreateCRFVersionServlet` | **COVERED** — create, then upload on `/crf-library`; or `/crf-authoring-canvas/:crfOid` → `CrfAuthoringCanvasView.vue` |
| Add a version (upload, preview, confirm) | `/InitCreateCRFVersion?crfId=<n>` → `/CreateCRFVersion` | `control.admin.InitCreateCRFVersionServlet`, `CreateCRFVersionServlet` | **COVERED** — upload on `/crf-library`; canvas builder |
| View CRF (metadata, versions, items, studies using it, rules) | `/ViewCRF?crfId=<n>` | `control.admin.ViewCRFServlet` | **PARTIAL** — `/crf-library` |
| Edit CRF name / description | `/InitUpdateCRF?crfId=<n>` → `/UpdateCRF` | `control.admin.InitUpdateCRFServlet`, `UpdateCRFServlet` | **NOT COVERED** — — |
| Remove CRF | `/RemoveCRF?action=confirm&id=<n>` | `control.admin.RemoveCRFServlet` | **COVERED** — *Disable* on `/crf-library` |
| Restore CRF | `/RestoreCRF?action=confirm&id=<n>` | `control.admin.RestoreCRFServlet` | **NOT COVERED** — — |
| View a version (rendered form) | `/ViewSectionDataEntry?crfVersionId=<n>…`, `/ViewCRFVersion?id=<n>` | `control.managestudy.ViewSectionDataEntryServlet`, `ViewCRFVersionServlet` | **PARTIAL** — only by forking the version into the canvas builder (preview pane) |
| Archive (lock) / unlock a version | `/LockCRFVersion?id=<n>`, `/UnlockCRFVersion?id=<n>` | `control.managestudy.LockCRFVersionServlet`, `UnlockCRFVersionServlet` | **COVERED** — *Lock* / *Unlock* on `/crf-library` |
| Remove / restore a version | `/RemoveCRFVersion`, `/RestoreCRFVersion` | `control.admin.RemoveCRFVersionServlet`, `RestoreCRFVersionServlet` | **COVERED** — `/crf-library` |
| Delete a version permanently | `/DeleteCRFVersion?action=confirm&verId=<n>` | `control.admin.DeleteCRFVersionServlet` (sysadmin only) | **COVERED** — *Hard remove*, blocked with a usage report while in use |
| Download a version's spreadsheet | `/DownloadVersionSpreadSheet?crfVersionId=<n>` | `control.admin.DownloadVersionSpreadSheetServlet` | **COVERED** — *.xls* (`GET /crfs/{oid}/versions/{v}/xls`) |
| Download the blank CRF template | `/DownloadVersionSpreadSheet?template=1` | `DownloadVersionSpreadSheetServlet` | **NOT COVERED** — — |
| Batch CRF migration (move subjects' existing event CRFs to another version) | `/BatchCRFMigration?crfId=<n>` + `/pages/api/v1/forms/migrate/{preview,run}` | `control.admin.BatchCRFMigrationServlet`, `controller.BatchCRFMigrationController` | **NOT COVERED** — see the note below |
| Create a version from an XForm | `/CreateXformCRFVersion` | `control.admin.CreateXformCRFVersionServlet` | **NOT COVERED** — — |

### View CRF

Live, for Demographics. The page shows:

- name, description and OID;
- versions, each with name, OID, description, status and revision notes, plus view, print and metadata links;
- items, with name, OID, description, data type, versions and integrity check;
- *Studies Using This CRF* (→ `/ViewStudy`);
- *Run All Rules for this CRF* (`/RunRule?crfId=<n>&action=dryRun`) and *View Rules for this CRF*.

*Missing in the SPA:*

- the item table;
- the list of studies using the CRF, which the SPA shows only inside the hard-remove blocker (`VersionUsageReport`);
- running all rules for one CRF.

The legacy *print* link calls `rest/metadata/html/print/…`, which belongs to the unregistered Jersey tree, so it is dead.

### Edit and restore CRF

The live edit form has Name* and Description. `CrfsApiController` has no PUT for a CRF. It also has no `/{crfOid}/restore`: only `/{crfOid}/disable`, plus restore at version level.

### Batch CRF migration

The legacy screen and the SPA's *Migrate* are different operations, and neither covers the other:

- **Legacy** (`BatchCRFMigrationController`) moves existing `event_crf` rows to the target version, across the chosen sites and event definitions. It clears their SDV flag and unsigns signed subjects and events. It offers a preview and a downloadable log.
- **SPA** *Migrate* (`POST /crfs/{oid}/versions/{from}/migrate-to/{to}`) reassigns `event_definition_crf.default_version_id`, the version that *new* event CRFs get. It moves no existing data.

Changing the version of a single event CRF (`/pages/managestudy/chooseCRFVersion`, `ChangeCRFVersionController`) is also uncovered, per the survey.

### XForm

Refused unless `xform.enabled=true`, which is not set on this stack. Note that `CoreResources` initialises a property with a different name, `xformEnabled`. Not exercised.

### Blank template

The SPA's canvas builder replaces spreadsheet authoring for new CRFs. A spreadsheet upload path still exists, but without a template to start from.

---

## 9. Jobs & scheduling (Tasks → Administration → Jobs)

| Label | URL | Backing servlet | SPA coverage |
|---|---|---|---|
| Administer All Jobs (landing, server time) | `/ViewAllJobs` | `control.admin.ViewAllJobsServlet` | **COVERED** — `/admin/jobs` → `AdminJobsView.vue` |
| Scheduled Export Data Jobs (list) | `/ViewJob` | `control.admin.ViewJobServlet` | **PARTIAL** — `/admin/jobs` (read-only, all triggers) |
| Create Scheduled Job: Export Dataset | `/CreateJobExport` | `control.admin.CreateJobExportServlet` | **PARTIAL** — dataset schedules on `/export` → `DatasetListView.vue` |
| Edit an export job | `/UpdateJobExport?tname=<t>` | `control.admin.UpdateJobExportServlet` | **NOT COVERED** — — |
| Scheduled Import Data Jobs (list) | `/ViewImportJob` | `control.admin.ViewImportJobServlet` | **PARTIAL** — `/admin/jobs` (read-only) |
| Create Scheduled Job: Import Data | `/CreateJobImport` | `control.admin.CreateJobImportServlet` | **NOT COVERED** — — |
| Edit an import job | `/UpdateJobImport?tname=<t>` | `control.admin.UpdateJobImportServlet` | **NOT COVERED** — — |
| Job detail (fire times, dataset, period, formats, e-mail) | `/ViewSingleJob?tname=<t>&gname=<g>` | `control.admin.ViewSingleJobServlet` | **PARTIAL** — `/admin/jobs` row |
| Pause / resume a job | `/PauseJob?tname=<t>&gname=<g>` | `control.admin.PauseJobServlet` | **NOT COVERED** — — |
| Delete a job | `/PauseJob?tname=<t>&gname=<g>&del=y` | `PauseJobServlet` | **NOT COVERED** — — |
| Currently Executing Data Export Jobs, with Cancel | `/pages/listCurrentScheduledJobs` (+ `…Data`), `POST /pages/cancelScheduledJob` | `controller.ScheduledJobController` | **PARTIAL** — export-job list on `/export` (no cancel) |
| Import run log | `/ViewLogMessage?n=<path>&tn=<t>&gn=<g>` | `control.admin.ViewLogMessageServlet` | **NOT COVERED** — — |

### Legacy job lists and row actions

Both legacy job lists were **empty** on this stack, so the row actions come from `showJobRow.jsp` and `showImportJobRow.jsp`: View (`/ViewSingleJob`), Edit, Pause (a "Remove" icon), Restore (resume) and Delete. **Pause, resume and delete act on GET** (`PauseJobServlet`), so they were not followed.

### `/admin/jobs`

Read live through `GET /pages/api/v1/admin/jobs`. It lists every Quartz trigger with name, group, description, priority, previous/next/final fire time and state.

It is read-only by design: `AdminJobsView.vue` says pause surfaces are "out of scope for this slice". It is missing create, edit, pause, resume, delete and the run log.

### Export schedules are a different entity

- **Legacy.** `/CreateJobExport` creates an XSLT Quartz trigger in the `XsltTriggerService` group. Fields on the live form: Job Name*, Description*, Dataset, Period (daily / weekly / monthly), start date with hour and minute, File Format, Contact Email*. The dataset list rendered empty because no datasets exist, and no File Format options were offered.
- **SPA.** `POST /datasets/{id}/schedules` stores an `export_schedule` row (format and cron), which the SPA's own registrar schedules.

The consequences:

- The SPA does not list, edit, pause or delete jobs made through `/CreateJobExport`.
- The SPA's schedules have no name, description or completion e-mail.
- The SPA's schedules can be deleted but not edited.

### `/CreateJobImport`

Live form:

- Import Job Name*, Description*
- Study* (all four studies)
- a sub-directory under `/usr/local/tomcat/libreclinica.data/scheduled_data_import/`
- frequency*: every 0/1/2/4/8/24 h plus 0–50 min
- Contact Email

There is no SPA or API equivalent; the SPA's `/import-crf-data` is interactive only.

### `/pages/listCurrentScheduledJobs`

Live. The table loads by AJAX, and Cancel posts to `/pages/cancelScheduledJob`. The SPA's `/export` lists its own export jobs with their status, but nothing cancels one.

### `/ViewLogMessage`

Shows the log file of one scheduled import run, and is reached from the import-job detail page. Called with no parameters, it falls back to the main menu with "You don't have correct permission in your current Study" (seen live). There are no import jobs on the stack, so it was not exercised.

---

## 10. Global subject registry (Tasks → Administration → Subjects)

| Label | URL | Backing servlet | SPA coverage |
|---|---|---|---|
| Administer Subjects (every person, all studies) | `/ListSubject` (+ `/ListSubjectData` JSON) | `control.admin.ListSubjectServlet`, `ListSubjectDataServlet` | **NOT COVERED** — — |
| View Subject Details | `/ViewSubject?action=show&id=<n>` | `control.admin.ViewSubjectServlet` | **NOT COVERED** — — |
| Update Subject Details (Person ID, sex, date of birth) | `/UpdateSubject?action=show&id=<n>` | `control.admin.UpdateSubjectServlet` | **NOT COVERED** — — |
| Remove Subject (cascades to every study participation) | `/RemoveSubject?action=confirm&id=<n>` | `control.admin.RemoveSubjectServlet` | **NOT COVERED** — — |
| Restore Subject | `/RestoreSubject?action=confirm&id=<n>` | `control.admin.RestoreSubjectServlet` | **NOT COVERED** — — |

### The legacy screens

- **List** (live): all 10 subjects. Columns: Person ID · Protocol-Study Subject IDs · Sex · Date Created · Owner · Date Updated · Last Updated by · Status · Actions (View, Edit, Remove; Restore when removed). The empty-state text renders as `???no_data???`.
- **View** (live, id 1): person ID, sex, date of birth and date created. It also lists the associated study subjects (study subject ID, secondary ID, study, enrolment date, created, created by, status), each linking to `/ViewStudySubject`.
- **Update** (live form): Person ID, Sex (m / f / not specified), Date of Birth*.
- **Remove** (live confirmation page): lists the subject's study participations and events. Submitting marks the subject Removed and auto-removes its study subjects, events, event CRFs and item data. The notification e-mail is commented out in the source.
- **Restore**: not fetched, because no subject is removed.

### The nearest SPA surface

`/patients` → `PatientsOverviewView.vue` (`PatientsApiController`) reads the same `subject` rows, but it does not replace the registry:

- It is read-only: it has no edit, remove or restore.
- It is scoped to the caller's study bindings. For `manual_admin` it returned `{"totalCount":0}`, while `/ListSubject` listed 10 subjects.

---

## 11. Administrator-only actions inside study screens

| Label | URL | Backing servlet | SPA coverage |
|---|---|---|---|
| Delete an event CRF's data (reset it) | `/DeleteEventCRF?action=confirm&ssId=<n>…` | `control.admin.DeleteEventCRFServlet` (sysadmin only) | **NOT COVERED** — — |

- **Where it appears.** An icon on the event rows of View Subject (`showStudyEventRow.jsp`), Enter Data for Study Event and the signed-event page.
- **What it does.** Submitting deletes the event CRF's item data and dynamic metadata, sets it to RESET, and clears its rule run logs.
- **SPA equivalent.** None. `EventCrfsApiController` has restore, mark complete/incomplete and item writes, but nothing that deletes data.
- **Not exercised**, because there was no study context.

---

## 12. Other reachable pages

| Label | URL | Backing servlet | SPA coverage |
|---|---|---|---|
| OpenClinica Enterprise (upstream marketing page) | `/Enterprise` | `control.login.EnterpriseServlet` → `login/enterprise.jsp` | **NOT COVERED** — — (nothing to replace) |
| Manual download (Tasks menu, when `display.manual=true`) | `/manuals/administrator-manual.pdf` (+ investigator, monitor) | static files, `navBar.jsp` | **COVERED** — `/manual` → `ManualView.vue` (in-app handbook with an administrator chapter) |

- **`/Enterprise`** (live) describes Akaza's commercial services, with links to openclinica.org. Nothing links to it. Retire it outright.
- **Manual PDFs** are off on this stack. The SPA handbook is HTML and per role, not a PDF download.

---

## 13. Study-scoped screens an administrator also uses

These screens are catalogued for other roles and are not counted in §15. Their SPA verdicts belong in the catalogues that own them. They are listed here because DR-018 point 4 needs the Administrator role recorded against them too.

How an administrator reaches them:

- **Legacy UI.** Most of these screens require a study role in the current study. `StudyModuleController.mayProceed`, for example, checks the session role, not the user type. The exceptions are servlets whose `mayProceed` admits `isSysAdmin()` outright: the CRF, View Study and Update Study servlets.
- **SPA.** Every route below admits `Administrator`.

| Screen | Legacy URL | Catalogued in | SPA route |
|---|---|---|---|
| Build Study | `/pages/studymodule` | [DM §4](data-manager-features.md#4-build-study--the-primary-dm-workflow) | `/build-study` |
| Sites | `/ListSite`, `/CreateSubStudy`, `/ViewSite` | [DM §16.5–16.7](data-manager-features.md#165-manage-all-sites) | `/sites` |
| Event definitions | `/ListEventDefinition`, `/UpdateEventDefinition` | [DM §7, §16.3–16.4](data-manager-features.md#7-event-definitions) | `/event-definitions` |
| Subject group classes | `/ListSubjectGroupClass` | [DM §8](data-manager-features.md#8-subject-group-classes) | `/group-classes` |
| Rules | `/ViewRuleAssignment` | [DM §9](data-manager-features.md#9-rules-engine) | `/rules` |
| Users in the current study | `/ListStudyUser` | [DM §10](data-manager-features.md#10-user--role-management) | `/manage-users` |
| Datasets and export | `/ViewDatasets`, `/CreateDataset` | [DM §13](data-manager-features.md#13-data-extraction-data-manager-scope) | `/export`, `/datasets/new` |
| Study audit log | `/StudyAuditLog` | [DM §12](data-manager-features.md#12-study-audit-log-data-manager--top-nav) | `/audit-log` |
| Source data verification | `/pages/viewAllSubjectSDVtmp` | [Monitor §4](monitor-features.md#4-source-data-verification-sdv--primary-workflow) | `/sdv` |
| Subject matrix, data entry, notes | `/ListStudySubjects`, CRF entry, `/ViewNotes` | Investigator and Monitor catalogues | `/subjects`, `/event-crfs/:eventCrfOid`, `/notes` |

---

## 14. SPA administrator surfaces with no legacy counterpart

These pages retire nothing. They are listed because they belong to the administrator surface that replaces the legacy one:

- `/admin/system-status`: JVM heap and threads, database reachability and version, the Liquibase count, uptime, and the retinal-inference cluster (`GET /admin/retinal-cluster`).
- `/admin/config`: the resolved deployment configuration, read-only by design.
- `/system/audit-log`: the institution-wide `audit_log_event` trail, including the failure-class rows (`OPERATION_FAILED`, `JOB_FAILED`) that study views hide.
- `/admin/jobs`: every Quartz trigger, including the MUW retention and poller jobs that no legacy screen shows.
- `/modalities`: the imaging-modality catalogue (Administrator only).
- In *Invite user*: SSO pre-binding of a new user to an institutional principal (eppn).

---

## 15. Coverage summary

Counted over the feature tables in §3–§12. The navigation rows in §2 and the pointer rows in §13 are not counted.

| Section | Features | COVERED | PARTIAL | NOT COVERED |
|---|---:|---:|---:|---:|
| §3 Landing pages | 2 | 0 | 1 | 1 |
| §4 Studies | 8 | 1 | 3 | 4 |
| §5 Users & accounts | 12 | 5 | 6 | 1 |
| §6 Security configuration & mail | 3 | 1 | 0 | 2 |
| §7 System status & audit | 5 | 3 | 0 | 2 |
| §8 CRF library | 16 | 9 | 2 | 5 |
| §9 Jobs & scheduling | 12 | 1 | 5 | 6 |
| §10 Global subject registry | 5 | 0 | 0 | 5 |
| §11 Admin-only study actions | 1 | 0 | 0 | 1 |
| §12 Other | 2 | 1 | 0 | 1 |
| **Total** | **66** | **21** | **17** | **28** |

The gaps most likely to cost clinical or regulatory capability if a screen were deleted too early:

1. **Global subject registry (§10).** There is no SPA path to correct a person's ID, sex or date of birth across studies, or to remove or restore a person together with every participation. `/patients` is read-only and scoped to the caller's bindings.
2. **Batch migration of existing CRF data (§8).** Only the legacy screen moves subjects' existing event CRFs to a new version. The SPA's *Migrate* changes the default version for new event CRFs.
3. **Login history (§7).** `/AuditUserActivity` is the only UI over `audit_user_login`: successful and failed logins, and logouts.
4. **Account lockout settings (§6).** Whether lockout is on, and after how many failed attempts, can be changed only on `/Configure`.
5. **Study lifecycle and the all-studies view (§4).**
   - Remove and restore exist only in the API.
   - No SPA list shows studies the administrator is not bound to.
6. **Study record fields (§4).**
   - Dates, design, eligibility, facility and related information have no SPA field at all.
   - Login e-mail notification is missing from the API.
   - Contact e-mail, collaborators and detailed description exist only in the API.
7. **Administrator accounts and role reach (§5).** The SPA cannot:
   - create, promote or demote an administrator;
   - grant the Data Entry Person roles (`ra`/`ra2`);
   - bind a user to a study the administrator is not bound to.
8. **Scheduled imports and job control (§9).**
   - Creating or editing import jobs, and pausing, resuming, deleting or editing any job, are legacy-only.
   - Legacy export jobs are invisible to the SPA's schedule list.
9. **Other data-changing actions.** Deleting an event CRF's data (§11), editing a CRF's name and description, and restoring a removed CRF (§8).
10. **Smaller gaps.** The test e-mail (§6), the schema change log (§7), the study metadata download (§4), the blank CRF template (§8) and the two-factor LETTER printout (§5).

---

## 16. Findings that bear on retirement

### 16.1 Legacy admin screens that are broken or inert on this build

| Screen | What happens | Cause |
|---|---|---|
| `/ViewStudy` | HTTP 500 for every study | `ParticipantPortalRegistrar` needs Apache HttpClient 5, which is absent from the WAR (§4) |
| `/ConfigurePasswordRequirements` | HTTP 200, empty body | the JSP imports `javax.servlet.http.HttpServletRequest` (§6) |
| `/AuditLogUser` | always "no rows" | reads the heritage `audit_event` table, which is empty (§7) |
| `/SystemStatus` | plain text, no page, no permission check | its forward to a JSP is commented out (§7) |
| `/TechAdmin`, `/Enterprise` | no function | content only (§3, §12) |
| `/CreateXformCRFVersion` | always refused | `xform.enabled` is not set (§8) |
| *print* link on `/ViewCRF` | dead | points at the unregistered Jersey tree (§8) |
| `/AuditUserActivity`, `/ListSubject` | `???attempt_date???`, `???no_data???` | missing i18n keys |

The same `ParticipantPortalRegistrar` call that breaks `/ViewStudy` is also made from `AccountController`, `UserInfoController`, `StudyEventController`, `OdmController`, `EditFormController`, `AnonymousFormControllerV2` and `openrosa.OpenRosaSubmissionController`. The failure is a `NoClassDefFoundError`, thrown when the request factory is constructed. That happens before the `try` in `loadRegistrationStatus`, and it is an `Error` rather than an `Exception`, so that method's `catch (Exception e)` would not stop it anyway. Whether any of those callers catches it further up was not checked. Those paths are outside this catalogue and were not exercised.

### 16.2 Admin actions that change data on a plain GET

`/DeleteUser?action=3|4`, `/UnLockUser`, `/DeleteStudyUserRole?action=3|4`, `/PauseJob` (pause, resume and `del=y`) and `/SendTestEmail` act as soon as their URL is requested. A JavaScript `confirm()` on the link is the only guard.

Two defences do not stop this:

- `CrossSiteRequestFilter` passes safe methods through ("Safe methods (GET, HEAD, OPTIONS, TRACE) pass untouched").
- The session cookie is `SameSite=Lax`. That stops cross-site sub-resource requests, but not a top-level navigation.

A link followed from another site therefore performs the action with the administrator's session. This is the kind of undetected weakness DR-018 aims to remove.

### 16.3 Where this catalogue disagrees with earlier coverage claims

The earlier sources are:

- the 2026-09-30 static survey behind DR-018;
- [legacy-retirement-phase-c-plan.md](../../legacy-retirement-phase-c-plan.md);
- `web/deprecation/LegacyServletDeprecationCatalog.java`, which maps legacy paths to SPA routes for the deprecation banner.

| Legacy screen | Earlier claim | Finding here |
|---|---|---|
| `/Configure` | Survey: PARTIAL. Plan L3.4 describes it as "deployment-time settings (timezone, locale, max-upload-size)". The deprecation catalogue maps it to `/app/admin/config`. | It is lockout configuration; `/admin/config` shows other data. **NOT COVERED.** |
| `/BatchCRFMigration` | Survey: PARTIAL, via `migrate-to`. | `migrate-to` is a different operation. **NOT COVERED.** |
| `/InitUpdateCRF`, `/UpdateCRF`, `/RestoreCRF` | Survey: COVERED. The deprecation catalogue maps them to `/app/crf-library`. | No API or UI exists for either. **NOT COVERED.** |
| `/RemoveStudy`, `/RestoreStudy` | The deprecation catalogue maps them to `/app/studies/:oid/edit`. | That view does not offer them. **NOT COVERED** (API only). |
| `/PauseJob` | The deprecation catalogue maps it to `/app/admin/jobs`. | That view is read-only. **NOT COVERED.** |
| `/AuditUserActivity`, `/AuditDatabase` | Survey: PARTIAL / NOT COVERED. The deprecation catalogue maps both to `/app/system/audit-log`. | That view reads neither table. **NOT COVERED.** |
| `/ViewLogMessage` | The deprecation catalogue maps it to `/app/audit-log`. | It is an import-job log-file viewer. **NOT COVERED.** |
| `/AuditLogUser` | Survey: PARTIAL. | The legacy screen returns nothing. **COVERED** by the actor filter, within its 500-row cap. |
| `/CreateStudy` | Survey: an 8-page wizard. | One page in practice (§4). |

### 16.4 The legacy-access log cannot show admin use yet

DR-018 point 4 deletes a screen only when "the access log shows no use by anyone". The telemetry meant to produce that log does not fire for legacy servlet URLs:

- `LegacyServletTelemetryFilter` is registered on `/pages/*` only (`ServletInfraConfig.legacyServletTelemetryFilter`).
- It compares `getRequestURI()`, which includes the `/LibreClinica` context path, against keys such as `/pages/ListUserAccounts`. The servlets actually live at `/LibreClinica/ListUserAccounts`.
- After this walk, some fifty legacy GETs, the application log held **0** `legacy-hit` lines.
- The `libreclinica.legacy.servletsEnabled=false` switch, which should answer 410 Gone, is inert for the same reason.

Until the filter is fixed, an empty log is not evidence of non-use.

---

## 17. JSP file map (for Phase E rewrite scoping)

JSPs an administrator can reach:

- [web/src/main/webapp/WEB-INF/jsp/admin/](../../../../web/src/main/webapp/WEB-INF/jsp/admin/)
  - Landing: `index.jsp`
  - Users: `listuseraccounts.jsp`, `showUserAccountRow.jsp`, `createuseraccount.jsp`, `viewuseraccount.jsp`, `edituseraccount.jsp`, `edituseraccountconfirm.jsp`, `setUserRole.jsp`, `editstudyuserrole.jsp`
  - Security: `configuration.jsp`, `configurationPasswordRequirements.jsp`
  - Audit: `auditUserActivity.jsp`, `auditLogUser.jsp`, `showAuditEventRow.jsp`, `auditDatabase.jsp`, `auditLogStudy.jsp`, `studyAuditLog.jsp`, `auditItem.jsp`
  - CRFs: `listCRF.jsp`, `showCRFRow.jsp`, `createCRF.jsp`, `createCRFVersion*.jsp`, `createXformCRFVersion.jsp`, `viewCRF.jsp`, `updateCRF*.jsp`, `removeCRF*.jsp`, `restoreCRF*.jsp`, `deleteCRFVersion.jsp`, `batchCRFMigration.jsp`
  - Jobs: `viewAllJobs.jsp`, `viewJobs.jsp`, `showJobRow.jsp`, `viewImportJobs.jsp`, `showImportJobRow.jsp`, `viewSingleJob.jsp`, `showAuditEventJobRow.jsp`, `createExportJob.jsp`, `updateExportJob.jsp`, `createImportJob.jsp`, `updateImportJob.jsp`, `viewLogMessage.jsp`
  - Studies: `viewStudy.jsp`, `viewFullStudy.jsp`, `removeStudy.jsp`, `restoreStudy.jsp`
  - Subjects: `listSubject.jsp`, `viewSubject.jsp`, `updateSubject*.jsp`, `removeSubject.jsp`, `restoreSubject.jsp`
  - Study actions: `deleteEventCRF.jsp`
- [web/src/main/webapp/WEB-INF/jsp/managestudy/](../../../../web/src/main/webapp/WEB-INF/jsp/managestudy/): `studyList.jsp`, `createStudy1..8.jsp`, `studyCreateConfirm.jsp`, `updateStudyNew.jsp`, `updateStudy1..8.jsp`, `studyUpdateConfirm.jsp`
- [web/src/main/webapp/WEB-INF/jsp/techadmin/](../../../../web/src/main/webapp/WEB-INF/jsp/techadmin/): `index.jsp`
- [web/src/main/webapp/WEB-INF/jsp/login/](../../../../web/src/main/webapp/WEB-INF/jsp/login/): `enterprise.jsp`
- [web/src/main/webapp/WEB-INF/jsp/](../../../../web/src/main/webapp/WEB-INF/jsp/) (Spring MVC): `listCurrentScheduledJobs.jsp`

Already unreachable in `jsp/admin/`, per the survey: `systemStatus.jsp`, `listLdapUsers.jsp`, `showSubjectRow.jsp`, `auditLogStudyOld.jsp`, `showAuditEventStudyRow.jsp`, `createCRFConfirm.jsp`, `viewScheduler.jsp`, `createCRFVersionConfirmSQL.jsp`, `uploadCRFVersionFile.jsp`.

---

## 18. Open follow-ups / known gaps in this catalogue

### Not exercised, because the walk was read-only

- Any form submit.
- Actions that fire on GET (§16.2): remove, restore and unlock user; remove and restore study role; *Send Test Email*; job pause, resume, delete and cancel.
- Creating or editing scheduled jobs. The stack has no jobs, so the job-detail and run-log pages were not seen either.
- `/CreateStudy` pages 2–8, and whether any client path reaches them.
- `/RestoreStudy`, `/RestoreSubject` and `/RestoreCRF`: nothing on the stack is removed.
- `/DownloadStudyMetadata` and the spreadsheet downloads: not fetched.
- `/DeleteEventCRF`: no study context.

### Not reachable as this account or configuration

- `/TechAdmin`: only technical administrators can open it. `root` is the only one, and it was not used.
- Legacy screens gated on a study role, such as Build Study. `manual_admin` has no study binding.
- LDAP (`ldap.enabled=false`).
- Two-factor authentication (`2fa.activated=false`): `/PrintoutCertificate` and the Authentication Type fields.
- XForm upload.
- The Manual Download menu.

### Checked from source and API GETs, not in a browser

The SPA side was checked from source and API GETs, not in a browser. One question stays open:

- The user-lifecycle endpoints refuse to act without a study on the session (`UsersApiController.preflightLifecycle`: "No active study bound").
- `/studies` is empty for an administrator with no binding, so `/pick-study` cannot set one.
- It is unknown whether a pure-SPA session for such an administrator gets a session study some other way.
- Observed: with a legacy page visited first, `GET /users/manual_dm/roles` worked. Straight after login, it answered 400.

### Not checked

- Whether the SPA's `/studies/{oid}/disable` cascades the way `RemoveStudyServlet` does.
- Whether the SPA's create and edit forms validate lengths and uniqueness as the servlets do.
- Why the export-job form offered no File Format options, although `extract.properties` defines them.

### Build provenance

The stack was built at 2026-09-30 08:25 UTC from a tree this walk did not identify. Source references are to branch `docs/muw-dr-018-jsp-retirement` at `4f3acc6ef`, which is `lc-develop` `136650004` plus DR-018.

### Outside this catalogue, but found on the way

- The HTTP 500 on concurrent-session expiry (§1).
- The HttpClient 5 gap on the OpenRosa, account and ODM controller paths (§16.1).
- The telemetry filter that never fires (§16.4).

### Keeping it current

When a verdict here changes, update this file before the entry in `phase-e-retirement-log.md`.

### Recommended next pass

Repeat the walk as `root`, a technical administrator with study bindings, on a disposable stack where submits are allowed. That pass would cover what this one could only read from source:

- the create, edit and remove flows;
- the job screens, with real jobs;
- LDAP and two-factor, with their switches on.
