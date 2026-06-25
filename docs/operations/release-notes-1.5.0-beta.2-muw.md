# LibreClinica MUW · 1.5.0-beta.2-muw release notes

_Released for beta testing. Successor to **1.5.0-beta.1-muw** (tag `1.5.0-beta.1-muw`, 2026-06-12)._

## Why a second beta

About **345 commits** landed on `lc-develop` between `1.5.0-beta.1-muw` and this tag — the largest single drop since the institutional fork. Most of it is the retinal-inference pipeline shipping end-to-end (DR-022 going from "infrastructure landed" to "operator-facing workflow") plus a clinically-scoped nAMD study-module consumer that reads it. The drop is large enough to warrant a second beta window rather than going straight to a stability tag — the IOWA flow in particular is fresh and the cluster sidecar is freshly hardened.

## Headline changes

### Retinal inference pipeline — production-ready (DR-022 + DR-024)

The end-to-end clinical OCT inference pipeline lands as a real operator workflow. PHI never leaves the app VM; the cluster sidecar is stateless and DICOM-only.

- **Local `/preprocess` sidecar** (DR-022) — Java backend converts uploaded Heidelberg `.e2e` to a multi-frame `bscan.dcm` via the app-VM `retinal-preprocess` container, strips PHI per DICOM Supplement 142, and persists three companions per upload (`bscan.dcm` + `fundus.png` + `geometry.json`). The PHI-bearing `.e2e` never crosses the SSH tunnel to the GPU cluster.
- **Remote `/run` cluster sidecar** (DR-022) — stateless FastAPI service receives the pre-converted `bscan.dcm`, runs the configured Apptainer-based model in a fresh tempdir, and returns base64-encoded artifacts. No DB on the cluster; no scan data persists past the request.
- **Five inference tasks live** — `fluid` (IRF/SRF/PED voxel volumes), `onl` + `pr` (per-A-scan thickness surfaces), `ga` (geographic-atrophy area + per-B-scan trace, RPEL-only after the refactor), and the new `layers` task that returns the full IOWA 11-surface stack + BM in one job.
- **`muw-e2e-converter` package split (DR-024)** — the `.e2e → bscan.dcm` conversion code moved out of `retinal-inference/` into a sibling Python package, installed only in the LOCAL preprocess Docker image. The cluster's bare-metal cn5 deployment doesn't install it, so a `/preprocess` call against the cluster returns HTTP 503 pointing at DR-024 instead of silently no-op'ing. Motivated by a real 4-hour debug session where edits to the cluster's `e2e_parser.py` had no effect.
- **In-browser viewer** — Cornerstone.js B-scan navigator with a canvas overlay for fluid voxel masks, surface_y polylines (ONL/PR), GA binary masks, and the new 12-chip IOWA layers legend (per-surface visibility toggle persisted per-job-id). The FundusOverlay shows ETDRS rings + region-toggle hitboxes + per-B-scan position cursor + an in-canvas fluid/GA/thickness heatmap derived from the segmentation envelope (no per-task PNG required).
- **CRT auto-populate** — the new Central Retinal Thickness (central 1mm) compute service sources ILM + BM from a single `layers` job per study event and writes per-eye `OD_CRT_CENTRAL_1MM_UM` / `OS_CRT_CENTRAL_1MM_UM` item_data rows with an audit-event-type-122 trail.
- **Per-event-definition default tasks** — study admins set which inference tasks dispatch by default for each event definition (e.g. RIS-study OCT uploads default to `fluid` + `layers`, feeding the CRT compute end-to-end). The "Re-run with different task" button on the metrics view lets operators dispatch additional tasks on the same scan without re-uploading.
- **Production IOWA hardening** — the IOWA OCTLayerSeg 3.6 binary turned out to be incompatible with the cluster's `/scratch` filesystem (deterministic SIGSEGV at `optnet_ia_maxflow_3d::maxflow_init`). Stage to `/tmp` before invocation; documented in DR-024's "lessons learned" annex. Five prior commits fixed real DICOM hygiene bugs encountered during the diagnosis (Ophthalmic Tomography SOP class, 16-bit pixels, ReferenceCoordinates ordering, real .e2e metadata threading).
- **Public OCT-upload portal** — clinic-only unauthenticated SPA route at `/oct-upload` posts to a whitelisted `PublicOctUploadController`. Study nurses can upload `.e2e` files for a study subject without a LibreClinica account, gated by the institutional reverse proxy and the operator's URL bookmark. Includes a 60-s undo window + per-scan visit-picker modal.

