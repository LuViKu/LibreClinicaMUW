# LibreClinica MUW · 1.5.0-beta.21-muw release notes

_Successor to **1.5.0-beta.20-muw**. A release about **who may do what**, and about **retiring the old interface**. It came out of removing screened patients who did not consent from HealthAEye._

_**Roles:**_
- _A Study Director could not remove anyone, and an account with several roles signed in as the wrong one._
- _The Data Manager now enrols participants and enters data, and **only an Investigator signs**, both the casebook and single visits._
- _Trial blinding counts every treating role an account holds._
- _A system administrator no longer loses a study-level Administrator role when picking a study._

_**Legacy interface:** after the log showed no use, every old screen outside the shell is closed to everyone but administrators. The first two waves of old screens are deleted, and so is the OpenClinica 3.x REST API._

For older releases see [release-notes-1.5.0-beta.20-muw.md](release-notes-1.5.0-beta.20-muw.md) and its predecessors.

**Deployment-breaking:** nothing has to be set.
- **No migrations:** the changelog count stays at 1206.
- **Nothing to configure, no change to the host setup, nginx or the sidecars.** Retinal-inference and dicom-scp get the same tag as usual, but their code is unchanged.

Two things change for users the day it is installed:
1. **Signing.** A Data Manager (Study Director) who signed casebooks or visits until now, or a Koordinator who signed visits, can no longer sign. Whoever signs must hold the **Investigator** role (Prüfarzt/Prüfärztin), and only that role on the study (see [Signing goes by the active role](#signing-goes-by-the-active-role)).
2. **The old screens.** Any bookmark to an old screen (`/LibreClinica/ListStudySubjects`, `/ViewStudySubject`, …) now opens a page that says the screen is switched off and links the new interface. Only the main menu, study switch, profile and password screens, and the two public forms stay open.

Work through [Upgrading the app VM](#upgrading-the-app-vm), then [After the upgrade: role setup](#after-the-upgrade-role-setup) and [What needs a human to check](#what-needs-a-human-to-check-after-the-upgrade).

---

## Highlights

### Participants: open, remove, and the strongest role at sign-in (#442)

Found in production while removing screened patients who did not consent: a Study Director could not remove anyone, for three reasons.

- **Sign-in bound the wrong role.** An account with several roles on one study (for example Study Director, Monitor and Koordinator) was signed in with whichever role the database returned last, usually the newest grant.
  - A Monitor sees only the sites it is granted on explicitly, so the participant list came up empty and *Entfernen* was refused.
  - Picking the study again fixed it until the next sign-in.
  - **Now** sign-in binds the strongest live role, the same rule the study picker already used: Administrator, then Study Director, Koordinator, Investigator, Data Entry, Monitor.
- **The participant page sent the Data Manager home.** *Öffnen* returned to the landing page. The page now admits the Data Manager; its *Entfernen* button was already meant for Data Manager and Administrator.
- **No way to the participant list.** The Data Manager and the Administrator now find *Studienteilnehmer* in the top bar and on the home page.

**Blinding.** Until now only the session role decided whether an account saw the AI output of hidden-arm participants. Since sign-in now binds the strongest role, a treating physician who also holds Study Director would have seen it. **Any treating role** the account holds on the active study (or on its parent, when that is a site) now blinds: Investigator or Koordinator. A removed binding, or one on another study, does not.

### The Data Manager enters data; only the Investigator signs (#443)

Decided in the role review of 2026-11-13. At MUW, study management (the app's **Data Manager**, Study Director in the old interface) transcribes and corrects the paper CRFs. The signature is the investigator's attestation that the record is complete and correct.

- **The Data Manager enrols participants and enters and corrects CRF data.** The server already allowed it, but the add button and *Datenerfassung starten* sent a Data Manager to the home page. A CRF link opens the entry form for a Data Manager, also when the account holds Monitor too.
- **Only an Investigator signs, the casebook and single visits alike.**
  - Signing a **visit** used the visit-*edit* rule, so administrator, Koordinator and Study Director could sign a visit. It now uses the casebook's rule.
  - Both sign endpoints refuse other roles (403), and the app hides the sign buttons for them.
- **The manuals** (in the app, German and English, and `docs/manuals/app-manual/role-data-manager.md`) say so.

#### Signing goes by the active role

An account is signed in with its strongest role (#442). An account holding both **Investigator and Study Director** on a study is therefore signed in as Study Director and **cannot sign**. Whoever signs should hold Investigator only on that study.

### 'Heute' shows only today's visits (#439)

- **The bug.** The **Heute** chip in the participant list, and the home page's *Heutige offene CRFs* card that links to it, showed every participant with *any* open visit, overdue ones from earlier days included.
- **Now** a participant shows under *Heute* only for an open visit (scheduled or in data entry) dated today. The home card counts the same way, so its number matches the list it opens.
- **Unchanged.** Overdue visits stay under *Fällige Visiten*, and *Offene Visiten* still shows every open visit.
- **API.** Each visit cell of `GET /api/v1/subjects` gains `date`.

### A system administrator keeps their study-level Administrator role (#446)

- **The bug.** A system administrator is marked by an `admin` role row without a study. Every save of such an account rewrote that marker by deleting all of the account's `admin` rows, the Administrator role it held on any study included. A save happens when the account picks a study, changes its profile or password, or is edited by another administrator. Demoting an administrator did the same.
- **The fix.** Only the marker row is rewritten now. A study-level Administrator role is a study role like any other.
- **What it does not repair.** A role already lost cannot be told apart from the marker. Grant it again where it is needed (see [After the upgrade](#after-the-upgrade-role-setup)).

### The old interface: one wave closes all but the shell (#444, #445, #447)

The DR-018 plan was to close the old screens area by area over several months, each followed by a six-month wait before deletion. The legacy-access log since 2026-10-08, and Tomcat's access logs since 2026-09-28, showed no request by anyone but a system administrator. So the [DR-018 amendment of 2026-11-13](../development/modernization/decision-record.md#amendment-2026-11-13--one-wave-and-a-four-week-bake-in) changes two things:
- **One wave** closes everything but the shell;
- **four weeks** in production, instead of six months, before a closed screen is deleted.

- **Closed (#444): 164 screens.** Everyone but a system administrator gets a page that says the screen is switched off and links its replacement in the new interface, where there is one (status 410):
  - participants and visits;
  - data entry and double data entry;
  - notes, SDV and print;
  - export and extract;
  - the rest of the study build: event definitions, sites, groups, rules, *ViewCRF*, *ViewStudy*;
  - study-user roles and the audit pages.

  **A system administrator** is sent to `/LibreClinica/legacy/<screen>` and served there. That covers the one thing the new interface does not do yet: moving a participant to another site (`/ReassignStudySubject`).
- **Still open, until the shell wave:** `/MainMenu`, `/ChangeStudy`, `/Logout`, `/UpdateProfile`, `/ResetPassword`, `/MatchPassword`, `/RequestStudy`, and the public `/RequestAccount` and `/Contact`. Sign-ins, single sign-on and an expired session still land on `/MainMenu`.
- **Deleted (#445): waves 0 and 1,** four weeks after they reached production with beta.16. These are the not-needed screens and the old administration: users, roles, studies, jobs, the CRF library and the study audit log. That is 55 servlets, two controllers, 60 JSPs and 57 page constants. A deleted path now answers **404**, for administrators too.
  - The shell's task menu links the new screens instead of the deleted administration screens.
  - Closed wave-2 screens that ended on a deleted list now end on the main menu.
- **Deleted (#447): the OpenClinica 3.x REST API** under `/pages/auth`, with the API-key filter that authenticated it. The log showed no call to it. A request there now needs a sign-in and then answers 404.
- **The legacy-access log** lists 164 of 173 screens as closed at startup (it was 225 of 234).

---

## Smaller items

- **SPA dependencies (#440).** The open Dependabot alerts on the SPA's toolchain are fixed: vue-i18n 11, Vite 6, Vitest 4, and patched versions of shell-quote, launch-editor and markdown-it. The SPA builds on Node 22.23.4. Nothing changes in what the app does.
- **Unused code went with wave 1:** the old breadcrumb builder, which nothing called, and the CRF template spreadsheet behind the deleted template download.

---

## Upgrading the app VM

Do this outside clinic hours. The restart takes the app down for a few minutes, and everyone signs in again afterwards.

1. **Tell the study team first.** From the upgrade on, only an Investigator signs, and old bookmarks open a page that links the new interface (see the top of these notes).

2. **Back up, and dry-run against that backup.**
   ```sh
   sudo systemctl start libreclinica-backup-db.service
   IMAGE=ghcr.io/luviku/libreclinicamuw:1.5.0-beta.21-muw sudo -E /opt/libreclinica/deploy/dry-run-migration.sh
   ```
   **Expect 0 new changesets (1206 → 1206)** and a healthy start. The rollback is `1.5.0-beta.20-muw`.

3. **Check that nothing overrides the closed screens.** The environment file must not set `LIBRECLINICA_LEGACY_CLOSED_PATHS`. If it does, it replaces the default and decides which screens are closed.
   ```sh
   sudo grep -c LIBRECLINICA_LEGACY_CLOSED_PATHS /etc/libreclinica/env   # expect 0
   ```

4. **Set the tag and restart:**
   ```sh
   sudo bash /opt/libreclinica/deploy/setup-ubuntu-host.sh --image-tag 1.5.0-beta.21-muw   # plus --dicom, as on the last run
   sudo systemctl restart libreclinica
   ```

5. **Smoke test** (runbook §4) and the checks below. **If anything is wrong, roll back** by running the same two commands with `1.5.0-beta.20-muw`. No data changes need undoing.
   - A rollback reopens the screens closed here.
   - It also brings back the wipe of study-level Administrator roles (#446).

---

## After the upgrade: role setup

- **The `studienmanager` account** should hold **Data Manager** only on HealthAEye. It now holds Study Director (= Data Manager), Monitor and Koordinator, and Koordinator is a treating role: it blinds the account to the AI output of hidden-arm participants.
- **The admin account** should hold **Data Manager** on each study where it removes or manages participants. A system administrator with no study role sees "Administrator" in the app, but is refused study writes.
- **Anyone who signs** holds **Investigator**, and only that role on the study.
- **A study-level Administrator role on a system administrator account** may have been wiped before this release (#446). Grant it again where it is needed.

---

## What needs a human to check after the upgrade

- **The closed screens are in force.** The startup line reads `legacy-retirement: 164 of 173 legacy screens closed`:
  ```sh
  sudo grep legacy-retirement /var/lib/libreclinica/tomcat-logs/libreclinica-legacy-access.log | tail -1
  ```
- **An old bookmark.** `/LibreClinica/ListStudySubjects` opened by a non-administrator shows *Diese Seite wurde abgeschaltet* with a button *Zur neuen Oberfläche*; opened by the administrator it goes to `/LibreClinica/legacy/ListStudySubjects`. `/LibreClinica/ListUserAccounts` answers 404 for both.
- **The Data Manager**, signed in with the reworked `studienmanager` account:
  - sees the participant list and opens a participant without landing on the home page;
  - removes a test participant (*Entfernen*);
  - enrols a test participant and enters a CRF;
  - sees no sign button.
- **An Investigator** signs a test visit and a test casebook.
- **Heute** lists only participants with an open visit dated today.
- **The administrator** picks a study and still holds the study-level role it had afterwards (*Nutzer verwalten* → the account's roles).

---

## Still open at this release

- **CI results.** CI run [43606814054](https://github.com/LuViKu/LibreClinicaMUW/actions/runs/43606814054) on `2a736e05f` (lc-develop after #446, the code of this release without the version bump) passed all seven jobs:
  - Maven build and unit tests: core 599, web 1,440. Web is down from 1,541 because the tests of the deleted screens went with them.
  - Integration tests on PostgreSQL 14 and 17 passed. On 17: core 679, web 4,509 (down from 4,613, with the deleted code). The PostgreSQL 14 log was too large to fetch whole for its counts; it runs the same suite.
  - The compose smoke test: `api.ts` matches the live spec; E2E smoke 28, accessibility 17, DICOM 4.
  - The SPA: 2,619 vitest tests, 4 skipped.
  - The three Python sidecar suites: muw-e2e-converter 83, dicom-scp 107, retinal-inference 338.
- **Wave 2 is deleted four weeks after this release reaches production,** if the legacy-access log still shows no use by anyone but an administrator. Read it monthly; a hit by anyone else reopens that screen.
- **The shell wave (W5)** needs four things changed first: the sign-in and single-sign-on landing on `/MainMenu`, the expired-session redirect, the `sysURL` links in e-mails, and the old logout's target.
- **Moving a participant to another site** exists only on the old screen, for administrators (`/legacy/ReassignStudySubject`). The old screen does not offer the parent study when the participant sits at a site. A screen in the new interface is planned for the multi-site setup (DR-040).
- **A legacy scheduled extract job** still e-mails a `/AccessFile` link, which now answers 410 to anyone but an administrator. No such download appears in the logs.
- **New accounts still get an API key,** which nothing reads any more since the REST API is gone.
- **Histoire,** the SPA's component catalogue (a development tool), does not build on Vite 6 yet. The app is not affected.
- **Carried over from beta.20 and still open:** see the [beta.20 notes](release-notes-1.5.0-beta.20-muw.md#still-open-at-this-release).
