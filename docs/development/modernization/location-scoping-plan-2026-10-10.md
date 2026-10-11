# Locations: a global place, studies enabled per location, data scoped by location

**Status:** plan, 2026-10-10; uploader tokens and the Standort-Admin right added the same day. Decision record: [DR-040](decision-record.md#dr-040--locations-are-global-studies-are-enabled-per-location-and-study-independent-data-is-scoped-by-location) (Proposed). Approved direction (user, 2026-10-10): link LibreClinica's sites to global locations; derive a user's location scope from their site roles, with optional explicit assignment; data of unknown origin is visible to system administrators only; uploaders authenticate with a per-uploader token issued for one location; a Standort-Admin right lets selected staff issue tokens for their own location.

## Why

A physician sees every scan a device uploaded, whatever study or site they work in. `IngestItemVisibility` shows an unfiled file with no origin to everyone who may open the inbox, and every device ingress has no origin: the Export Watcher, the Optomed Bridge, the Remidio pull, the DICOM receiver and the public upload page. A staff upload is scoped by `origin_study_id`, but a device knows nothing about studies.

What is missing is a place. A "Standort" in the SPA today is a study site, a LibreClinica sub-study that exists per study. Devices (`uploader_instance`), DICOM senders, the Remidio account and the upload page belong to no place at all, so nothing can be scoped by place.

The internet-facing deployment closes the device ingress, so the gap is internal today. It becomes a multicenter problem as soon as a second clinic contributes scans.

## Principles

1. **A location is a physical place**: a clinic, a department or a partner hospital. Locations are global and managed by system administrators.
2. **A study is enabled at a location by having a site there.** Sites stay, because roles, ODM export, randomisation, SDV and the cross-site isolation all hang off them. Each site points to exactly one location. A study without sites points to its location directly.
3. **Everything that arrives has an origin location**, set at arrival from its source.
4. **A user sees study-independent data only for the locations they are scoped to.** Study data stays scoped by study and site, exactly as today.
5. **Fail closed.** Data whose origin is unknown is visible to system administrators only, until an administrator assigns its source.
6. **No behaviour change on day one.** The migration creates one location and assigns everything to it.

## Data model (one new changeset)

| Table / column | Purpose |
|---|---|
| `location` (`location_id`, `code` unique, `name`, `status_id`, `address` fields, `owner_id`, `date_created`, `date_updated`) | the place |
| `study.location_id` (nullable FK) | a site's location; for a study without sites, the study's own location; for a parent study that has sites, its *coordinating* location (may be null). The migration sets it on every existing study row |
| `user_location` (`user_account_id`, `location_id`, `can_manage_sources`, `granted_by`, `date_granted`) | explicit scope for staff without a study role (technicians, photographers); `can_manage_sources` is the **Standort-Admin** right for that location (below) |
| `ingest_source` (`ingest_source_id`, `kind` uploader / dicom_ae / remidio / portal, `source_key`, `location_id` nullable, `display_name`, `token_hash`, `token_hint`, `created_by`, `created_at`, `last_used_at`, `revoked_at`, `revoked_by`) | one row per source. An **uploader** source is created by issuing a token (it has its location from the start); a DICOM AE or the Remidio account is created by an administrator or the first time it is seen, and gets its location assigned. Only the token's hash is stored, and `token_hint` keeps its first characters so a person can tell tokens apart |
| `ingest_item.origin_location_id` (nullable FK), `ingest_item.ingest_source_id` (nullable FK) | where a file came from |

**Migration:**
- Create one location. Its name and code are deploy settings, with a default of "MUW Augenklinik".
- Point every existing site, and every study without sites, at it.
- Create `ingest_source` rows for the known uploader instances, for the configured calling AE titles, and for the Remidio account and the upload page, all at that location.
- Set `origin_location_id` on every existing `ingest_item`.
- No row is left without a location, so nothing becomes admin-only on the internal deployment.

## Scope resolution

A user's location set is the union of:
- the locations of the sites they hold a live role on;
- for a role on a parent study, the locations of all its sites, plus the study's own location;
- their explicit `user_location` rows.

A system administrator has every location. All of this is computed in one place, `LocationScope`, next to `SiteVisibilityFilter`, and cached per session like the visible-study set.

## What changes for each surface

| Surface | Today | After |
|---|---|---|
| **Inbox** (`IngestItemVisibility.predicate` / `canSee`, the single rule for lists, counts, reads and every state change) | unfiled + no origin → everyone | unfiled → visible when `origin_location_id` is in the user's set (or, for a staff upload, `origin_study_id` is visible, as now); no origin location → system administrators only. Filed files are unchanged (the subject's study decides) |
| **Staff upload** | `origin_study_id` from the session | also `origin_location_id` = the location of the session's site or study |
| **Export Watcher / Optomed Bridge** | anonymous: `/public/upload/resolve` and `/commit` and the heartbeat take no credential; the Optomed worklist takes one token shared by every bridge (`core.optomed.worklist.token`) | **every request carries the uploader's token** (`X-MUW-Uploader-Token`: lookup, upload, heartbeat, worklist) → `ingest_source` → location. The lookup (`/resolve`) answers only for visits at that location, where today it answers for any label. A request without a token is the source "unknown uploader" → admin-only (or the default location while it is the only one), until the anonymous front door is switched off (see Rollout) |
| **DICOM receiver (C-STORE)** | calling AE allow-list, no place | calling AE → `ingest_source` → location; an unknown AE is admin-only |
| **Camera worklist (MWL C-FIND, Optomed worklist)** | global `core.dicom.worklist.studyOids`; the DICOM receiver asks the app for the date window only and keeps the calling AE to itself | the receiver passes the calling AE along; the AE's location → only visits of subjects at sites of that location. The Optomed worklist goes by the bridge's token instead. Today's setting stays as an extra filter. DICOM has no credential, so a camera that sends directly stays mapped by its AE title |
| **Remidio pull / patient sync** | one cloud account | the account is an `ingest_source` with a location; the patient sync pushes only subjects at that location's sites (a second account per location later, if a second clinic uses Remidio) |
| **Public upload page** | anonymous | the page is opened with a link that carries an uploader token, one per workstation or place, so it behaves like an uploader; the anonymous page follows the front-door switch |
| **Patient overview (cross-study)** | visible studies | unchanged: it lists enrolled patients through their studies, which are already site-scoped |
| **System Status uploader / storage panels** | administrators | unchanged (admin-only) |

