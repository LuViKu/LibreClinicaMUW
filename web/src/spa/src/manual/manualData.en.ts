/* LibreClinica MUW — Application Manual · English content model.
   Ported from the Claude Design handoff (manual-data.js). Inline markup in
   strings: **bold**, *italic*, `code`. Screenshot paths are `role/id.png`,
   resolved against `${BASE_URL}manual/` by the view.
   The German-primary translation lives in ./manualData.de.ts. */
import type { Manual } from './manualTypes'

export const manualEn: Manual = {
  meta: {
    title: 'Anwendungshandbuch',
    product: 'LibreClinica MUW',
    subtitle: 'End-user manual for the clinical study-data platform of the Department of Ophthalmology — organised by role.',
    version: 'v1.5.0-beta.2-muw',
    build: 'Build 25-06-2026 · 7518bd3de',
    shotBase: 'uploads/app-manual/manuals/app-manual/screenshots/',
  },

  /* role accent keys map to CSS classes in the shell */
  roles: {
    common:       { label: 'Alle Rollen',        en: 'All roles',    accent: 'blue'  },
    administrator:{ label: 'Administrator/-in',   en: 'Administrator',accent: 'blue'  },
    'data-manager':{ label: 'Studienleitung',     en: 'Data Manager', accent: 'coral' },
    monitor:      { label: 'Monitor',             en: 'Monitor',      accent: 'sky'   },
    investigator: { label: 'Prüfarzt/-ärztin',    en: 'Investigator', accent: 'teal'  },
    crc:          { label: 'Koordinator/-in',     en: 'CRC',          accent: 'tealdk'},
  },

  chapters: [
    /* ─────────────────────────────── GETTING STARTED ─────────────────────── */
    {
      id: 'getting-started', role: 'common', kicker: 'Kapitel 00',
      title: 'Getting started', deutsch: 'Erste Schritte',
      oneLiner: 'Sign-in, first-login profile, study/site context, and the navigation chrome — shared by every role.',
      intro: [
        'This chapter covers what every role shares: signing in, the first-login profile, choosing your study/site, and the navigation chrome. Role-specific work starts in the per-role chapters.',
      ],
      sections: [
        {
          id: 'gs-signin', num: '1', title: 'Signing in', deutsch: 'Anmelden',
          shot: 'common/00-login.png',
          goal: 'Authenticate into the platform.',
          body: [
            "The app is reached at your institution's URL (at MUW, the eCRF address provided by IT). There are two ways to authenticate:",
          ],
          bullets: [
            '**Institutional single sign-on (Shibboleth).** Click the SSO button and complete login at the MUW identity provider. Most clinical staff use this path.',
            '**Local account.** Enter your **username** and **password** — used for sponsor monitors, service accounts, and break-glass access.',
          ],
          notes: [
            'If your credentials are wrong the form shows *“Invalid username or password.”* After too many failed attempts the account locks — contact an Administrator.',
          ],
        },
        {
          id: 'gs-first-login', num: '2', title: 'First login & your profile', deutsch: 'Erstanmeldung & Profil',
          shot: 'common/01-first-login.png',
          goal: 'Complete your profile before you reach the app.',
          body: [
            'On first login (or after an administrator resets your account) you are asked to complete your **profile** — name, email, and interface language — and, if your account requires it, to **change your password**.',
            'You cannot reach the app until the profile is complete; this guarantees the audit trail has a real name behind every action.',
          ],
        },
        {
          id: 'gs-study', num: '3', title: 'Choosing a study (and site)', deutsch: 'Studie auswählen',
          shot: 'common/02-pick-study.png',
          goal: 'Scope your session to one study and site.',
          body: [
            'If your account is attached to more than one study, you land on the **study picker** after login. Pick the study (and, where applicable, the site) you want to work in. Everything you do afterwards is scoped to that selection. You can switch later from the top bar.',
          ],
        },
        {
          id: 'gs-chrome', num: '4', title: 'The navigation chrome', deutsch: 'Navigationsrahmen',
          shot: 'administrator/00-home.png',
          goal: 'Learn the frame shared by every page.',
          bullets: [
            '**Top bar** — the active study/site, a search box for subject IDs, your name with a **role chip** (colour-coded per role), the language indicator, and **Log out**.',
            '**Side navigation** — the workflows available to your role. The set of links is role-gated, so a Monitor and a Data Manager see different menus.',
            '**Breadcrumbs** — show where you are within a multi-step flow (e.g. Subject → Event → CRF).',
          ],
          notes: [
            'The **home dashboard** summarises what needs your attention — for example notes and discrepancies assigned to you — and provides quick links into your most common tasks.',
          ],
        },
        {
          id: 'gs-language', num: '5', title: 'Language & logging out', deutsch: 'Sprache & Abmelden',
          goal: 'Understand the German-first UI and shared-workstation hygiene.',
          body: [
            'The interface is **German-first** for clinical staff (e.g. *Modalitäten*, *Übernehmen*); some administrative screens remain English. Your language is set in your profile.',
            'Use **Log out** in the top bar. For shared workstations always log out — the audit trail attributes every entry to the signed-in user.',
          ],
        },
      ],
    },

    /* ─────────────────────────────── ADMINISTRATOR ───────────────────────── */
    {
      id: 'administrator', role: 'administrator', kicker: 'Kapitel 01',
      title: 'Administrator', deutsch: 'Administrator/-in',
      oneLiner: 'System + study setup: users, sites, studies, CRFs, rules, config, audit. A superset of every other role.',
      intro: [
        'The **Administrator** is the superset role in LibreClinicaMUW. It combines **system setup** (user accounts, sites, modality catalog, password policy, scheduled jobs, system status) and **study setup** (creating studies, building event definitions and CRFs, rules, datasets, importing data). Wherever the application gates a screen on *Administrator*, you reach it; several of those screens are visible to no other role.',
      ],
      callout: {
        kind: 'info', title: 'Role gating',
        text: 'The web app hides controls you are not allowed to use, but the *server* is the authoritative gate — every Administrator-only action is re-checked on the backend and every lifecycle action (disable, restore, reset, status change) is written to the audit trail. The Administrator role does **not** silently inherit other roles: study-scoped screens open because they list *Administrator* as an allowed role, not through inheritance.',
      },
      sections: [
        {
          id: 'ad-matrix', num: '1', title: 'Subject Matrix', deutsch: 'Studienteilnehmer', route: '/subjects',
          roles: ['investigator','monitor','data-manager','administrator'],
          shot: 'administrator/01-subject-matrix.png',
          goal: 'See every subject in the active study and their per-visit progress at a glance.',
          steps: [
            'Open **Studienteilnehmer** (Subject Matrix) from the home page or side-rail (`/subjects`).',
            'Use the search box and the filter chips (*all*, *today*, *ready-to-sign*, *open-events*, *all-events-complete*, *signed*) to narrow the list; tick **Nur mit Queries** to show only subjects with open queries.',
            'Read each row: the frozen left column is the **Subject ID**; scrollable columns show one status pill per visit, plus gender, study eye (OD/OS/OU), group and enrolment date.',
            'Use the **V1 … VN** visit controls (First / Previous / Next / Latest) above the table to page across visits.',
            'Click **Öffnen** (Open) at the right of a row to open that subject\u2019s detail page.',
          ],
          notes: [
            'Open to Investigator, Monitor, Data Manager and Administrator. The **Export** button downloads the matrix; **Studienteilnehmer hinzufügen** (Add Subject) starts enrolment.',
          ],
        },
        {
          id: 'ad-users', num: '2', title: 'Manage Users', deutsch: 'Nutzerverwaltung', route: '/manage-users',
          roles: ['administrator'],
          shot: 'administrator/02-manage-users.png',
          goal: 'Create, edit, disable/restore, unlock and password-reset accounts, and assign per-study roles.',
          steps: [
            'Open **Nutzerverwaltung** (Manage Users) from the home page or side-rail (`/manage-users`).',
            'To add a user, click **Nutzer/-in einladen** (Invite User) and complete the invite dialog; on success a one-time password is shown inline — copy it and hand it over through a secure channel.',
            'Filter the table with the search box, the **Rolle** and **Auth** dropdowns, and the **Nur aktive** (only active) checkbox.',
            'For any row, use the inline actions: **Bearbeiten** (Edit), **Deaktivieren / Reaktivieren** (Disable / Restore), **Passwort zurücksetzen** (Reset Password) and **Entsperren** (Unlock) — offered only for active *Lokal* accounts — and **Rollen** (Roles).',
            'After a restore, reset or unlock, the generated one-time password appears in the green panel below the table — copy it before dismissing.',
          ],
          notes: [
            'This screen is **Administrator-only** — the invite button and every per-row action are hidden for other roles.',
            'Roles show with German labels: *Prüfarzt/-ärztin* (Investigator), *Monitor*, *Studienleitung* (Data Manager), *Administrator/-in*, *Koordinator/-in* (CRC). The **Auth** column distinguishes *Lokal*, *SSO*, *LDAP (Legacy)* and *Einladung offen*; reset and unlock are unavailable for SSO/LDAP accounts because the identity provider owns the credential.',
            'Every disable, restore, reset and unlock is documented in the audit trail; via the multi-role dialog one user can hold several roles per study.',
          ],
        },
        {
          id: 'ad-sites', num: '3', title: 'Sites', deutsch: 'Standorte', route: '/sites',
          roles: ['administrator','data-manager'],
          shot: 'administrator/03-sites.png',
          goal: 'Create and manage the sites (centres) under the active study.',
          steps: [
            'Open **Standorte** (Sites) from the home page (`/sites`).',
            'Click **Neuer Standort** (New Site) to open the inline create form.',
            'Fill the required fields — *Name*, *Unique Protocol ID*, *Principal Investigator* — and any optional details, then click create.',
            'For an existing site, use **Entfernen** (Disable) to soft-remove it or **Wiederherstellen** (Restore) to bring it back.',
          ],
          notes: [
            'The active study must be the top-level parent — the screen is hidden when the active study is itself a site. Managing sites is open to Administrator and Data Manager.',
            'Disabling a site keeps its enrolled subjects and visits in the audit trail but makes them unreachable until the site is restored; the confirmation dialog states this.',
          ],
        },
        {
          id: 'ad-build', num: '4', title: 'Build Study', deutsch: 'Studienaufbau', route: '/build-study',
          roles: ['administrator','data-manager'],
          shot: 'administrator/04-build-study.png',
          goal: 'Track the study-setup checklist and drive each setup task to completion.',
          steps: [
            'Open **Studienaufbau** (Build Study) from the home page or side-rail (`/build-study`).',
            'Read the progress card (tasks completed, percentage, site and enrolled-subject counts).',
            'Work through the task tiles — *Create Study*, *CRF Library*, *Event Definitions*, *Sites*, *Group Classes*, *Rules*, *Manage Users* — each showing a status pill. Click **→ Weiter** to jump to that task.',
            'For optional zero-count tasks, click **Als abgeschlossen markieren** (Mark as complete) to acknowledge them.',
            'As Administrator, use the toolbar to change study status: pick a target state and confirm in the **Status wechseln zu …** dialog, supplying a reason when transitioning to LOCKED or FROZEN.',
          ],
          notes: [
            'Build Study is open to Administrator and Data Manager, but the status dropdown, the status-change action and the *Neue Studie* button are Administrator-only. A reason is mandatory for LOCKED and FROZEN transitions and is recorded in the audit trail.',
          ],
        },
        {
          id: 'ad-events', num: '5', title: 'Event Definitions', deutsch: 'Visiten-Definitionen', route: '/event-definitions',
          roles: ['administrator','data-manager'],
          shot: 'administrator/05-event-definitions.png',
          goal: 'Define the visits (events) that make up the study schedule.',
          steps: [
            'From **Studienaufbau**, open the **Event Definitions** task, or go to `/event-definitions`.',
            'Review the list of **Visiten-Definitionen** (Event Definitions).',
            'Add or edit a definition, setting its name, type (e.g. scheduled vs. repeating) and the CRFs attached to it.',
            'Order the definitions to match the clinical visit sequence.',
          ],
          notes: [
            'Open to Administrator and Data Manager. Event definitions are the backbone of the Subject Matrix columns, so changes here are reflected in every subject\u2019s visit list.',
          ],
        },
        {
          id: 'ad-crf', num: '6', title: 'CRF Library (and CRF Builder)', deutsch: 'CRF-Bibliothek', route: '/crf-library',
          roles: ['administrator','data-manager'],
          shot: 'administrator/06-crf-library.png',
          goal: 'Create case report forms, author new versions, and manage version lifecycle.',
          steps: [
            'Open **CRF-Bibliothek** (CRF Library) from Build Study or `/crf-library`.',
            'Tick **Entfernte einschließen** (Include removed) to see soft-deleted CRFs.',
            'Click **Neue CRF** (New CRF) and enter a name and optional description to create the form shell.',
            'On a CRF card, click **Neue Version anlegen** to open the drag-and-drop **CRF Builder** canvas (`/crf-authoring-canvas/<oid>`), where you place sections and items, set data types and labels, then save the version.',
            'Use the version sub-list to **XLS herunterladen**, and Lock / Unlock / Restore / **Entfernen**. *Hard Remove* is system-admin-only and blocked while any event definition or event CRF still references the version.',
          ],
          notes: [
            'Open to Administrator and Data Manager. The drag-and-drop canvas is the sole CRF-authoring surface; the legacy side-rail wizard has been removed.',
            'A version in use cannot be hard-removed — the blocker dialog lists the event definitions and sample subjects holding it.',
          ],
        },
        {
          id: 'ad-rules', num: '7', title: 'Rules', deutsch: 'Regeln', route: '/rules',
          roles: ['administrator','data-manager'],
          shot: 'administrator/07-rules.png',
          goal: 'Inspect, author, test and schedule the study\u2019s edit-check and automation rules.',
          steps: [
            'Open **Regeln** (Rules) from Build Study or `/rules`.',
            'In the left pane, search and select a rule set; its target, attached rules and run schedule appear on the right.',
            'Click **Neue Regel** to open the rule wizard, or **Regeln importieren** to load rules from XML.',
            'For a selected rule set, edit the run schedule, edit individual action messages, and run **Trockentest ausführen** (Dry run) to preview which subjects the rules would fire on.',
            'Use **XML exportieren** to download all rules, and the **Test** sandbox at the bottom to evaluate an expression against sample values.',
          ],
          notes: [
            'Open to Administrator and Data Manager; for other roles the screen is read-only. Rule actions include discrepancy notes, e-mail, notifications, show/hide, insert and randomize. The run log records each fired action with a timestamp.',
          ],
        },
        {
          id: 'ad-groups', num: '8', title: 'Group Classes', deutsch: 'Teilnehmer-Gruppen', route: '/group-classes',
          roles: ['administrator','data-manager'],
          shot: 'administrator/08-group-classes.png',
          goal: 'Define subject groupings (arms, cohorts, families) for the study.',
          steps: [
            'Open **Teilnehmer-Gruppen** (Group Classes) from Build Study or `/group-classes`.',
            'Add a group class, naming it and choosing its type.',
            'Define the group values that subjects can be assigned to.',
          ],
          notes: [
            'Open to Administrator and Data Manager. Group assignment then appears on each subject\u2019s detail page and as a column in the Subject Matrix. This is an optional setup task — it can be acknowledged as complete even with zero groups.',
          ],
        },
        {
          id: 'ad-modalities', num: '9', title: 'Modalitäten', deutsch: 'Modalitäten', route: '/modalities',
          roles: ['administrator'],
          shot: 'administrator/09-modalities.png',
          goal: 'Maintain the platform-wide catalog of measurement modalities and their per-eye item bindings.',
          steps: [
            'Open **Modalitäten** (Modalities) from the home page (`/modalities`).',
            'Click **Neue Modalität** to register one. Supply the stable **code** (fixed after creation), German and English labels, the ordinal, the data type (numeric / categorical), an optional unit (mm, mmHg, …), and the OD and/or OS item OID(s) — at least one is required.',
            'Use **Bearbeiten** to change a modality, or **Entfernen** to retire it.',
          ],
          notes: [
            'This is an **Administrator-only**, platform-wide catalog (not study-scoped). Columns are *Code*, German/English labels, OD and OS item OIDs, type, unit and ordinal.',
            'Removing a modality keeps existing baseline measurements but drops the modality from the per-eye baseline panel; the confirmation dialog states this.',
          ],
        },
        {
          id: 'ad-datasets', num: '10', title: 'Datasets / Data Export', deutsch: 'Datenexport', route: '/datasets',
          roles: ['administrator','data-manager','monitor'],
          shot: 'administrator/10-datasets.png',
          goal: 'Build reusable export datasets and download study data in your chosen format.',
          steps: [
            'Open **Datenexport** (Data Export) from the home page or side-rail (`/datasets`).',
            'For a quick dump, click **Schnell-ODM-Export**. For a tailored extract, click **Neuer Datensatz** and complete the dataset wizard.',
            'In the dataset table, expand **View files**, **Open wizard** to edit a dataset (disabled once it has been run), or **Remove** / **Restore** to manage its lifecycle.',
            'Click **Export now** and pick a format — *odm*, *csv*, *tsv*, *excel*, *sas* or *spss* — then download the generated file.',
          ],
          notes: [
            'Open to Administrator, Data Manager and Monitor. Tick **Entfernte einschließen** to show soft-deleted datasets. A dataset that has already run cannot be edited — clone or create a new one instead.',
          ],
        },
        {
          id: 'ad-import', num: '11', title: 'Import CRF Data', deutsch: 'CRF-Daten importieren', route: '/import-crf-data',
          roles: ['administrator','data-manager'],
          shot: 'administrator/11-import-crf-data.png',
          goal: 'Bulk-load CRF data from an ODM XML file through a guided, four-step wizard.',
          steps: [
            'Open **CRF-Daten importieren** from the home page or side-rail (`/import-crf-data`).',
            '**Hochladen** (Upload): drag an `.xml` file onto the drop zone (or browse), then click **Weiter**.',
            '**Mappen** (Map): check the detected counts of subjects, events, CRFs and rows, then continue.',
            '**Vorschau & Auflösung** (Preview & Resolve): review the status cards (*Ready*, *Overwrite*, *Warning*, *Error*) and the per-row table. Resolve issues, choose an overwrite mode, and — if any rows will be replaced — type a **Reason for change** before continuing.',
            '**Commit**: the system applies the import and shows a summary (rows inserted, overwritten, skipped, discrepancy notes). Use **Start over** to import another file.',
          ],
          notes: [
            'Open to Administrator and Data Manager. The commit step is entered by the system, not clicked. You cannot commit while any row is in error; overwriting in *replace* mode requires a reason, recorded in the audit trail. If the upload token expires before commit, re-upload the file.',
          ],
        },
        {
          id: 'ad-sysaudit', num: '12', title: 'System Audit Log', deutsch: 'System-Audit-Protokoll', route: '/system/audit-log',
          roles: ['administrator'], tall: true,
          shot: 'administrator/12-system-audit-log.png',
          goal: 'Review the institution-wide audit trail, including operation failures and failed jobs.',
          steps: [
            'Go to **System-Audit-Protokoll** at `/system/audit-log`.',
            'Filter by actor, event variant (signed, reason-for-change, sdv, admin, data, query, subject-group-change) or subject.',
            'Browse the date-grouped timeline; click an entry with a chevron to expand its before/after diff and reason.',
          ],
          notes: [
            'This system-wide log is **Administrator-only** and surfaces rows the per-study *Audit Trail der Studie* hides — notably `OPERATION_FAILED` and `JOB_FAILED` entries. It has no study scope and (unlike the per-study audit view) no XLSX export.',
          ],
        },
        {
          id: 'ad-status', num: '13', title: 'System Status', deutsch: 'Systemstatus', route: '/admin/system-status',
          roles: ['administrator'],
          shot: 'administrator/13-system-status.png',
          goal: 'Check the health of the running application after a restart or incident.',
          steps: [
            'Go to **Systemstatus** at `/admin/system-status`.',
            'Read the three panels: **JVM** (Java version, heap, threads, CPUs), **Database** (reachability, product/version, Liquibase changelog count), and **Application** (status OK/OutOfMemory, uptime).',
            'Click **Aktualisieren** (Refresh) to re-poll; the last-refreshed time is shown next to the button.',
          ],
          notes: ['Administrator-only; the backend returns 403 for any other session. This is a read-only diagnostics view.'],
        },
        {
          id: 'ad-password', num: '14', title: 'Password Policy', deutsch: 'Passwort-Richtlinie', route: '/admin/password-policy',
          roles: ['administrator'],
          shot: 'administrator/14-password-policy.png',
          goal: 'Set the password rules enforced for local accounts.',
          steps: [
            'Go to **Passwort-Richtlinie** at `/admin/password-policy`.',
            'Tick the required character classes (lowercase, uppercase, digits, special characters).',
            'Set the length constraints — *Min Length*, *Max Length* (1–256) — and *Expiration Days* (0 = no expiration).',
            'Optionally require a password change on first login, then click **Save** (or **Discard**).',
          ],
          notes: [
            'Administrator-only. On success a *Gespeichert* (Saved) banner appears. The policy applies to local accounts; SSO/LDAP credentials are governed by the identity provider.',
          ],
        },
        {
          id: 'ad-config', num: '15', title: 'App Configuration', deutsch: 'Anwendungskonfiguration', route: '/admin/config',
          roles: ['administrator'],
          shot: 'administrator/15-app-config.png',
          goal: 'See what configuration the running application actually reads.',
          steps: [
            'Go to **Anwendungskonfiguration** at `/admin/config`.',
            'Read the values: default timezone, user language/country, file encoding, OS name/architecture, JVM options, the retinal-inference remote-push URL, and whether SSO is enabled.',
            'Click **Aktualisieren** to re-read after a restart.',
          ],
          notes: [
            'Administrator-only and **read-only** — deployment configuration lives in environment variables, not here. This screen surfaces what the running JVM sees, useful for diagnostics after a restart.',
          ],
        },
        {
          id: 'ad-jobs', num: '16', title: 'Scheduled Jobs', deutsch: 'Geplante Jobs', route: '/admin/jobs',
          roles: ['administrator'],
          shot: 'administrator/16-scheduled-jobs.png',
          goal: 'Inspect the background (Quartz) jobs the platform runs.',
          steps: [
            'Go to **Geplante Jobs** at `/admin/jobs`.',
            'Review the scheduler status bar (name, started/standby) and the jobs table: name, group, state, previous and next fire times, description.',
            'Click **Aktualisieren** to re-poll.',
          ],
          notes: ['Administrator-only and read-only. The state pill is colour-coded (NORMAL green, PAUSED amber, ERROR/BLOCKED red). An empty list shows *Keine Jobs*.'],
        },
        {
          id: 'ad-subject', num: '17', title: 'Subject Detail, Study Identity & Parameters', deutsch: 'Probandendetail · Studien-Identität', route: '/subjects/<id>',
          roles: ['investigator','administrator'],
          shot: 'administrator/20-subject-detail.png',
          goal: 'Inspect a single subject, and edit the active study\u2019s identity and parameters.',
          steps: [
            'From the Subject Matrix, click **Öffnen** on a row to open the subject detail page (`/subjects/<id>`).',
            'Review the **IDENTITÄT** block and the **BESUCHE** (Visits) table. Use **Bearbeiten** to amend identity fields, **CRFs öffnen** to enter data, and the row kebab (⋮) for *Stornieren* (Cancel) or *Signieren* (Sign).',
            'To create a study, open **Neue Studie** from Build Study or go to `/studies/new`.',
            'To change identity, open **Bearbeiten** (`/studies/<oid>/edit`) — name, secondary protocol ID, phase, summary, principal investigator, sponsor, official title and protocol type. Click **Save**.',
            'To change behaviour, open **Parameter** (`/studies/<oid>/parameters`) — subject-ID generation, discrepancy management, interviewer defaults and module toggles. Click **Save**.',
          ],
          notes: [
            'Subject detail is open to Investigator and Administrator. Study creation, identity edit and parameters edit are **Administrator-only** and re-checked on the backend (403 on denial).',
            'Subject identity edits, eye transitions, event cancellations and signatures are all recorded in the audit trail.',
          ],
        },
        {
          id: 'ad-parked', num: '18', title: 'Parked Scans (cross-study retinal jobs)', deutsch: 'Geparkte Scans', route: '/retinal/parked',
          roles: ['administrator'],
          shot: 'administrator/22-parked-scans.png',
          goal: 'Review retinal inference jobs uploaded without a visit and waiting to be assigned to a subject.',
          steps: [
            'Open **Geparkte Scans** (`/retinal/parked`) — a sysadmin-only, cross-study overview.',
            'Each row shows the **Job**, **PatientId**, **Auge** (eye), **Task** and **Hochgeladen** (upload time). Select rows and use the row **Aktion** to bind a parked scan to a subject\u2019s visit, or **Neu laden** to refresh.',
          ],
          notes: ['Administrator-only. Parked jobs have no study-subject linkage yet, which is why they surface here rather than on a per-subject page.'],
        },
      ],
    },

    /* ─────────────────────────────── DATA MANAGER ────────────────────────── */
    {
      id: 'data-manager', role: 'data-manager', kicker: 'Kapitel 02',
      title: 'Data Manager', deutsch: 'Studienleitung',
      oneLiner: 'Builds and runs the study: CRFs, events, rules, groups, discrepancy oversight, data export.',
      intro: [
        'The **Data Manager** builds the study and keeps its data clean. This is the broadest study-level role: you assemble the CRFs, define the visits, attach validation rules, group subjects for analysis, oversee discrepancies, and export the data. Day-to-day data *entry* is the Investigator\u2019s job — as a Data Manager you set the study up, supervise it, and pull the data out.',
      ],
      callout: {
        kind: 'info', title: 'What you can and cannot reach',
        text: 'You can open the **Subject Matrix** to see enrolment and visit progress, but the per-subject detail screen and direct CRF data entry are Investigator/Administrator screens. Creating users, editing the study\u2019s core identity, and locking/unlocking individual visits or CRF versions are Administrator actions. Your supervision powers — discrepancies, audit log, rules, export, double-data-entry reconciliation — are all available.',
      },
      sections: [
        {
          id: 'dm-home', num: '1', title: 'Home', deutsch: 'Start', route: '/',
          roles: ['data-manager'],
          shot: 'data-manager/00-home.png',
          goal: 'See open discrepancies and jump to your common tasks.',
          steps: [
            'After login (and picking a study) you land on the home dashboard.',
            'Review the summary of notes and discrepancies assigned to you.',
            'Use the side navigation or the dashboard links to start a task — most build work begins at **Build Study**.',
          ],
          notes: [
            'The top bar shows the active study/site, your name with a colour-coded **role chip**, the language indicator, and **Log out**.',
          ],
        },
        {
          id: 'dm-matrix', num: '2', title: 'Subject Matrix', deutsch: 'Studienteilnehmer', route: '/subjects',
          roles: ['investigator','monitor','data-manager','administrator'],
          shot: 'data-manager/01-subject-matrix.png',
          goal: 'Review enrolment and per-visit status across all subjects in the study.',
          steps: [
            'Open **Subject Matrix** from the side navigation.',
            'Each row is a subject; columns show **Gender**, study **Eye** (OD/OS/OU), **Group**, **Enrolled** date, one cell per visit, and a **signed** indicator.',
            'Filter with the search box or the status chips, or tick **only with queries** to find subjects carrying open discrepancies.',
            'For studies with many visits, use the chevron buttons or **Jump to latest** to scroll the visit columns; the Subject column stays frozen on the left.',
            'Open **Studien-Statistik** (study metrics) from the side rail for aggregate counts.',
          ],
          notes: [
            'A red badge on a visit cell is the count of **open queries** on that visit — your cue to follow up in Notes & Discrepancies.',
            'The subject link and **Öffnen** action point at the per-subject casebook, which is an **Investigator/Administrator** screen. As a Data Manager you use the matrix to *monitor* progress; you do not enter or sign data here.',
          ],
        },
        {
          id: 'dm-build', num: '3', title: 'Build Study', deutsch: 'Studienaufbau', route: '/build-study',
          roles: ['administrator','data-manager'],
          shot: 'data-manager/02-build-study.png',
          goal: 'Track and complete the tasks that make a study usable.',
          steps: [
            'Open **Build Study**. The header shows a **progress** card plus **Sites** and **enrolled subjects** counts.',
            'Work down the numbered task list — **CRFs**, **Event Definitions**, **Group Classes**, **Rules** (and, for Administrators, Create Study / Sites / Users). Each card shows a status pill and a one-line summary.',
            'Click **Next →** on a task to jump to the screen that completes it.',
            'For the optional tasks that legitimately have nothing to add, use **Als abgeschlossen markieren** to acknowledge them.',
          ],
          notes: [
            'The **Set Study Status** dropdown and the **Edit study** / **Study parameters** / **Create study** buttons are **Administrator-only**. A locking/freezing transition prompts for a reason (captured in the audit trail).',
            'The "Create Study" task shows a view-only hint for non-Administrators; the rest of the tracker is yours.',
          ],
        },
        {
          id: 'dm-events', num: '4', title: 'Event Definitions', deutsch: 'Visiten-Definitionen', route: '/event-definitions',
          roles: ['administrator','data-manager'],
          shot: 'data-manager/03-event-definitions.png',
          goal: 'Define the study\u2019s visits and what each visit collects.',
          steps: [
            'Open **Event Definitions** (or **Next →** from the Build Study events task).',
            'Click **Create** to add a visit: enter a **name**, choose a **type** (*scheduled* / *unscheduled* / *common*), optionally a **category** and **description**, and tick **repeating** if the visit can occur more than once.',
            'Reorder visits with the **↑ / ↓** arrows; the order sets how visits appear to data-entry staff.',
            'Use **Manage CRFs** to attach CRFs to the visit. The CRF-assignment dialog is where the **SDV requirement** per CRF is set — the value Monitors later act on.',
            'For OCT-imaging visits, the edit form exposes a **retinal-inference task** panel (*fluid*, *ga*, *onl*, *pr*, *layers*) — pick which automated jobs run when a scan is committed against that visit.',
            '**Disable** removes a visit; toggle **show removed** to **restore** one.',
          ],
          notes: [
            'Create, edit, reorder, disable, restore, and CRF assignment are available to Data Managers. **Lock / Unlock** of a visit definition is Administrator-only.',
            'Repeating + type semantics drive how the Subject Matrix and scheduling behave — set them deliberately.',
          ],
        },
        {
          id: 'dm-crf', num: '5', title: 'CRF Library + CRF Builder', deutsch: 'CRF-Bibliothek', route: '/crf-library',
          roles: ['administrator','data-manager'],
          shot: 'data-manager/04-crf-library.png',
          goal: 'Create case report forms and author their versions.',
          steps: [
            'Open **CRF Library**. Each card is a CRF with its versions listed inline (name, OID, status).',
            'Click **Neuen CRF anlegen** to add a CRF shell — give it a **name** and optional **description**.',
            'To author a form version, use **manuell anlegen** on the CRF card. This opens the **CRF Builder** canvas (`/crf-authoring-canvas/:crfOid`): a three-column drag-and-drop editor — item palette, section canvas, per-item properties. **Vorschau** renders the form; save writes a new version.',
            'To start from an earlier version, use the chevron beside **manuell anlegen** and pick **fork from** that version.',
            'Per version you can **Download** the Excel (.xls), and **lock / unlock**, **disable / restore** the version.',
            'Tick **entfernte anzeigen** to see and restore disabled CRFs.',
          ],
          notes: [
            'The drag-and-drop **canvas** is the primary authoring surface; the legacy Excel upload still exists for sponsor-workbook round-trips but is no longer the front-line path.',
            '**Hard-remove** of a version is **Administrator-only**; if a version is still referenced by event definitions or entered data, the SPA shows a blocker report instead of deleting.',
          ],
        },
        {
          id: 'dm-rules', num: '6', title: 'Rules', deutsch: 'Regeln', route: '/rules',
          roles: ['administrator','data-manager'],
          shot: 'data-manager/05-rules.png',
          goal: 'Attach validation and automation rules, and test them before they run.',
          steps: [
            'Open **Rules**. The left list shows each rule target; selecting one opens its detail pane (target, event definition, CRF/version, attached rules, run log).',
            'Click **Neue Regel** to author a rule with the 3-step wizard (rule body → target + scope → action), or **Regeln importieren** to upload a rule-definition XML.',
            'In the detail pane, edit a rule or its action inline, and set the **run schedule** (a daily batch time) via the schedule editor.',
            'Use **Probelauf** (dry run) to preview which subjects/actions a rule would hit without persisting anything, and **als XML exportieren** to download the selected rules.',
            'Use the **Test** panel to evaluate a raw expression against ad-hoc key/value test inputs.',
          ],
          notes: [
            'Create / import / edit / disable / restore / schedule / dry-run / export are available to Data Managers and Administrators.',
            'Rules can file discrepancy notes automatically — those then show up in **Notes & Discrepancies** for follow-up.',
          ],
        },
        {
          id: 'dm-groups', num: '7', title: 'Group Classes', deutsch: 'Teilnehmer-Gruppen', route: '/group-classes',
          roles: ['administrator','data-manager'],
          shot: 'data-manager/06-group-classes.png',
          goal: 'Group subjects for analysis (arms, cohorts, families).',
          steps: [
            'Open **Group Classes**. Each row is a group class with its child groups shown as chips.',
            'Click **Create** to add one: enter a **name**, pick a **type** (*Arm* / *Family* / *Demographic* / *Other*) and a **subject assignment** (*required* / *optional*).',
            'Add child **groups** in the rows beneath; add more with **Gruppe hinzufügen** and remove blank rows with **×**.',
            '**Disable** removes a group class; **restore** brings it back.',
          ],
          notes: [
            'When a **required** group class exists, enrolment must assign each subject to a group, and the Subject Matrix can be read by group.',
          ],
        },
        {
          id: 'dm-notes', num: '8', title: 'Notes & Discrepancies', deutsch: 'Rückfragen & Diskrepanzen', route: '/notes',
          roles: ['data-manager','monitor','administrator'],
          shot: 'data-manager/07-notes-discrepancies.png',
          goal: 'Oversee and resolve data queries across the study.',
          steps: [
            'Open **Notes & Discrepancies**. Summary cards count open notes by type: **query**, **failed validation**, **annotation**, and **reason for change**.',
            'Filter by status (default **open only**), by type, by free text, or tick **assigned to me**.',
            'Expand a row (chevron) to read the full thread; the **item** link deep-links to the CRF row, and the row shows the current value in context.',
            'Act on a note with **Respond**, **Mark resolved**, or **Close** — each opens an inline composer for the required comment (Close may be wordless).',
            '**Export CSV** writes the currently filtered list for offline review.',
          ],
          notes: [
            'Which actions you see depends on the note\u2019s status and your role; as a Data Manager you have broad close/resolve authority for oversight.',
            'Every response and status change is recorded in the audit trail.',
          ],
        },
        {
          id: 'dm-audit', num: '9', title: 'Study Audit Log', deutsch: 'Audit Trail der Studie', route: '/audit-log',
          roles: ['data-manager','monitor','administrator'], tall: true,
          shot: 'data-manager/08-study-audit-log.png',
          goal: 'Review who changed what, when, and why.',
          steps: [
            'Open **Study Audit Log**. Events are grouped by date (Today / Yesterday / DD.MM.YYYY) on a timeline.',
            'Filter by **actor**, by **type** (signed, reason-for-change, SDV, admin, data, query, subject-group-change), or by **subject**.',
            'Expand an event to see the **before / after** diff and any reason-for-change note.',
            '**Export XLSX** writes the filtered view for a compliance file.',
          ],
          notes: [
            'Actor role chips are colour-coded (Investigator / Monitor / Data Manager) so you can scan who acted at a glance.',
            'The audit log is read-only — it is the system of record, not an editing surface.',
          ],
        },
        {
          id: 'dm-datasets', num: '10', title: 'Datasets / Data Export', deutsch: 'Datenexport', route: '/datasets',
          roles: ['data-manager','monitor','administrator'],
          shot: 'data-manager/09-datasets.png',
          goal: 'Extract the study\u2019s data in the format your analysis needs.',
          steps: [
            'Open **Datasets** (Data Export). The table lists saved datasets with owner, created date, last run, and file count.',
            'For a quick full export, use **Schnell-ODM** (Quick ODM) in the header.',
            'For a saved dataset, **Export now** opens a format picker — **ODM**, **CSV**, **TSV**, **Excel**, **SAS**, or **SPSS** — and downloads the result.',
            '**View files** expands a sub-row listing every generated file with size, timestamp, and a **download** link.',
            '**Remove** soft-deletes a dataset; tick **show removed** to **restore** it.',
          ],
          notes: [
            'Editing a dataset is blocked once it has been run (to preserve a reproducible extract); create a new one instead.',
          ],
        },
        {
          id: 'dm-create-dataset', num: '11', title: 'Create Dataset (wizard)', deutsch: 'Neues Dataset', route: '/datasets/new',
          roles: ['data-manager','administrator'],
          shot: 'data-manager/10-create-dataset.png',
          goal: 'Define exactly which events, CRFs, and items an export contains.',
          steps: [
            'From **Datasets**, click **Neues Dataset** to open the create wizard at `/datasets/new`.',
            'Step through the wizard to name the dataset and choose its scope — the events, CRFs, and items to include, plus any date/status filters.',
            'Save the dataset; it then appears in the Datasets table, where you run it in whichever format you need.',
          ],
          notes: [
            'A saved dataset is reusable — define the scope once, export it repeatedly as the study accrues data. Editing is only possible before the dataset\u2019s first run.',
          ],
        },
        {
          id: 'dm-import', num: '12', title: 'Import CRF Data', deutsch: 'CRF-Daten importieren', route: '/import-crf-data',
          roles: ['administrator','data-manager'],
          shot: 'data-manager/11-import-crf-data.png',
          goal: 'Load data into the study from a CDISC ODM XML file.',
          steps: [
            'Open **Import CRF Data**. The wizard has four steps: **Upload → Map → Preview → Commit**.',
            '**Upload** the ODM `.xml` file (drag-and-drop or pick).',
            '**Map** shows the detected counts — subjects, events, CRFs, and rows.',
            '**Preview** classifies every row as **ready**, **overwrite**, **warning**, or **error**, with a before/after diff. Choose **replace** or **skip**; replacing requires a **reason for change**.',
            'You cannot commit while any row is in **error**. **Commit** writes the import and reports rows inserted / overwritten / skipped and discrepancy notes created.',
          ],
          notes: [
            'Overwriting values via import is audited like any other change — the reason you enter is stored with each overwrite. The upload token can expire; if it does, re-upload and start over.',
          ],
        },
        {
          id: 'dm-dde', num: '13', title: 'Double-Data-Entry (DDE) Reconciliation', deutsch: 'DDE-Abgleich', route: '/event-crfs/:oid/dde-reconcile',
          roles: ['data-manager','administrator','investigator'],
          goal: 'Reconcile a CRF that was entered twice, picking the canonical value per item.',
          steps: [
            'DDE reconciliation opens for a specific event CRF at `/event-crfs/:eventCrfOid/dde-reconcile`.',
            'The screen lists each conflicting item side by side — the **initial** entry versus the **double** entry.',
            'For each conflict, select the **winning** value (or type a manual value) and enter a **reason for change**.',
            '**Apply** the resolution per row; the running **open count** drops and a confirmation appears when the CRF is fully reconciled.',
          ],
          notes: [
            'Reachable by **Data Manager**, **Administrator**, and **Investigator**; the backend is the authoritative gate.',
            'Every resolution is captured in the audit trail with its reason — DDE is a data-quality control, so treat the reason field as part of the record.',
          ],
        },
      ],
    },

    /* ─────────────────────────────── MONITOR ─────────────────────────────── */
    {
      id: 'monitor', role: 'monitor', kicker: 'Kapitel 03',
      title: 'Monitor', deutsch: 'Monitor',
      oneLiner: 'Source data verification, discrepancy notes/queries, read-only review, audit log.',
      intro: [
        'The **Monitor** is the study\u2019s read-only oversight role. A Monitor reviews data that other roles have entered, verifies it against source documents (Source Data Verification, *Quelldatenvergleich*), raises and closes discrepancy notes (queries / *Rückfragen*), and consults the immutable audit trail. A Monitor **cannot enter or change clinical data** — every CRF a Monitor opens is read-only.',
      ],
      callout: {
        kind: 'accent', title: 'Two powers are the Monitor\u2019s alone',
        text: '**Source Data Verification** — marking CRFs as verified (SDV) and taking that mark back when the underlying data changes. **Closing a discrepancy** — only a Monitor (or a Data Manager / Administrator override) can move a query to its final *Geschlossen* (Closed) state.',
      },
      sections: [
        {
          id: 'mo-home', num: '1', title: 'Home dashboard', deutsch: 'Start', route: '/',
          roles: ['monitor'],
          shot: 'monitor/00-home.png',
          goal: 'See what needs your attention and jump into your most common Monitor tasks.',
          steps: [
            'After signing in (and picking a study) you land on the **home dashboard**.',
            'Read the attention summary — notes and discrepancies assigned to you and the count of CRFs still awaiting verification.',
            'Use the side navigation or the dashboard\u2019s quick links to open **Quelldatenvergleich**, **Rückfragen & Diskrepanzen**, or the **Audit Trail**.',
          ],
          notes: [
            'You can return here at any time with **Start** (Home). The dashboard is read-only; it links into the working screens rather than letting you act on data directly.',
          ],
        },
        {
          id: 'mo-matrix', num: '2', title: 'Subject Matrix', deutsch: 'Studienteilnehmer', route: '/subjects',
          roles: ['investigator','monitor','data-manager','administrator'],
          shot: 'monitor/01-subject-matrix.png',
          goal: 'Find a subject and open their visits and CRFs for visual review.',
          steps: [
            'Open **Studienteilnehmer** from the side navigation.',
            'Use the search box (*Teilnehmer per ID suchen…*) or the filter chips — **Alle**, **Mit offenen Visiten**, **Alle Visiten abgeschlossen**, **Signiert**, and the **Nur mit offenen Rückfragen** checkbox.',
            'Each row shows subject ID, gender, study eye, group, enrolment date, and a status pill per visit. A small red badge next to a visit pill counts the open queries on it.',
            'Click the subject ID, or **Öffnen** on the right, to drill into the subject detail and from there into individual visits and CRFs.',
          ],
          notes: [
            'For the Monitor this screen is a **read-only lookup tool** — there is no pencil / data-entry icon and no *Teilnehmer aufnehmen* (Add Subject) action.',
            'The visit columns scroll horizontally; use the chevron buttons or **Letzte Visite** to slide through long visit timelines while the Subject and action columns stay frozen.',
          ],
        },
        {
          id: 'mo-sdv', num: '3', title: 'Source Data Verification (SDV)', deutsch: 'Quelldatenvergleich', route: '/sdv',
          roles: ['monitor'],
          shot: 'monitor/02-sdv.png',
          goal: 'Confirm that the data entered in the app matches the source documents, then mark each verified CRF as Verifiziert. This is the core Monitor workflow.',
          steps: [
            'Open **Quelldatenvergleich** (`/sdv`). The table lists every CRF ready for verification, one row per CRF, with subject, site, visit, visit date, CRF name, **Anforderung** (SDV requirement), and **Status**.',
            'Narrow with the filter row: the search box, the **status** dropdown (**Alle Status**, **Ausstehend**, **Rückfrage**, **Verifiziert**, **Gesperrt**), the **requirement** dropdown (**100 % erforderlich**, **Teilweise erforderlich**, **Nicht erforderlich**), and the **Nur mit offenen Rückfragen** checkbox. Use **Zurücksetzen** to clear.',
            'Open a CRF to compare it against the source: click **CRF öffnen**. The CRF opens **read-only** — inputs are shown but cannot be saved.',
            'When a CRF matches the source, mark it verified: one row via its checkbox + the bulk bar, or several at once via the header checkbox then **… als verifiziert markieren (SDV)**. A confirmation dialog shows the count.',
            'Confirm in the **Als verifiziert markieren?** dialog. The action is recorded in the audit trail with your username and a timestamp.',
          ],
          notes: [
            '**Only CRFs that are *Ausstehend* (Pending) can be selected** for verification; the checkbox is disabled on rows already verified, locked, or carrying an open query.',
            '**The SDV requirement is display-only here.** Whether a CRF needs no, partial, or 100% verification is set by the Data Manager during study build.',
            '**Verification can be taken back.** On a *Verifiziert* row, use **Verifizierung zurücknehmen** — you must supply a **Begründung** (reason); the un-verify is documented and the CRF returns to the pending queue.',
            '**Automatic revert:** if data on a verified CRF is later changed, its SDV status flips back to **Ausstehend** by itself.',
          ],
          sub: {
            title: '3.1 Read-only CRF review',
            text: 'While verifying, you open each CRF read-only at `/event-crfs/:eventCrfOid/readonly` (from **CRF öffnen** on an SDV row, or a discrepancy\u2019s item link). The CRF renders exactly as it does for data entry — header info, section tabs, and items — but there is **no Save action**. This is the screen on which you compare each item against the source document and, where you find a mismatch, raise a query.',
          },
        },
        {
          id: 'mo-notes', num: '4', title: 'Notes & Discrepancies (queries)', deutsch: 'Rückfragen & Diskrepanzen', route: '/notes',
          roles: ['data-manager','monitor','administrator'],
          shot: 'monitor/03-notes-discrepancies.png',
          goal: 'Raise a query when entered data does not match the source, follow the back-and-forth, and close the query once resolved.',
          steps: [
            'Open **Rückfragen & Diskrepanzen** (`/notes`). The summary cards count open items by type: **Rückfrage** (Query), **Fehlgeschlagene Validierung**, **Notiz**, and **Änderungsgrund**.',
            'Filter with the search box, the **status** dropdown (**Nur offen** by default, plus **Neu**, **Aktualisiert**, **Lösung vorgeschlagen**, **Geschlossen**, **Nicht zutreffend**), the **type** dropdown, and the **Mir zugewiesen** checkbox.',
            '**Raise a query.** During SDV review, open the CRF read-only and use **Rückfrage stellen** on the relevant item. In the **Anmerkung hinzufügen** dialog the type is fixed to **Rückfrage** for a Monitor; enter a **Beschreibung**, then **Rückfrage absenden**.',
            '**Follow the thread.** Click the chevron on any row to expand its history. The data-entry user responds (**Antworten**) and proposes a resolution (**Als gelöst markieren** → *Lösung vorgeschlagen*).',
            '**Close the query.** Once you have re-checked the CRF and the value is correct, click **Schließen** on a *Lösung vorgeschlagen* row. A closing comment is optional. The query moves to **Geschlossen** — its final state.',
          ],
          notes: [
            '**Lifecycle (Monitor\u2019s view):** **Neu** → **Aktualisiert** → **Lösung vorgeschlagen** → **Geschlossen**. A note is never deleted — only its status changes.',
            '**Who does what:** the Monitor *raises* and *closes*; the **Antworten** and **Als gelöst markieren** buttons are shown to the Investigator / CRC. **Schließen** appears only on rows at *Lösung vorgeschlagen*.',
            '**Type is fixed for the Monitor** — a Monitor creates **Rückfrage** notes. A Monitor sees **all** discrepancies in the study; use **Mir zugewiesen** to focus. Use **CSV exportieren** to download the filtered list.',
          ],
        },
        {
          id: 'mo-audit', num: '5', title: 'Study Audit Log', deutsch: 'Audit Trail der Studie', route: '/audit-log',
          roles: ['data-manager','monitor','administrator'], tall: true,
          shot: 'monitor/04-study-audit-log.png',
          goal: 'Review the complete, immutable history of changes — for ongoing oversight and inspector-readiness during sponsor audits.',
          steps: [
            'Open **Audit Trail** (`/audit-log`).',
            'Entries appear on a timeline grouped by date (**Heute**, **Gestern**, then explicit dates). Each row names the action, the subject, the scope, the actor and their role, and the time.',
            'Narrow with the filter row: **Akteur/-in**, **Ereignistyp** (**Signatur**, **Änderungsgrund**, **SDV**, **Administration**, **Datenerfassung**, **Rückfrage**, **Gruppenwechsel**), and **Teilnehmer**. Use **Zurücksetzen** to reset.',
            'Click a row with a chevron to expand it and reveal old value / new value side by side, plus any reason note.',
          ],
          notes: [
            'The audit trail is **read-only and immutable** — filters only narrow the view. Available to Monitor, Data Manager, and Administrator; Investigators do not see it.',
            'Use **XLSX exportieren** to download the filtered view for off-line review or to hand to a sponsor.',
          ],
        },
        {
          id: 'mo-datasets', num: '6', title: 'Datasets / Data Export', deutsch: 'Datenexport', route: '/datasets',
          roles: ['data-manager','monitor','administrator'],
          shot: 'monitor/05-datasets.png',
          goal: 'Run a saved data export for the active study and download the generated files.',
          steps: [
            'Open **Datenexport** (`/datasets` or `/export`). The table lists the datasets saved for the active study, with owner, creation date, last run, and file count.',
            'For a quick, full-study export, use **Schnell-ODM-Export**.',
            'To run a saved dataset, click **Jetzt exportieren**, pick a format (**ODM (CDISC XML)**, **CSV**, **TSV**, **Excel**, **SAS**, **SPSS**), and start the export.',
            'Click **Dateien anzeigen** on a row to expand its generated files and download an earlier run with **Herunterladen**.',
          ],
          notes: [
            'A Monitor can run and download exports, including datasets created by other users in the study (cross-user visibility is intentional for monitoring).',
            'New datasets are defined through the classic **Extract Data** wizard; once saved they appear in this list and can be re-run from the SPA.',
          ],
        },
      ],
    },

    /* ─────────────────────────────── INVESTIGATOR ────────────────────────── */
    {
      id: 'investigator', role: 'investigator', kicker: 'Kapitel 04',
      title: 'Investigator', deutsch: 'Prüfarzt/-ärztin',
      oneLiner: 'Enrols subjects, enters CRF data, signs casebooks, reviews retinal results.',
      intro: [
        'As an **Investigator** you are responsible for the clinical record of each study participant: you enrol subjects, schedule their visits, enter the eye-examination data on the CRFs, review the automated retinal scan results, and finally sign the participant\u2019s casebook to confirm the record is complete and accurate.',
      ],
      callout: {
        kind: 'accent', title: 'Ophthalmology shorthand',
        text: '**OD** = right eye, **OS** = left eye, **OU** = both eyes. On forms that capture both eyes, the **OD (right-eye) column is on the LEFT** and the **OS (left-eye) column is on the RIGHT** — mirroring the clinician\u2019s view when sitting face-to-face with the patient.',
      },
      sections: [
        {
          id: 'inv-home', num: '1', title: 'Home', deutsch: 'Start', route: '/',
          roles: ['investigator'],
          shot: 'investigator/00-home.png',
          goal: 'Get an overview of your study and jump into your daily work.',
          steps: [
            'After signing in you land on the **Start** (Home) screen at `/`.',
            'Use the operator cards to jump straight to filtered work lists — for example *Heute* (today\u2019s open visits) and *Signaturfreigabe* (subjects ready to sign) deep-link into the Subject Matrix with that filter pre-applied.',
            'Use the side-rail to reach the Subject Matrix or the Add Subject form at any time.',
          ],
        },
        {
          id: 'inv-matrix', num: '2', title: 'Subject Matrix', deutsch: 'Studienteilnehmer', route: '/subjects',
          roles: ['investigator','monitor','data-manager','administrator'],
          shot: 'investigator/01-subject-matrix.png',
          goal: 'Find a participant and see, at a glance, the status of every visit.',
          steps: [
            'Open **Studienteilnehmer** (`/subjects`).',
            'Each row is one participant. The first columns show **Subject-ID**, **Geschlecht**, **Studienauge** (OD/OS/OU), **Group** and **Aufnahmedatum**. The remaining columns are one per scheduled visit, each a colour-coded status pill; a red badge counts open queries.',
            'Search by ID, or narrow with the filter chips (*Heute*, *Signaturfreigabe*, open-events, all-complete, *Signiert*). Tick *only with queries* to show participants with open discrepancies. *Export* downloads the matrix.',
            'The Subject column (left) and the action column (right) stay frozen while the visit columns scroll. Use the chevron buttons or *Zur aktuellsten Visite*.',
            'Click the **Subject-ID** link or **Öffnen** to open a participant\u2019s casebook.',
          ],
          notes: [
            'The matrix opens scrolled to the left so you see the participant identity columns first.',
            'A green **Signiert** (Signed) pill in the last column means the casebook has already been electronically signed.',
          ],
        },
        {
          id: 'inv-add', num: '3', title: 'Add Subject (enrolment)', deutsch: 'Teilnehmer aufnehmen', route: '/subjects/new',
          roles: ['investigator','crc'],
          shot: 'investigator/02-add-subject.png',
          shot2: 'investigator/02b-add-subject-filled.png',
          shot2Caption: 'The enrolment form filled with sample data, before saving.',
          goal: 'Enrol a new study participant.',
          steps: [
            'Open **Teilnehmer aufnehmen** (`/subjects/new`), from the side-rail or the button on the Subject Matrix.',
            'Fill in **Identifikation**: **Studien-Teilnehmer-ID** (required; may be pre-filled with the protocol short-code, e.g. `GA-…`) and the optional **Sekundär-ID**. *Never put identifying data — no name, no hospital ID, no social-security number — in the Secondary ID.*',
            'Fill in **Aufnahme**: **Aufnahmedatum** (defaults to today, cannot be in the future) and **Geschlecht** (pick one of the four buttons).',
            'In **Ophthalmology**, optionally set the **Studienauge** (*nicht gesetzt* / OD / OS / OU) and a **Screening-Datum**. The study eye drives the eye column in the matrix and how the eye-examination CRFs render.',
            'Save with one of three buttons: **Speichern & nächste/n Teilnehmer/in** (clears for next), **Speichern & Abschluss** (returns to matrix), or **Speichern & erste Visite planen** (opens the casebook to schedule a visit).',
          ],
          notes: [
            'The Subject-ID is checked for availability as you type; an "already taken" message appears inline before you submit.',
            'If the server rejects the entry (e.g. a duplicate ID at this site), the specific field is flagged in red — correct it and save again.',
          ],
        },
        {
          id: 'inv-casebook', num: '4', title: 'Subject casebook (events, CRFs, retinal trends)', deutsch: 'Probandendetail', route: '/subjects/:id',
          roles: ['investigator','administrator'],
          shot: 'investigator/20-subject-detail.png',
          goal: 'Work with one participant — review/edit identity, schedule visits, open CRFs, and review retinal results.',
          steps: [
            'From the Subject Matrix, open a participant (`/subjects/:id`).',
            'The header shows the Subject-ID and status pills (**Signiert** / *Nicht signiert* / *Gesperrt*).',
            'The **identity / enrolment card** lists demographics and the per-eye study assignment (OD and OS rows). Click **Bearbeiten** to amend Secondary ID, gender, year of birth or study eye; the Subject-ID and enrolment date stay read-only.',
            'The **Geplante Visiten / Events** panel lists every scheduled visit with its date, status, data-entry stage and open-query count.',
            'To open a visit, click **CRFs öffnen** on its row. The **⋮** overflow menu offers *Bearbeiten*, *Stornieren* and per-visit *signieren* where your role and the visit\u2019s status allow.',
            'If the participant has automated scan results, a **Retinal-Verlauf** section appears below the visits.',
          ],
          notes: [
            'Editing a previously **completed** visit requires an explicit *Bearbeiten* confirmation first — completed visits are read-only by default.',
            'Eye-transition banners appear here when a participant\u2019s eye has been moved to or from another study; *In andere Studie verlagern* on an eye row starts that workflow.',
          ],
          subsections: [
            {
              title: '4a · Schedule / open an event', deutsch: 'Visite planen',
              goal: 'Add a study visit so data entry can begin.',
              steps: [
                'On the subject casebook, click **Visite planen** (Schedule Event) in the events panel header.',
                'Pick the event definition, location and start date, then confirm. The new visit appears in the events table.',
                'Click **CRFs öffnen** on the visit row to open the event detail page (`/events/:id`), listing every CRF with its version, status, whether it is required (`*`), and an action.',
                'For a CRF not yet started, click **Datenerfassung starten**; for one in progress, click **CRF öffnen**.',
              ],
              notes: [
                'A visit must be scheduled before you can enter any CRF data for it.',
                'Once data entry on every required CRF is finished, use **Visite abschließen** on the event page to move the whole visit to *Abgeschlossen* — an explicit step you control, not an automatic cascade.',
              ],
            },
            {
              title: '4b · Enter CRF data', deutsch: 'Datenerfassung',
              goal: 'Record the eye-examination findings on a case report form.',
              steps: [
                'Open a CRF from the event detail page (`/event-crfs/:eventCrfOid`).',
                'Use the section rail on the left to move between sections; each required item shows a red asterisk.',
                'For **bilateral items**, the row has three columns — **OD / Rechtes Auge** on the **left**, **OS / Linkes Auge** on the **right**, with the label in the middle. Enter each eye\u2019s value in its column.',
                'Click **Entwurf speichern** (Save Draft) to save and continue later.',
                'When finished and required fields are filled, click **CRF als abgeschlossen markieren** (Mark CRF Complete).',
              ],
              notes: [
                '**Save vs. Mark complete:** *Entwurf speichern* persists what you entered but keeps the CRF editable; *als abgeschlossen markieren* validates and moves it to completed.',
                'A completed CRF is **read-only**. To change a value, click **CRF erneut öffnen** (Reopen) — a regulated action that asks for confirmation. A CRF on a signed or locked subject cannot be edited. **Drucken** produces a print-friendly version.',
              ],
            },
            {
              title: '4c · Sign the casebook', deutsch: 'Teilnehmer signieren',
              goal: 'Apply your electronic signature to a participant\u2019s complete record.',
              steps: [
                'Open the participant and go to **Teilnehmer … signieren** (`/subjects/:id/sign`).',
                'The **Pre-Flight-Prüfungen** panel lists conditions that must hold before signing; any blocking failure keeps the submit button disabled.',
                'Review the **Casebook des Teilnehmers — zu bestätigen** table (every visit, status, open-query count). *PDF-Vorschau* opens the casebook as a PDF.',
                'Read the attestation statement, tick the acknowledgement, enter your **password**, and click **Teilnehmer `<ID>` signieren**.',
              ],
              notes: [
                'Your electronic signature is the legally binding equivalent of your handwritten signature: it confirms that the CRFs are a full, accurate and complete record. You must re-enter your password for each subject you sign.',
                'If data on a signed subject is later changed, the affected visit drops back from *Signiert* to *Completed* and must be signed again.',
              ],
            },
          ],
        },
        {
          id: 'inv-retinal', num: '5', title: 'Review retinal scan metrics', deutsch: 'Netzhaut-Auswertung', route: '/retinal-jobs/:id',
          roles: ['investigator'],
          shot: 'investigator/21-retinal-viewer.png',
          shotCaption: 'KPI tiles, en-face fundus with ETDRS-ring overlay, and the B-scan navigator with the segmentation overlay.',
          goal: 'Review the results of the automated OCT inference pipeline (fluid volumes, GA area, retinal thickness) for one scan.',
          steps: [
            'Open a retinal job — from the **Retinal-Verlauf** section of a participant or directly at `/retinal-jobs/:id`.',
            'The header (**Netzhaut-Auswertung**) shows the **laterality** (OD / OS), the analysis task, the job status, the model version, the run time and a **Konfidenz** (confidence) bar.',
            'Read the KPI tiles. For a fluid analysis these are **IRF**, **SRF**, **PED** and **Gesamt** fluid volumes in mm³; a GA analysis shows the **GA-Fläche**; thickness tasks show **ONL** / **PR** thickness.',
            'The **fundus (en-face) overlay** shows the ETDRS rings (central 1 / 3 / 6 mm) over the scan; the accompanying table breaks the metric down per ring.',
            'The **B-scan viewer** lets you scrub through the individual OCT slices with the segmentation overlay.',
          ],
          notes: [
            'Results come from an automated inference pipeline — treat them as a reading aid, not a substitute for clinical review.',
            'While a job is still running you see a live status indicator and in-flight messages (queued / preprocessing / segmenting); the view refreshes as each stage completes.',
            '**Erneut versuchen** re-dispatches a failed job; **Anderen Task ausführen** re-analyses the same scan under another task (fluid, GA, ONL, PR, layers).',
          ],
        },
        {
          id: 'inv-trends', num: '6', title: 'Retinal trends on the casebook', deutsch: 'Retinal-Verlauf', route: '/subjects/:id#retinal',
          roles: ['investigator'],
          goal: 'Track a participant\u2019s biomarkers across visits.',
          steps: [
            'On the subject casebook, scroll to the **Retinal-Verlauf** section — it appears only when the participant has at least one retinal job.',
            'Pick a task with the **Aufgabe** selector — *Flüssigkeit (IRF / SRF / PED)*, *GA-Fläche*, *ONL-Dicke* or *PR-Dicke* — to drive the trend chart.',
            'Below the chart, the **history table** lists every scan job with acquisition date, task, eye, status and primary metric. Click a column header to sort; click the view link on a row to open that job\u2019s full metrics.',
          ],
        },
      ],
    },

    /* ─────────────────────────────── CRC ─────────────────────────────────── */
    {
      id: 'crc', role: 'crc', kicker: 'Kapitel 05',
      title: 'CRC (Clinical Research Coordinator)', deutsch: 'Koordinator/-in',
      oneLiner: 'Day-to-day data entry; inherits the Investigator surface — but does not sign casebooks.',
      intro: [
        'The **CRC** (Clinical Research Coordinator) is the day-to-day data-entry role: enrolling new subjects, opening their scheduled visits, and keying CRF data — including paired right/left-eye (OD/OS) ophthalmology items.',
        'In LibreClinicaMUW the CRC **inherits the Investigator surface**. There is no separate, large CRC menu: the screens you use are the Investigator screens — the Subject Matrix, Add Subject, the subject casebook, the event detail, and the CRF entry form.',
      ],
      callout: {
        kind: 'warn', title: 'One thing the CRC does not do: sign casebooks',
        text: 'Applying the electronic signature to a subject\u2019s casebook is the investigator\u2019s attestation and is keyed with the signing investigator\u2019s own password. You prepare the data so it is complete and ready; the investigator signs. See the Investigator chapter for the signing workflow.',
      },
      sections: [
        {
          id: 'crc-matrix', num: '1', title: 'Find a subject (Subject Matrix)', deutsch: 'Studienteilnehmer', route: '/subjects',
          roles: ['crc','investigator'],
          shot: 'crc/01-subject-matrix.png',
          shotPre: 'crc/00-home.png',
          shotPreCaption: 'The CRC home screen — today\u2019s tasks and quick links into your common workflows.',
          goal: 'Locate a subject and see which of their visits are open, complete, or signed.',
          steps: [
            'Open **Studienteilnehmer** from the side navigation (`/subjects`).',
            'Use the **search box** to filter by subject ID, or use the filter chips — **Alle**, **Heute**, **Bereit zum Signieren**, **Offene Visiten**, **Alle abgeschlossen** and **Signiert**.',
            'Read the row: the **Eye** column shows the study eye (OD / OS / OU), and each visit column shows a status pill, with a red count when that visit has open queries.',
            'Click the subject ID, or **Öffnen** on the right, to open the subject.',
          ],
          notes: [
            'The visit columns scroll **inside** the table — use the chevron buttons or **Zur letzten Visite springen** to slide through a long visit timeline. The **Studienteilnehmer** and **Öffnen** columns stay pinned.',
            'The matrix opens scrolled to the left so you see identity first (ID, gender, eye, group, enrolment date), then the visits.',
          ],
        },
        {
          id: 'crc-add', num: '2', title: 'Enrol a subject (Add Subject)', deutsch: 'Teilnehmer aufnehmen', route: '/subjects/new',
          roles: ['crc','investigator'],
          shot: 'crc/02-add-subject.png',
          shot2: 'crc/02b-add-subject-filled.png',
          shot2Caption: 'The enrolment form filled with sample data, before saving.',
          goal: 'Add a new subject to the active study.',
          steps: [
            'Click **Add Subject** in the side navigation, or **+** on the matrix (`/subjects/new`).',
            'Enter the **Subject ID**. When the study has a protocol short-code the field is pre-filled (e.g. `GA-`). The app checks availability as you type and flags an ID that is already taken.',
            'Optionally add a **Secondary ID**. Do **not** put direct patient identifiers here — the form warns against personally identifying data.',
            'Set the **enrolment date** (cannot be in the future) and pick the **gender**.',
            'Under **Ophthalmology**, optionally set the **study eye** (OD / OS / OU) and a **screening date**. Leave blank for non-ophthalmology studies.',
            'Save with **Save & add next**, **Save & finish**, or **Save & schedule** (primary — opens the new subject to schedule the first visit).',
          ],
          notes: [
            'The site is taken from your active study/site selection — you do not pick it on this form.',
            'If the server rejects the submit (for example a duplicate ID at this site), the exact field error is shown inline; correct it and save again.',
          ],
        },
        {
          id: 'crc-entry', num: '3', title: 'Open a visit and enter CRF data', deutsch: 'Datenerfassung', route: '/event-crfs/:oid',
          roles: ['crc','investigator'],
          shot: 'crc/20-subject-detail.png',
          goal: 'Record data for one of a subject\u2019s scheduled visits, including paired OD/OS items, then save and mark the CRF complete.',
          steps: [
            'From the subject\u2019s casebook, open the visit (event) you want to work on — the **Event detail** screen lists the CRFs that belong to that visit.',
            'For each CRF row, choose the action on the right: **Öffnen** if data entry has started, or **Datenerfassung starten** to begin a fresh CRF.',
            'Fill in the items. For **bilateral** items the form shows two columns: **OD on the LEFT** (badge **R**), **OS on the right** (badge **L**). Items marked for both eyes (OU) span a single field across both columns.',
            'Click **Entwurf speichern** (save draft) at any time; the header shows when it was last saved and warns of unsaved changes.',
            'When the CRF is finished, click **Abschließen** (mark complete). Required fields are validated; anything missing is highlighted. After completion you return to the visit.',
            'Back on the Event detail screen, when all CRFs for the visit are done, click **Visite abschließen** to mark the whole visit complete.',
          ],
          notes: [
            'A **complete** CRF becomes read-only. To edit it again, click **Erneut öffnen** (reopen) — as a CRC you are permitted to reopen a completed CRF; this is recorded in the audit trail.',
            'A CRF that is **locked** (because the subject\u2019s casebook has been signed) cannot be reopened from here — that needs the study lead.',
            'Use **+ Frage** on an item to raise a discrepancy note, and **Vom letzten Besuch übernehmen** to pre-fill carried-forward values from the previous visit (you still review and **save** them deliberately).',
            'Every entry is attributed to you in the audit trail — on a shared workstation, always log out when you step away.',
          ],
        },
      ],
    },
  ],
};
