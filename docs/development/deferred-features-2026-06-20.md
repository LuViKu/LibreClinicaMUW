# Deferred / not-implemented features — triage 2026-06-20

Items accumulated across PRs #209, #210, #211, #224 and the earlier retinal + portal + app-feedback work. Each is tagged with the originating PR and a one-line rationale for why it didn't ship.

**How to use:**
- Tick the `[ ]` box once you've made a decision.
- Replace `decision: TBD` with `decision: KEEP` (stays on backlog), `DROP` (will not be done), or `DONE` (already shipped — please add the PR/commit).
- Optionally add a note after the decision (e.g., a target phase, a blocker, a ticket link).

When this file is fully triaged, the engineer can:
- Open one tracking issue per `KEEP`.
- Edit `docs/development/modernization/decision-record.md` for items that affect the broader modernization spine.
- Delete the file or move it to an archive folder.

---

## A. Retinal-inference + viewer (PRs #209, #211)

- [ ] **A1 — DICOM SEG writer for fluid labels.** Needs `dcm4che-core 5.x`. `bscan.dcm` is now persisted so the foundation is in place.
      **decision: TBD**

- [ ] **A2 — Fovea localizer better than volume-center MVP.** Currently uses geometric volume center. Needs a heuristic or model.
      **decision: TBD**

- [ ] **A3 — GA RPEL clinical-threshold tuning.** Hard-coded `> 0.5`. Current threshold works on the IAMD test scan but no clinical sign-off.
      **decision: TBD**

- [ ] **A4 — Per-modality vendor pixel-scale registry beyond Spectralis.** Topcon `.fda`, Zeiss `.dcm`, Cirrus stay out for now.
      **decision: TBD**

- [ ] **A5 — Hand-written EN translations for retinal viewer + portal.** All keys present with `[NEEDS_REVIEW] ` prefix. Gate script exists but is not CI-enforced.
      **decision: TBD**

- [ ] **A6 — OpenAPI pre-commit hook for codegen drift.** Currently manual regen; CI catches drift via compose smoke. Would prevent the "push → CI fails → regen → repush" cycle.
      **decision: TBD**

## B. OCT upload portal (PR #210)

- [ ] **B1 — Cluster sidecar honors `scan_index` at runtime.** Java side wired in #211; Python sidecar still picks `max(volumes, key=num_slices)`. Multi-acquisition uploads silently process largest only.
      **decision: TBD**

- [ ] **B2 — Authenticated park-bind UX completeness.** Parked-scans list + bind endpoint + dialog shipped (#211). Still missing: bulk-bind from the admin parked view (e.g., bind all from the same `e2eUuid` at once).
      **decision: TBD**

- [ ] **B3 — CAPTCHA or higher per-IP rate limit beyond 30/hr.** Hand-rolled token bucket at 30 req/hr/IP. Heavier clinics or longitudinal sessions might trip it.
      **decision: TBD**

- [ ] **B4 — Mobile / tablet layouts for the portal.** Desktop only (1440 px target). Drag-drop UX doesn't translate to touch.
      **decision: TBD**

## C. Cross-study patient identity (PR #224 — Wave 1B)

- [ ] **C1 — `match-preflight` label-union controller wiring.** `SubjectMatchPreflightRequest.label` field added but unused. DAO method `findByLabelAcrossAllStudies` exists but isn't called from the endpoint. ~50 LOC.
      **decision: TBD**

- [ ] **C2 — `POST /study-subjects/{id}/link-patient` endpoint.** Audit type 118 reserved but endpoint not implemented. ~80 LOC.
      **decision: TBD**

- [ ] **C3 — `PatientMatchDialog.vue` "Verknüpfen" action.** Cross-study label match + link action not wired. ~40 LOC.
      **decision: TBD**

- [ ] **C4 — Hard `patient` table with FK.** Plan explicitly chose soft-link via UUID. Future migration if you ever want strict referential integrity across studies.
      **decision: TBD**

## D. CRF builder + entry (PR #224 — Wave 1D, Wave 2)

- [ ] **D1 — 4 flaky vitest harnesses.** `CancelEventDialog.spec`, `CrfPrefillModal.test.todo`, `CrfEntryView.test.todo`, `CrfItemWidget TRISTATE_REASON` cases. Async + i18n setup issues; component code compiles + tsc-clean.
      **decision: TBD**

- [ ] **D2 — `EventsApiControllerCancelReasonIT` fixture seeding.** Class-level `@Disabled` from #224. Controller hits 404 before reaching validation because no `event_crf` fixture seeded.
      **decision: TBD**

- [ ] **D3 — Visual CRF builder canvas — feature flag removal.** Old wizard route + new canvas route both live. Plan promised "one release" before removing the wizard. Wizard menu link still visible.
      **decision: TBD**

- [ ] **D4 — More presets beyond IOP + OPHTH_EXAM.** Catalog infrastructure ships; only 2 entries. Common asks: visual acuity, RNFL, retinal-thickness-map references, BCVA chart.
      **decision: TBD**

- [ ] **D5 — Per-study cancellation reason override.** Plan explicitly chose institutional-global. Sponsor protocols might want different lists.
      **decision: TBD**

- [ ] **D6 — Translations of cancel reasons + new portal/retinal strings.** `[NEEDS_REVIEW] ` markers on every new key (de.json + en.json mirror).
      **decision: TBD**

## E. Cross-cutting

- [ ] **E1 — Trends + longitudinal Chart.js across visits (deeper).** Subject tab shows per-task trends from #211. Cross-subject comparison views + per-study cohort statistics not done.
      **decision: TBD**

- [ ] **E2 — Sub-µm geometry refinement when other vendors land.** Spectralis-tuned only. Multi-vendor support requires a modality scale registry.
      **decision: TBD**

- [ ] **E3 — Multi-study CRF item harmonization** ("foveal sparing" same meaning across studies). Listed as a precursor for the NL-query feasibility. Needs an item-OID mapping registry.
      **decision: TBD**

- [ ] **E4 — Sponsor-protocol-aware roles / permissions** beyond current per-study site visibility.
      **decision: TBD**

- [ ] **E5 — AI / NL query of patient data.** Tier 1 (retinal-inference results only) is feasible in ~2 weeks; not started. See the feasibility note in the earlier transcript.
      **decision: TBD**
