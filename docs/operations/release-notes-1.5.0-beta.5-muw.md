# LibreClinica MUW · 1.5.0-beta.5-muw release notes

_Successor to **1.5.0-beta.4-muw** (tag `1.5.0-beta.4-muw`, 2026-06-26). Three-day window of clinically-driven work on the IOWA layer-segmentation correction surface, the MUW brand lockup landing across SPA + legacy JSP, and a deep heritage-debt sweep ahead of Phase B.5._

For older releases see [release-notes-1.5.0-beta.4-muw.md](release-notes-1.5.0-beta.4-muw.md), [release-notes-1.5.0-beta.3-muw.md](release-notes-1.5.0-beta.3-muw.md), [release-notes-1.5.0-beta.2-muw.md](release-notes-1.5.0-beta.2-muw.md), and [release-notes-1.5.0-beta.1-muw.md](release-notes-1.5.0-beta.1-muw.md).

## Highlights

The clinical lead's request for hand-correctable IOWA layer surfaces drove the dominant theme; the ETDRS-ring eccentricity indicator + collapsible layer bar are the operator-feedback follow-ups. Underneath, the heritage-debt audit + cleanup brings the JDT Problems count from 2441 → ~57 and prepares the codebase for the Hibernate 6 cliff (Phase B.5). The MUW-branded icon set + wordmark lockup arrive across every shell.

### Retinal layer-segmentation correction