## Uploader tokens

- **Issued for one location.** Issuing creates the uploader source; there is no separate assignment step.
- **Shown once.** A token is 32 random bytes and is displayed once at issue. The platform stores only its SHA-256 hash and its first characters as a hint.
- **Revocable.** A revoked token is refused at once; the uploader shows "token rejected" in its tray and its heartbeat panel.
- **Visible use.** `last_used_at` is updated at most once a minute.
- **Audited.** Issuing, revoking and replacing a token each write an audit row: who, which location, which uploader name. The token itself never appears.
- **Narrow.** A token can do four things for its location: look up visits, upload, report heartbeats and fetch the worklist. It opens no session, reads no study data and cannot reach another location.
- **Rate limit.** Per token, which settles the open uploader rate-limit question: a known device gets a budget that fits a clinic day, and an unknown caller gets none once the front door is closed.
- **On the PC.** The Export Watcher gets a token setting, DPAPI-protected like the Optomed Bridge's; both scripts get a version bump. A token is entered in the uploader's settings dialog, never passed on a command line.

**Who issues tokens.**
- **System administrators,** for every location.
- **Standort-Admins,** for their own location only. The right is granted by a system administrator per person and location, typically to that site's IT contact or a study coordinator.
- **A study role does not include it.** A token lets its holder put scans into a location's inbox and fetch its worklist (subject labels, dates of birth, sex), so issuing one is a deliberate grant.
- **What a Standort-Admin can do:** see the location's uploaders and their health, and issue, revoke and replace their tokens. Nothing at other locations, and no change to sites, studies or users.

## Administration (SPA)

- **System → Standorte:** list, create, edit, deactivate, plus per location its sites, sources and explicitly assigned users. System administrators only, apart from the uploader section below.
- **Uploaders of a location:** issue, revoke and replace tokens, names, last use, heartbeat health. Open to system administrators and to that location's Standort-Admins.
- **Site form** (`SitesView`): a required "Standort" select over the global locations. The address fields come from the location, so the free facility fields are no longer typed per site.
- **Sources:** every `ingest_source` with its location. "Unknown" sources are highlighted together with their pending file count, and assigning a location moves their files into the right inboxes at once.
- **User page:** explicit location assignments, for staff without a study role, and the Standort-Admin right per location.

## Phase 1 as built (2026-10-11)