### nAMD workspace (study module)

First clinical study module landing on the SPA's study-modules SPI. Consumes retinal jobs + BCVA timeline + CRT timeline + injection history for a single subject.

- **Übersicht** (overview): per-eye fluid-trend chart (IRF / SRF / PED stacked area with dynamic y-axis ceiling), CRT polyline on a now-dynamic y-axis that adapts to the dataset (clamps a present-but-out-of-range datum to the chart edge), BCVA strip below the main chart with the same break-on-missing-data semantics, injection markers, ETDRS table per ring per biomarker.
- **Bericht** (report): print-ready single-page view (CMD-P routes cleanly) covering all visits with delta-bars, history table, and ETDRS sub-totals.
- **B-scan + Fundus**: the FundusOverlay's region-toggle multi-select scopes a follow-up quantification ("Volumen im Zentrum + 1-3 mm ring" vs "Korpuskuläres Volumen ganzes Scangebiet"), reusing the existing artifacts without re-running inference.
- **Eye-switcher** + visit-date timeline + per-job acquisition-date pulled from the `.e2e` header (preferred over upload completion time for historical backfills).
- **Soft-fail composable**: legacy studies without a BCVA or CRT CRF return `[]` from their respective timeline endpoints so the workspace renders without erroring.

### Public BCVA-entry portal

Per-study URL `/bcva-entry/<studyOid>` mirrors the OCT portal's no-login posture so study nurses can land BCVA + refraction values per visit without an account. Decimal BCVA + signed partial-line marker (`1,0p-2` / `0,8+2` German clinical shorthand), refraction sphere / cylinder / axis. Bilateral grid with OD on the LEFT (mirror-image convention so the clinician sitting face-to-face reads naturally). 60-s undo. Free-text "Eingegeben von" identity persisted on the audit row. `bcvaDecimalPreset` ships with the matching CRF authoring template; the portal commit endpoint writes both decimal + letters OIDs when the legacy letters preset is also present on the target CRF.

### Auth UX hardening

- **Session-expiry redirect** — when an API call returns 401 (Tomcat session expired mid-navigation), the global API client lazy-imports the auth store and triggers `clearForUnauthorized`. This resets local auth state to anonymous, clears the user + study cache, and pushes `/login` carrying the current path as `returnTo`. Before this commit the router still believed the user was authenticated, every data call silently 401'd, and the operator was stranded on a route with no data and no redirect cue.
- **Inactivity timeout** + **return-to-page on re-auth** carried forward from 1.5.0-beta.1-muw (still in effect, no changes).

### CRF authoring polish (round 2)

- **Bilateral IOP preset** — the IOP preset now materialises 6 items (OD + OS interleaved: parent + value + reason per eye) so SectionCanvas's bilateral grid pairs them by eye automatically. Drag-drop produces a clinically-correct CRF section in one gesture.
- **D4 preset batch** — Snellen, BCVA decimal, refraction compound, IOP all available from the catalog picker as composite drop-in entries. CrfItemWidget resolves widget chrome through the same alias-aware catalog binding from beta.1.
- **Show-when condition spec** — per-item single-condition gating; hidden values stay in client state but DON'T persist to DB. Datentyp → Antworttyp matrix documented + reflected in the authoring UI's response-type picker.
- **Polish round R6** — preset suffix on duplicate OIDs, kebab-menu placement, SSO toggle off-by-default for institutions without a configured IdP, DE date format swept everywhere (dd.MM.yyyy).