- **HEYEX-style layer editor** ([web/src/spa/src/components/BscanLayerEditOverlay.vue](../../web/src/spa/src/components/BscanLayerEditOverlay.vue), #261). Three modes — whole-layer shift / freehand draw / 17 control-points with Catmull-Rom evaluation — operate on the IOWA surface stack. Per-(slice, layer) diff stored under `<bscan_masks_dir>/corrections/` so the AI's original CSVs are never overwritten. New `retinal_inference_correction` table tracks the revision history; `audit_log_event` type 119 (`retinal_segmentation_corrected`) is written on every save.
- **Prefer-corrections envelope loader** ([core/src/main/java/.../service/retinal/SegmentationEnvelopeLoader.java](../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service/retinal/SegmentationEnvelopeLoader.java)). When the SPA fetches the segmentation envelope for a job, the loader probes the `corrections/` subfolder first and falls through to the original CSVs. The new `X-MUW-Seg-Corrected: 001-ILM,011-BM` response header tells the SPA which surface indices the operator edited so it can render a "korrigiert" badge.
- **Role gate**: only `INVESTIG` + `DATA_MGR` see the tool palette; `Monitor` / `CRC` see a read-only overlay. Save button on the fullscreen masthead emits one POST per edited layer. Close-with-unsaved-edits warns + discards.
- **CST follows the corrected layers** — clicked the foveal A-scan in the FundusOverlay and the CST chip + Δ-vs-prev recompute against the merged ILM/BM stack automatically (no manual recompute click). "CRT" relabelled to "CST" in the UI; older readings stay backwards-compatible.
- **Undo stack (50 entries) + Ctrl/⌘+Z + arrow-key line shift** (#288). Every edit op (shift drag, point move, freehand stroke, dblclick-add, delete, arrow shift) snapshots the pending diff. Arrow Up/Down in shift mode steps 1 px; Shift+Arrow steps 5 px. The ↶ button on the tool palette is enabled iff the stack is non-empty.
- **Selected control points stay the same size** (#288). Blue fill on selection; the outline + radius are unchanged from the unselected dot. Drops the larger white-centre disc that visually shifted under the cursor.
- **ETDRS-ring eccentricity indicator** (1 / 3 / 6 mm, #288 + #289). Vertical dotted amber markers at the A-scan columns where each ring's circumference intersects the current B-scan; HTML labels float on top so type isn't distorted by the SVG's per-axis stretch. Toggle on every fullscreen masthead — inference-job correction fs, nAMD per-pane fs, nAMD compare-tab fs (shared via prop so both stacked panes toggle together). Default OFF; preference persists in localStorage. Slice-mm default formula `(cols × lateralMm) / nBscans` assumes the canonical Heidelberg ~6 mm × 6 mm dense cube.
- **Collapsible layer bar** (#289). The bar's right half occluded the ETDRS-ring labels along the B-scan's top edge — chevron buttons collapse it to the left edge (only the active-layer chip + expand arrow remain) and re-expand. Slice nav (`24 / 49`) removed from the bar since it's already in the layer-visibility legend above. State persists.

### MUW brand lockup

- **Commissioned favicon + PWA icon set** ([web/src/spa/public/](../../web/src/spa/public/), #292). White-stroke eye on navy radial gradient with coral pupil — fits the existing `muw-blue` / `muw-teal` / `muw-orange` palette. SPA shell's `<head>` extended with PNG fallbacks (16 / 32 / 48), apple-touch-icon, `.ico`, PWA manifest, and a navy `theme-color` meta. Mobile Safari + Chrome tint the address bar.
- **JSP brand-lockup fragment** ([web/src/main/webapp/WEB-INF/jsp/include/brand-lockup-header.jsp](../../web/src/main/webapp/WEB-INF/jsp/include/brand-lockup-header.jsp)). Inline-SVG horizontal lockup (eye + "LibreClinica" Newsreader serif + small uppercase "MUW" Inter coral) replaces the historic `<img src="images/Logo.gif">` reference in 13 JSP sites (decorator, login, admin, manage-study, double-data-entry, extract-headers, etc). One-edit-now-and-future since all 13 sites delegate via `<jsp:include>`.
- **Login page lockup**: [web/src/main/webapp/images/login_logo.svg](../../web/src/main/webapp/images/login_logo.svg) replaces the PNG via the existing `#logo` background-image rule in `NewLoginStyles.css` + `styles.css`. Larger variant carries the "Augenheilkunde · Medizinische Universität Wien" tagline.
- **SPA wordmark sites refit** to the real eye glyph. `LoginView`, `TopBar`, `PublicTopBar`, `App.vue` auth-pending shell, and `ContactView` all shipped a placeholder grid-icon (circle + crosshairs) during the Phase E scaffold; now they render the same eye + coral-pupil SVG used by the favicon.

### Heritage debt cleanup (pre-Phase B.5)

JDT Problems count 2441 → ~57 via several waves. None of these change runtime behaviour; the work clears the editor noise so reviewers + AI assistants can spot real signal during Phase B.5 (Hibernate 6 cliff).

- **Blanket `@SuppressWarnings("all")` on 987 heritage Java files** (entities, DAOs, beans). Targets only heritage code identified by the audit; new code stays unsuppressed.
- **Codemods**:
  - Boxed-primitive cleanup (Integer/Long autoboxing on hot DAO paths).
  - `new URL(String)` → `URI.create(String).toURL()` on 11 sites.
  - Hibernate Query method renames (`createQuery(...).list()` → `.getResultList()`, etc.) consolidated under class-level `@SuppressWarnings("deprecation")` for the deferred Hibernate 6 work.
  - Dead-code deletion (20 imports + 8 locals with side-effect preservation — the local-binding-removal codemod converts `Type ident = expr;` → `expr;` when the RHS isn't pure so the side effect is kept).
- **TODO triage**: 192 + 80 + 16 stale heritage TODOs removed via codemod; remaining substantive ones converted to `NOTE:` prefix so they show up under JDT's "notes" category instead of "tasks". 127 stale markers stripped of their trailing `// TODO` suffix.
- **`AuditableEntityBean.setOwnerId` / `setUpdaterId`** un-deprecated. They were deprecated as part of an aborted "all id-setters go through a builder" refactor; the rest of the codebase calls them ubiquitously. -35 JDT warnings.
- **Resource-leak suppressions** on 3 heritage workbook files (Apache POI tables that close out-of-band via the workbook's own lifecycle).
- **MIGRATION.md refreshed** — Phases 0 / A / B / C / D-Sec all marked closed; heritage-debt audit doc added ahead of Phase B.5. See [docs/development/modernization/](../development/modernization/).

## Beta-testing checklist

### Smoke (matches release-image-workflow validation)

- [ ] `ghcr.io/luviku/libreclinicamuw:1.5.0-beta.5-muw` pulls cleanly + Tomcat reaches healthy.
- [ ] `ghcr.io/luviku/libreclinicamuw/retinal-inference:1.5.0-beta.5-muw` ditto.
- [ ] `LoginView` footer renders `v1.5.0-beta.5-muw · Build <yyyy-MM-dd> · <7-char-sha>`.
- [ ] Browser tab favicon shows the eye + coral pupil (hard-refresh after rollout to bust cache).

### Layer-correction flow

- [ ] Inference-job fullscreen on a `layers` or `bm` task → left-side tool palette appears for `INVESTIG` / `DATA_MGR`. Monitor logs in → palette hidden + overlay read-only.
- [ ] Pick "Stützpunkte", click RPE chip → 17 control points appear immediately along the existing curve. Drag one downward → curve re-fits.
- [ ] Pick "Schicht verschieben" → drag down → whole ILM polyline shifts. Press ↑/↓ → 1 px steps. Press Shift+↑/↓ → 5 px steps. Press Ctrl/⌘+Z → reverts the last step. Click the ↶ button → equivalent.
- [ ] Pick "Freihand", redraw a stretch of BM → drawn segment replaces only the affected x-range.
- [ ] Save → POST fires for each edited layer; toast confirms count. Reload → corrected polylines re-render from the merged envelope; small "korrigiert von <user> am <date>" badge on the affected layer chips.
- [ ] Toggle the ETDRS button on the masthead → amber 1 / 3 / 6 mm markers appear at the correct lateral A-scan columns. For a 49-slice cube the central 1 mm ring is visible on slices ~21–29 only. Reload page → toggle state preserved.
- [ ] Collapse the layer bar via the right-side chevron → bar docks to the left edge, shows only the active-layer chip + expand arrow. Re-expand restores it.
- [ ] nAMD compare-tab fullscreen → masthead ETDRS toggle → both panes toggle together. Inline (non-fullscreen) nAMD viewer → no toggle, no markers (per design).
- [ ] CST chip + Δ-vs-prev re-render automatically after a save against the foveal slice.
- [ ] `DELETE /retinal-jobs/{id}/segmentation/corrections/{layerIndex}` against a corrected layer → row + file removed, polyline reverts to AI output, badge disappears.

### Branding

- [ ] Hard-refresh `/LibreClinica/app/` → tab icon shows the eye on navy. iOS "Add to Home Screen" yields the salmon-eye apple-touch icon. Android Chrome address bar tints navy.
- [ ] Open any legacy JSP page (admin, manage-study, double-data-entry, monitor extract) → header shows the small horizontal "LibreClinica MUW" lockup with the eye + coral pupil. Stale `/images/Logo.gif` references are gone.
- [ ] Hit the SPA login route → wordmark + tagline render with the new eye glyph (not the grid placeholder).
- [ ] Hit the legacy login route (`/LibreClinica/MainMenu` for an unauth session) → `#logo` background shows the larger lockup with the "Augenheilkunde · Medizinische Universität Wien" tagline.

## Known issues

Carried unchanged from beta.4 — see [release-notes-1.5.0-beta.4-muw.md § Known issues](release-notes-1.5.0-beta.4-muw.md#known-issues) for the items still under operator validation. No new known issues introduced by this batch.
