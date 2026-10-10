# Locations: a global place, studies enabled per location, data scoped by location

**Status:** plan, 2026-10-10. Decision record: [DR-040](decision-record.md#dr-040--locations-are-global-studies-are-enabled-per-location-and-study-independent-data-is-scoped-by-location) (Proposed). Approved direction (user, 2026-10-10): link LibreClinica's sites to global locations; derive a user's location scope from their site roles, with optional explicit assignment; data of unknown origin is visible to system administrators only.

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
| `study.location_id` (nullable FK) | a site's location; for a study without sites, the study's own location. Null on a parent study that has sites |
| `user_location` (`user_account_id`, `location_id`, `granted_by`, `date_granted`) | explicit scope for staff without a study role, such as technicians and photographers |
| `ingest_source` (`ingest_source_id`, `kind` uploader / dicom_ae / remidio / portal, `source_key`, `location_id` nullable, `display_name`, `first_seen_at`) | one row per source. Created automatically the first time a source is seen; an administrator assigns its location |
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
| **Export Watcher / Optomed Bridge** | anonymous `/public/upload` + heartbeat with instance id | the upload request carries the instance id too (new header; script version bump; rollout to the acquisition PCs) → `ingest_source` → location. An unknown or old script lands at the source "unknown uploader" → admin-only until assigned |
| **DICOM receiver (C-STORE)** | calling AE allow-list, no place | calling AE → `ingest_source` → location; an unknown AE is admin-only |
| **Camera worklist (MWL C-FIND, Optomed worklist)** | global `core.dicom.worklist.studyOids`; the DICOM receiver asks the app for the date window only and keeps the calling AE to itself | the receiver passes the calling AE along; the AE's location → only visits of subjects at sites of that location. Today's setting stays as an extra filter. The Optomed worklist gets the same through its bridge instance |
| **Remidio pull / patient sync** | one cloud account | the account is an `ingest_source` with a location; the patient sync pushes only subjects at that location's sites (a second account per location later, if a second clinic uses Remidio) |
| **Public upload page** | anonymous | the page is a source per installation (or per link token, if several places use it) with a location |
| **Patient overview (cross-study)** | visible studies | unchanged: it lists enrolled patients through their studies, which are already site-scoped |
| **System Status uploader / storage panels** | administrators | unchanged (admin-only) |

## Administration (SPA)

- **System → Standorte:** list, create, edit, deactivate, plus per location its sites, sources and explicitly assigned users. System administrators only.
- **Site form** (`SitesView`): a required "Standort" select over the global locations. The address fields come from the location, so the free facility fields are no longer typed per site.
- **Sources:** every `ingest_source` with its location. "Unknown" sources are highlighted together with their pending file count, and assigning a location moves their files into the right inboxes at once.
- **User page:** explicit location assignments, for staff without a study role.

## Phases

1. **Foundation, no behaviour change** (≈1.5 days): the changeset and migration, `LocationScope`, the inbox predicate, staff uploads stamping a location, and the System → Standorte admin. Tests:
   - a two-location fixture: a user at location A sees A's device files and never B's, in lists, counts, reads and every state change;
   - an unknown origin is admin-only;
   - one location behaves exactly as today;
   - the cross-site isolation matrix is extended with the location dimension.
2. **Sources** (≈1.5 days): `ingest_source`; the uploader header (script bump for Export Watcher and Optomed Bridge, tolerant of old scripts); the AE mapping in the C-STORE hand-off; Remidio and the public page as sources; the source assignment UI.
3. **Location-scoped worklist and Remidio sync** (≈1 day): MWL and Optomed worklist by the device's location; the Remidio patient sync by location.
4. **Site form and cleanup** (≈0.5 day): the site form's location select, facility fields taken from the location, the manual and the release notes.

**Total:** ≈4–5 days, with each phase separately mergeable. Phase 1 alone already closes the gap for staff uploads and for every source on day one, because everything is at the single location. The real separation starts with the second location.

## Release and rollout

- **Phases 1–2 together in one release.** After the deploy, an administrator checks System → Standorte: one location, all sources assigned.
- **The uploader scripts get a new version** and must be updated on each acquisition PC before a second location exists. Until then, old scripts keep working at the default location.
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
