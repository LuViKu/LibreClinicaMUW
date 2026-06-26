# LibreClinica MUW · 1.5.0-beta.4-muw release notes

_Successor to **1.5.0-beta.3-muw** (tag `1.5.0-beta.3-muw`, 2026-06-25). Three-day window of operator-driven UX iteration on the nAMD workspace + Phase E retinal flow._

For older releases see [release-notes-1.5.0-beta.3-muw.md](release-notes-1.5.0-beta.3-muw.md) and [release-notes-1.5.0-beta.2-muw.md](release-notes-1.5.0-beta.2-muw.md).

## Highlights

This release is dominated by the nAMD workspace + retinal-pipeline iteration that came out of three consecutive live-test rounds with the clinical lead. Two backend bug fixes underneath, plus one CI workflow change.

### nAMD treat-and-extend workspace

#### Overview tab

- **CRT delta-vs-prev chip** on the CRT row (was missing despite the sibling biomarkers having it). Direction `badUp` — rising CRT reads as worsening, matches the fluid biomarker convention.
- **ETDRS region filter** (Zentral 1 mm / 3 mm / 6 mm) on the Flüssigkeitsverlauf chart. Picks the polygon source from the inference payload's `etdrs_mm3.central_{1,3,6}mm` blocks; default stays 6 mm (legacy behaviour). Legacy payloads without the breakdown render the flat c6 numbers regardless of selector.
- **Inline chart-header legend** + region selector. The standalone right-column "Legende" card is gone; the Decision panel reclaims that full column height.

#### OCT-Viewer tab

- **Layout matches the inference job viewer**: 4-column FundusOverlay (click-to-quantify ETDRS regions) + 8-column NamdScanFrame (sky-blue slider). Biomarker selection sums show inline under the fundus; `RetinalVisitComparison` Δ-vs-prev tile strip below the imagery.
- **Fullscreen toggle** on the scan frame's top-right cluster. Cornerstone canvas refits via `RenderingEngine.resize(immediate=true, keepCamera=false)` + explicit `viewport.resetCamera()` so the image actually fills the dark canvas, not just the wrapper.
- **Activity-heatmap colormap** under the slider: bars colored red-green diverging by Δ vs the chronological previous visit (red = more fluid → worse; green = less → better). Bar HEIGHT still encodes current-visit fluid magnitude so the heavy slices keep calling attention to themselves. Selected slice keeps the navy / sky accent so it pops over any colormap entry. Grey fallback when there's no comparison data (first visit / missing `per_bscan_mm2`) — no misleading shading.

#### Compare tab

- **Stacked-vertical fullscreen** mode (V_A on top + V_B below) so both visits are visible at once. Triggered by the maximize button next to the "Synchroner Scroll" toggle in the delta-bar card header.
- **Single masthead controls** in the stacked fullscreen — synced-scroll toggle, KI-Maske toggle, close pill. Per-pane KI-Maske + play buttons hidden in `fillContainer` mode (their local state could desync the shared `mask` / `slice` v-models when one was clicked).
- **Symmetric Δ colormaps**: each pane references its OWN chronological predecessor (not the other pane), so V01-as-reference renders uniform grey and V_n shows change vs V_(n-1).

#### Report tab

- **"Dynamische B-Scans" block** replaces the static Baseline-vs-aktueller OCT pair. Computes per-slice Δ across `current.per_bscan_mm2` − `prev.per_bscan_mm2` (irf + srf + ped), picks the SINGLE most-changed slice, renders THAT slice from both V_(n-1) and V_n side-by-side with the change magnitude on the V_n caption. Falls back to the legacy baseline-vs-current block when no prior visit / no per-B-scan trace.
- **Weeks instead of days** in the "Vergleich zum vorherigen Besuch" header (`ceil(days/7)`, min 1). Matches T&E nAMD scheduling cadence; "Vor 6 Tagen" was visually noisy next to "Intervall: 8 Wochen".

### Retinal pipeline

- **acquisition_date end-to-end**: `RemoteRunResult` gains the field; `RemoteRetinalInferenceClient` threads it from `prep.acquisitionDate()`; `RetinalInferenceApiController.persistAcquisitionDate` UPDATEs the row inside the same tx as `insertResult` + `updateStatus('done')`. Public-portal already wrote it; the authenticated path now does too. The `Aufnahmedatum` column on the SPA job-list goes from "—" to the .e2e device-side date on every new upload.
- **Historical backfill script** at [`retinal-inference/scripts/backfill_acquisition_date.py`](../../retinal-inference/scripts/backfill_acquisition_date.py). Already run on dev compose (6 unique .e2e → 17 jobs populated). Idempotent (`acquisition_date IS DISTINCT FROM ?` guard), supports `--dry-run`, configurable DB via `LIBRECLINICA_DB_*` env vars. **One-shot deployment step for prod**:

  ```sh
  docker cp retinal-inference/scripts/backfill_acquisition_date.py \
      libreclinica-muw-retinal-preprocess-1:/tmp/backfill.py
  docker exec libreclinica-muw-retinal-preprocess-1 \
      python /tmp/backfill.py --dry-run    # inspect first
  docker exec libreclinica-muw-retinal-preprocess-1 \
      python /tmp/backfill.py              # commit
  ```

