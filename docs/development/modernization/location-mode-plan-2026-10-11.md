# Plan: single- or multi-location is a deploy-time mode (DR-040 amendment)

**Status:** proposed, 2026-10-11. Changes phase 2 ([PR #419](https://github.com/LuViKu/LibreClinicaMUW/pull/419)) before it merges, so the count-based behaviour never ships.
**Background:** [location-scoping-plan-2026-10-10.md](location-scoping-plan-2026-10-10.md), DR-040 in [decision-record.md](decision-record.md).

## Why

As built in phase 2, the number of active locations decides two things at runtime:

- **The anonymous door.** Untokened device uploads are served while exactly one location is active. Creating the second location closes the door, within the 15 s the count is cached.
- **The fallbacks.** "The only active location" (`LocationScope.defaultLocationId`) is where a study with no location, a source with no location and a file from an unassigned sender belong. With two or more locations, they belong nowhere.

So one click on *Neuer Standort* silently stops every acquisition PC without a token and changes where unplaced records belong. A warning dialog guards that click, but it is still a runtime switch on clinical data flow.

Instead, the operator chooses the mode when deploying. The number of locations then follows from the mode and never changes behaviour.

## Decision

`LIBRECLINICA_DEPLOYMENT_LOCATIONS=single|multi` (Spring property `libreclinica.deployment.locations`, set the same way as `LIBRECLINICA_DEPLOYMENT_INTERNET_FACING`).

- **Default `single`.** Existing installations, including the internal VM, change nothing.
- **Any other value refuses to start**, with a log line that names the variable.

| | `single` (internal deployment) | `multi` |
|---|---|---|
| Active locations | exactly one; a second cannot be created or restored | any number |
| Device request without a token | served, filed at the one location, no source | 401 `uploader token required`, from the first location on |
| Device request with a valid token | served, filed with its source (lets PCs be prepared before a switch) | served, scoped to the token's location |
| Device request with a bad token | 401 `uploader token rejected` | 401 `uploader token rejected` |
| Study, site, source or file with no location | belongs to the one location | belongs to no location: system administrators only, until assigned |
| Site/study creation without `locationId` | the one location | 400 `locationId` required (already `LocationChoice`'s rule when there is no default) |
| Uploaders page and tokens | available | available, required |

A rejected token stays a 401 in single mode. A wrong token is a misconfiguration the tray should show, not something to ignore.

## What goes away

- **The count-based door** in `UploaderTokenFilter` (`doorOpen`, `exactlyOneLocation`, the 15 s cache, `forgetLocationCount`). This also closes the "untokened request during an outage is answered 401 *required*" limit, because the door no longer reads the database.
- **`core.ingest.anonymousDeviceUploads`**, from both `datainfo.properties` files and the filter. It never shipped, so nothing has to be migrated. Its only remaining use would be refusing untokened uploads on a single-location install. The internet-facing mode already answers the device endpoints with 404, so the setting would protect nothing there. *Recommendation: drop it. If tokens should be required on the internal VM before a switch, keep it as a single-mode-only option instead.*
- **The second-location warning** in `LocationEditDialog.vue` (the anonymous-uploaders check and its acknowledgement).

`GET /api/v1/admin/ingest-sources/anonymous-uploaders` stays, repurposed as the pre-switch check (see "Switching").

## Implementation

1. **The mode (core).**
   - `LocationMode` (enum `SINGLE`, `MULTI`) in `service.auth`, with a static holder set once at startup from the Spring property by a small config bean in `web`. `LocationScope` and `StudyDAO` read it from static code paths.
   - Unset means `SINGLE` (the unit tests). A test helper sets and restores it.
2. **One rule for "the default location"** (`LocationScope.defaultLocationId(Connection)`): single mode returns the one active location; multi mode returns null. Every fallback goes through it, so this single change moves them all:
   - `visibleLocations`, `studyIdsAtLocation`, `originForStaffUpload`;
   - `DeviceScope.Origin.of`, `DicomIngestApiController`, `RemidioPullService`;
   - `LocationChoice`, `StudyDAO.create`.
3. **Single mode keeps its data placed.**
   - On startup in single mode, one idempotent statement per table sets the one location on rows that have none:
     - `study.location_id`;
     - `ingest_source.location_id`;
     - `ingest_item.origin_location_id`, for unfiled items with neither origin.
   - Phase 1 already backfilled studies, so this catches only rows written by paths that bypass `StudyDAO.create`. The point is that a later switch to multi finds nothing unplaced.
   - Each statement writes an audit row (one per table and run, with the count) only when it changed something. This needs a new audit type, 208 `location_backfilled`.
4. **Startup check.** Single mode with more than one active location in the database refuses to start. The log line says to disable the extra locations or set `multi`. The app itself cannot get into that state (step 5); only a switch back from multi can. Quietly merging locations would be the worse failure.
5. **Locations API** (`AdminLocationsApiController`), in single mode:
   - `POST /admin/locations` with an active location already present answers 409 `{"message":"single-location deployment"}`;
   - restoring a location while another is active answers the same 409;
   - disabling the only active location answers 409, as today.

   Editing code and name works in both modes.
6. **Token filter.** The door becomes `mode == SINGLE`. Nothing else in the filter changes: the token rules, the 503 on an outage, the 429 bound and the scoping stay.
7. **`/me` and the SPA.**
   - `MeDto` gains `locationMode: "single" | "multi"`.
   - System → Standorte in single mode replaces *Neuer Standort* with one line: this installation is set up for one location, and switching is a deploy step (link to the README).
   - The Uploaders page in single mode shows the pre-switch count ("N Programme senden noch kein Token").
   - `types/api.ts` is regenerated.
8. **Deployment.**
   - `deploy/compose.production.yaml` passes `LIBRECLINICA_DEPLOYMENT_LOCATIONS: "${LIBRECLINICA_DEPLOYMENT_LOCATIONS:-single}"`.
   - `setup-ubuntu-host.sh` leaves it unset unless the operator exports it, like `INTERNET_FACING`.
   - The scripts (`ExportWatcher.ps1`, `OptomedBridge.ps1`) need no change: they already handle 401 by keeping their files.

## Switching from single to multi

1. While still in single mode, issue a token for every acquisition PC on the Uploaders page and enter it in each script's settings.
2. Wait until the pre-switch count reads 0. That means no program seen in the last seven days still sends no token.
3. Set `LIBRECLINICA_DEPLOYMENT_LOCATIONS=multi` in `/etc/libreclinica/env` and restart.
4. Create the further locations. Assign them their sites and people, and the DICOM AE and Remidio sources on the *Quellen* tab.

Step 3 of the implementation guarantees that nothing is left without a location at the switch. Switching back to single works only while at most one location is active (step 4 refuses otherwise).

## Before the first multi-location deployment (not part of this change)

- **The site form has no location field** (`SitesView.vue`). In multi mode, creating a site from the SPA answers the `locationId` field error until phase 4 adds the select. This does not affect the internal VM.
- **The legacy `CreateStudy` and `CreateSubStudy` pages** (`LegacyServletRegistry`) create studies through `StudyDAO.create`. In multi mode those get no location and are visible to system administrators only until assigned. Phase 4 should either add the assignment or close those pages in multi mode.

## Tests

- **`UploaderTokenFilterTest`:** the door tests become per-mode tests. Single mode serves untokened requests whatever the count; multi mode refuses them with one location already.
- **`UploaderTokenDatabaseIT`:** `oneLocation()`, `twoLocations()` and `setDoor()` are replaced by setting the mode.
- **`LocationVisibilityDatabaseIT`, `IngestOriginLocationDatabaseIT`, `LocationDefaultsOnCreationDatabaseIT`:** the fallback cases run in both modes.
- **`CrossSiteIsolationSupport`, `IngestSourceStampingDatabaseIT`, `IngestSourceApiDatabaseIT`, `IngestSourceServiceDatabaseIT`, `AnonymousUploadersApiDatabaseIT`:** they create a second location, so they run in multi mode.
- **`AdminLocationsApiControllerDatabaseIT`:** a second create or a restore in single mode answers 409. Both modes are covered.
- **New:**
  - startup check: single mode with two active locations refuses to start, and an invalid value refuses to start;
  - single-mode backfill: places the unplaced rows, is idempotent and writes an audit row only when it changed something;
  - `/me` carries `locationMode`.
- **SPA:**
  - Standorte in single mode has no create button and shows the explanation;
  - the Uploaders page shows the pre-switch count;
  - the `LocationEditDialog` warning tests are removed.
- **CI:** the full run including the compose smoke test, which also checks `api.ts` against the live spec.

## Documentation

- **`deploy/README.md`, "Uploader tokens and locations":** the mode table and the switch runbook replace "creating the second location closes the front door".
- **Both `datainfo.properties`:** the `anonymousDeviceUploads` block goes.
- **DR-040:** an amendment ("the mode is a deploy-time choice").
- **The location-scoping plan's "Phase 2 as built":** updated to match.

## Effort

About a day: the mode and fallbacks ¼, API, startup check and backfill ¼, SPA ¼, tests and docs ¼. It lands on the phase 2 branch, followed by a full CI run and a re-check of every place listed in step 2.