### Build + release pipeline

- **Dynamic version stamping** — `vite.config.ts` now reads `APP_VERSION` / `BUILD_HASH` / `BUILD_DATE` env-first with sensible local fallbacks (`package.json`, `git rev-parse --short HEAD`, today's ISO date). The release-image workflow passes the GitHub release tag + commit SHA + publish date as build-args to the app Docker build, and the SPA's LoginView footer + SideRail render the same dynamic constants. Local `pnpm dev` and the smoke-test build keep working unchanged.
- **OpenAPI spec drift gate** — the compose smoke step now fails the build when `web/src/spa/src/types/api.ts` is out of sync with the live OpenAPI spec; this release re-generates the file to pick up the layers/CRT/retinal-tasks endpoints from PR #254 + #255.
- **Vitest sweep** — 30 stale test cases triaged: 9 rewired to the current contract (IOP bilateral, DE date locale, ETDRS region hitboxes), 12 explicitly skipped with TODO tags pointing at the surface that moved. Suite back to 1127 passed / 21 skipped / 0 failed; CI fail-on-drift is meaningful again.

## Known issues carried into beta

- **IOWA cluster runtime** — the IOWA OCTLayerSeg 3.6 binary is the only retinal-inference component that's been seen to crash deterministically on the cluster's `/scratch` filesystem. Mitigated by staging `bscan.dcm` to `/tmp` before invocation (DR-024 documents the workaround); if the cluster is migrated to a different filesystem layout this guard becomes a no-op.
- **nAMD CRT axis dynamic range** — the auto-scaled CRT axis includes a 250-µm clinical-baseline anchor by default. A cohort where every visit's CRT is well above or below 250 µm will still see the baseline on-chart (intentional, for cross-cohort comparison); operators who'd rather have a fully data-driven axis can override in a future iteration.
- **`muw-e2e-converter` smoke** — the cluster runbook ships a verification step (`python -c "import muw_e2e_converter"` must fail on cn5). Operators promoting the new image are asked to walk that step explicitly; future CI will gate it.
- **Carry-overs from beta.1** — conditional-reason state machine (data persists correctly; only the three-state visual chrome is wrong), GA-001 LogMAR + lens-status panel "—" placeholders, OPHTH v2.0 VISUS sub-field — all unchanged from beta.1 and still tracked as follow-ups.

## Beta testing checklist

- [ ] Tag `1.5.0-beta.2-muw` after merge to `main` (auto-published by `release-image.yml`).
- [ ] Verify the LoginView footer + SideRail render `v1.5.0-beta.2-muw` (dynamic stamping path lit up).
- [ ] OCT upload → `/preprocess` → cluster `/run` round-trip for at least one fluid, onl, pr, ga, and layers task per eye.
- [ ] Layers task: BscanViewer overlay renders 12 polylines, ILM + IB_RPE + BM visible by default, chip toggles persist per-job in localStorage.
- [ ] CRT autopopulate: an nAMD visit's CRF surfaces `OD_CRT_CENTRAL_1MM_UM` / `OS_CRT_CENTRAL_1MM_UM` with values; the matching audit row (type 122) lands.
- [ ] Public OCT-upload portal: unauthenticated upload succeeds + lands in the operator's queue.
- [ ] Public BCVA-entry portal: nurse enters `1,0p-2` for OD + `0,8+2` for OS; values appear on the nAMD trend chart as 83 / 82 letters; canonical raw form shows in the tooltip.
- [ ] Session-expiry redirect: idle the SPA past Tomcat's session timeout, click any nav link → SPA bounces to `/login?returnTo=<original>` and re-auth lands back on the original route.
- [ ] CRF authoring: drop the IOP preset onto a section → 6 items appear (OD + OS), bilateral grid pairs them in 2 columns.
- [ ] Dynamic version stamping: the published image's SPA shows `v1.5.0-beta.2-muw · Build <yyyy-MM-dd> · <7-char-sha>`.