- **Cornerstone refit fix** + `fillContainer` prop on `BscanViewer`. The viewer's hardcoded `aspect-[4/3]` wrapper was blocking the fullscreen + compare-stacked panes from growing; new prop releases it to `flex-1 min-h-0` so the canvas tracks the parent.
- **Compare-prev delta fix**: `RetinalVisitComparison` read fluid metrics at the top level of the payload but the fluid task nests them under `biomarkers`. The tiles were rendering "—" everywhere; now they show real values. Also renamed `total_fluid_volume_mm3` → `total_mm3` to match the payload.
- **Segmentation envelope cache invalidation** on the SSE `done` push. The module-level `cache` in `useSegmentationEnvelope` was caching the in-progress 404 / null response forever, so the layers-task overlay never appeared until manual F5. New `clearSegmentationEnvelopeCache(jobId)` export wired into `RetinalMetricsView`'s `useJobStatusStream.onStatus`.
- **Sortable job-history table** on the SubjectRetinalTab. Six columns (Job / Aufnahmedatum / Aufgabe / Auge / Status / Primärwert). Default Aufnahmedatum desc. Null-acquisition rows pinned to the bottom regardless of sort direction. Tie-break by jobId for stability.
- **Per-subject job deep-link** routes (`/subjects/{label}/jobs/{seq}`) gain an OpenAPI declaration in `web/src/spa/src/types/api.ts` — they were live since the per-subject numbering work landed but the codegen wasn't re-run.

### CI

- **`build.yml` openapi drift self-heals** on lc-develop / master. Previously the smoke job's drift check fail-fast'd, but PRs to lc-develop don't trigger the workflow (cost-saving choice from 2026-05-28), so contributors only saw the error AFTER the merge. The drift step now regenerates `api.ts` against the live spec, commits + pushes the fresh file as `github-actions[bot]` with `[skip ci]`, and lets the build continue. Release/hotfix branches keep the strict fail-fast behaviour — those refs shouldn't carry auto-commits.

## Beta-testing checklist

### Smoke (matches release-image-workflow validation)

- [ ] `ghcr.io/luviku/libreclinicamuw:1.5.0-beta.4-muw` pulls cleanly + Tomcat reaches healthy.
- [ ] `ghcr.io/luviku/libreclinicamuw/retinal-inference:1.5.0-beta.4-muw` ditto.
- [ ] LoginView footer renders `v1.5.0-beta.4-muw · Build <yyyy-MM-dd> · <7-char-sha>`.

### nAMD workspace flows

- [ ] Open the nAMD workspace on a subject with ≥ 3 fluid visits → Overview tab shows the CRT delta chip + region selector on the chart + Decision panel full-height in the right column.
- [ ] OCT-Viewer tab → click the fundus's central ring → biomarker sums appear inline below the fundus.
- [ ] OCT-Viewer tab → maximize on the scan frame → B-scan fills the dark canvas (no letterboxed strip in the left half).
- [ ] Vergleich tab → maximize next to "Synchroner Scroll" → both visits stacked, single masthead KI-Maske button, sync-scroll on.
- [ ] Vergleich V_n + V_(n-1): activity bars are coloured (red/green diverging) on both panes. Pick V01 + V_n: V01 pane uniform grey, V_n bars coloured vs V_(n-1).
- [ ] Bericht tab on a subject with V02+ → "Dynamische B-Scans" block shows the same b-scan index from V_(n-1) + V_n side-by-side.

### Retinal pipeline

- [ ] Fresh OCT upload via the operator-facing flow → SPA job-list `Aufnahmedatum` column populated with the .e2e device-side date.
- [ ] Run the backfill script against historical rows → existing jobs gain populated `acquisition_date` without operator intervention.
- [ ] Dispatch a fresh layers task → BscanViewer + FundusOverlay paint the IOWA surface overlay the moment SSE pushes `done`, no manual F5.
- [ ] Per-subject jobs table → click each column header → sorts asc/desc with a small ▲/▼ on the active column; null-acquisition rows sink to the bottom regardless of direction.

### CI

- [ ] Merge a small backend change to lc-develop that drifts `api.ts`. The smoke job's `Auto-heal api.ts drift` step should commit + push a `github-actions[bot]` follow-up with `[skip ci]` instead of failing the build.
- [ ] Open a release branch with a deliberate drift → strict `Fail on api.ts drift (strict — non-lc-develop refs)` step kicks in + blocks the release tag.

## Known issues

Carried unchanged from beta.3 — see [release-notes-1.5.0-beta.3-muw.md § Beta testing checklist (delta vs beta.2)](release-notes-1.5.0-beta.3-muw.md#beta-testing-checklist-delta-vs-beta2) for the items still under operator validation.