- **Schema** (`lc-muw-2026-10-11-locations.xml`): `location`, `user_location` (with `can_manage_sources`, unused until phase 2), `study.location_id`, `ingest_item.origin_location_id`, one seeded location (`MUW-AUGEN`, renameable in the UI), every study row and ingest item backfilled.
- **`LocationScope`** and the single inbox rule in `IngestItemVisibility`: an unfiled or dismissed item with no origin study is visible when its origin location is one of the user's; with neither origin it is a system administrator's. A retinal job with neither a visit nor a scan record is an administrator's.
- **Stamping:** every ingest path writes `origin_location_id` (staff uploads from the session study's location, device ingress from the default location, NULL when several locations exist and the source is not assigned yet). New studies and sites get the default location while exactly one location exists; with more than one, the create APIs require `locationId`.
- **Administration:** `AdminLocationsApiController` (`/api/v1/admin/locations`, system administrators only), audit types 195-200, and the System → Standorte screen.
- **Not in phase 1:** `ingest_source`, uploader tokens, the Standort-Admin right (phase 2); location-scoped worklist and Remidio sync (phase 3); the site form's location select and the wording of the per-study site (phase 4).

## Phase 2 as built (2026-10-12)

- **Schema** (`lc-muw-2026-10-12-ingest-source.xml`): `ingest_source` (`kind` `uploader` / `dicom_ae` / `remidio` / `portal`, `source_key`, `display_name`, nullable `location_id`, unique `token_hash`, `token_hint`, `created_by` / `created_at`, `last_used_at`, `revoked_at` / `revoked_by`, `instance_uid`) and `ingest_item.ingest_source_id`. `(kind, source_key)` is unique where a key exists, and an uploader's name is unique per location without regard to case. **There is no backfill and no source after the upgrade:** an uploader appears when a token is issued, a DICOM AE title or the Remidio account the first time it is seen, so day one still changes nothing.
- **`IngestSourceService`** (core) is the one writer: issue, replace, revoke, rename and assign a location, each one transaction together with its audit row (types 201-205); `findActiveByToken` for the device endpoints; `resolveAutoSource` for the senders that have no credential. A token is `mwu_` and 43 base64url characters from 32 `SecureRandom` bytes, returned once; only its SHA-256 and an eight-character hint are stored. The lookup is by hash with no cache, so a revoked or replaced token is refused on the next request, and `last_used_at` is renewed at most once a minute. Nothing logs a token or a hash.
- **API.** `UploadersApiController` (`GET /api/v1/uploaders`, `POST /locations/{id}/uploaders`, `PUT /uploaders/{id}`, `POST /uploaders/{id}/replace` and `/revoke`) serves system administrators and **Standort-Admins** (anonymous 401, no right at all 403, another location's uploader 404 for a Standort-Admin, so the answer is no oracle for what exists elsewhere). `AdminIngestSourcesApiController` (`GET /admin/ingest-sources`, `PUT /admin/ingest-sources/{id}/location`; system administrators only): assigning a location also places the source's waiting files (unbound or dismissed, no origin yet) in the same transaction. `PUT /admin/locations/{id}/users/{username}` takes `canManageSources`, the **Standort-Admin right** (`user_location.can_manage_sources`; a study role does not include it), and `GET /me` lists the caller's `manageableLocations`.
- **Audit types 201-207:** source created, uploader token replaced, uploader token revoked, uploader renamed, source assigned to a location, Standort-Admin right granted, Standort-Admin right revoked. A row carries the uploader's name and at most the token hint.
- **SPA:** the Uploaders page (*Uploader und Tokens*; System rail and home card; a Standort-Admin who holds no study role can reach it), System → Standorte with the Standort-Admin checkbox per assigned person and a *Quellen* tab to assign sources, and System Status naming the two token problems.
- **Scripts:** Export Watcher 2026-10-12.2 and Optomed Bridge 2026-10-12.1 send the token (`X-MUW-Uploader-Token`) on every request, DPAPI-protected and entered in the settings dialog, never on a command line. A 401 leaves every file where it is and shows `token-required` or `token-rejected`; it is never treated like a 429. 2026-10-12.2 also fixes a `.e2e` file with several volumes being set aside instead of uploaded. Rollout and putting files back: `deploy/README.md`, "Uploader tokens and locations".
- **Enforcement (D1):** enforcement of the token on the device endpoints, location-scoped lookup/worklist, per-token rate limit and the anonymous-door rule (an untokened request is served only while `core.ingest.anonymousDeviceUploads` is `allowed` and exactly one location is active, else 401 `uploader token required`; an unknown, revoked or removed-location token is 401 `uploader token rejected`).
- **Senders without a credential (D2).** The DICOM hand-off's calling AE title and the Remidio account each become a source on first sight, unassigned, and stamp every file with `ingest_source_id` and, as its origin, the source's location. A source with no location files at the only active location and, while several exist, at none: the file is administrator-only until the source is assigned, which then places it. A title that cannot be a source (none sent, an invalid one, or a new one past a limit of 200) gets the same default rule and no source; the hand-off is never refused for it. The limit exists because the receiver's calling-AE allow-list is empty by default, and an empty list accepts any title: without it anything that reaches the port could fill the table. The Remidio source is keyed by the screening site the pull lists (`core.remidio.siteCustomId`), not by the vendor's client name, which is the same for every integration. `GET /api/v1/admin/ingest-sources/anonymous-uploaders` (system administrators) counts the uploader programs seen in the last seven days that have never reported with a token and lists the newest 50: what stops working when the second location is created.
- **Not in phase 2:** the camera worklist by AE title (phase 3); a Remidio account per location (one account is one source for now); `portal` sources (the kind is in the schema, nothing makes one, so the public upload page is not a source yet); the browser upload page carrying a token (an SPA follow-up); and the count of untokened uploaders on System → Standorte before a second location is created (the endpoint exists, the page does not use it yet).

## Phases

1. **Foundation, no behaviour change** (≈1.5 days): the changeset and migration, `LocationScope`, the inbox predicate, staff uploads stamping a location, and the System → Standorte admin. Tests:
   - a two-location fixture: a user at location A sees A's device files and never B's, in lists, counts, reads and every state change;
   - an unknown origin is admin-only;
   - one location behaves exactly as today;
   - the cross-site isolation matrix is extended with the location dimension.
2. **Sources and uploader tokens** (≈3 days):
   - `ingest_source`, with token issue, revoke and replace, hashing and audit;
   - the Standort-Admin right;
   - token checks on `/resolve`, `/commit`, the heartbeat and the Optomed worklist, with the lookup scoped to the token's location and a per-token rate limit;
   - the scripts: token setting, version bump, tolerant of a missing token during the rollout;
   - the AE mapping in the C-STORE hand-off; Remidio and the public page as sources;
   - the uploaders and sources UI.
3. **Location-scoped worklist and Remidio sync** (≈1 day): MWL and Optomed worklist by the device's location; the Remidio patient sync by location.
4. **Site form and cleanup** (≈0.5 day): the site form's location select, facility fields taken from the location, the manual and the release notes. **Wording (decided 2026-10-11):** "Standort" means only the global place; the per-study site is called **"Prüfzentrum"** in German ("site" in English) on the Sites page, its Home card, the rails and the manual.

**Total:** ≈6–7 days, with each phase separately mergeable. Phase 1 alone already closes the gap for staff uploads and for every source on day one, because everything is at the single location. The real separation starts with the second location.

## Release and rollout

- **Phases 1–2 together in one release.** After the deploy, an administrator checks System → Standorte: one location, all sources assigned.
- **Every acquisition PC gets the new script version and its own token.** Issue the token on the uploaders page, enter it in the uploader's settings, and check that its heartbeat shows the location. Until a PC has its token, it keeps working at the default location.
- **The anonymous front door closes by itself with the second location.** An untokened device request is served only while `core.ingest.anonymousDeviceUploads` is `allowed` (the default) **and exactly one active location exists**; with two or more it answers `401 uploader token required`. So creating the second location is the switch: every uploader needs its token by then, and the UI says so when the second location is created. The setting can also be set to `denied` earlier to close the door on a single-location installation.
- **The shared Optomed worklist token is removed** at the same point.
- **Before a second location is created:**
  - every source of the new place is assigned;
  - its sites are created at it;
  - its staff have roles on those sites, or an explicit assignment.

## Not changing

- Study data visibility (subjects, CRFs, notes, SDV, exports) stays study and site based. Location only scopes what has no study yet.
- The internet-facing mode keeps refusing device ingress. There, a location is simply each partner's site.
- LibreClinica's site semantics, roles and ODM are untouched.

## Open points (decide during phase 1)

1. **Location of a study without sites:** stored on the study row (`study.location_id`), as above, or by creating an implicit site. The plan uses the column, because it avoids a fake site.
2. **Several Remidio accounts:** whether a second clinic would use its own Remidio account, or the same account with a site field. This only matters when a second clinic uses Remidio.
3. **Moving a scan to another location:** only by filing it to a subject, whose study then decides. A plain "move to location X" would be an admin action; only add it if needed.
