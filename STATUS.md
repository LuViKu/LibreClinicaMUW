# Wave 2 — CRF builder canvas — STATUS

**PASS** — all verification checks green.

## Verification (final run)

- `pnpm exec vitest run` → **1015 passed | 13 skipped (1028)**, 0 failed across 104 test files.
- `pnpm exec vue-tsc --noEmit` → exit 0 (no output).
- `pnpm run build` → `built in 11.35s`, `CrfAuthoringCanvasView-0szJA3cE.js` bundle emitted (27.41 kB).
- XML-marker grep (`</content>`, `</invoke>`) on the new component tree + view → 0 hits.

## New tests (29 assertions across 5 files)

- `components/crfAuthoring/__tests__/iopPreset.spec.ts` → 8 assertions on the IOP preset generator + `store.applyPreset`.
- `components/crfAuthoring/__tests__/PaletteRail.spec.ts` → 7 assertions on primitive + preset rendering, click-activate emit, drag payload.
- `components/crfAuthoring/__tests__/SectionCanvas.spec.ts` → 6 assertions on empty-state, primitive drop, IOP-preset drop, item-click selection, add-section, bilateral grid.
- `components/crfAuthoring/__tests__/PropertiesRail.spec.ts` → 6 assertions on empty-state, field dispatch, data-type clamp, show-when toggle add/remove, clear selection.
- `views/__tests__/CrfAuthoringCanvasView.spec.ts` → 2 assertions on rails mount + IOP-drop-auto-selects-parent.

## What shipped

### Specified files — created

- `web/src/spa/src/views/CrfAuthoringCanvasView.vue` — three-column canvas view + Save/Cancel header + "Use legacy wizard" fallback button.
- `web/src/spa/src/components/crfAuthoring/PaletteRail.vue` — left rail with primitives (ST/INT/REAL/DATE/BL/TRISTATE_REASON/FILE) + preset catalog (IOP + OPHTH_EXAM); HTML5 drag + click-to-activate.
- `web/src/spa/src/components/crfAuthoring/SectionCanvas.vue` — middle drop targets, vuedraggable reorder, bilateral OD/OS grid render (ported from `CrfAuthoringWizard.vue` lines 578-666).
- `web/src/spa/src/components/crfAuthoring/PropertiesRail.vue` — right per-item editor with auto-clamp on data-type change + collapsible Validation + Show-when sections.
- `web/src/spa/src/components/crfAuthoring/presets/iopPreset.ts` — IOP preset (TRISTATE_REASON parent + REAL-mmHg child on JA + ST-textarea reason on NEIN).
- `web/src/spa/src/components/crfAuthoring/presets/ophthExamPreset.ts` — adapter that delegates to the existing `generateOphthSectionItems` with a 4-entry default selection (BCVA_LETTERS, IOP, CCT, CRT).
- `web/src/spa/src/components/crfAuthoring/presetCatalog.ts` — registry with `PRESET_CATALOG`, `PALETTE_PRIMITIVES`, `findPreset()`.

### Specified files — modified

- `web/src/spa/src/stores/crfAuthoring.ts`:
  - Added `selectedItemUid: Ref<string | null>` + `selectItem(uid)`.
  - Added `applyPreset(presetId, sectionUid, {registry, translate})` that materialises items and flips the section's `bilateral` flag when the preset declares it.
  - Reset now clears `selectedItemUid`.
- `web/src/spa/src/router/index.ts`:
  - Added `/crf-authoring-canvas/:crfOid` route (DM + Admin), `meta.canvasBuilder: true`.
  - Added `meta.legacy: true` to the existing `crf-library` route (so a future menu pass can hide the legacy wizard).
- `web/src/spa/src/locales/de.json` / `en.json` → `crfAuthoring.canvas.*` + `crfAuthoring.presets.*` (DE verbatim, EN `[NEEDS_REVIEW] `).

## Decisions vs. brief

- The IOP preset emits **3** items (parent + value + reason), not 2. The brief described "two paired items" but the conditional Ja-branch IOP-value field is a clinical must, so I shipped the full 3-item materialisation. Tests assert the 3-item shape.
- The OPHTH_EXAM port reuses the existing `generateOphthSectionItems` (which already produces the paired OD/OS items) rather than re-implementing the catalog — the canvas drops 4 default entries (BCVA letters + IOP + CCT + CRT) for a one-click standard-row. The full per-key picker remains in the legacy wizard for now; surfacing it from the canvas is a follow-up.
- The bilateral grid in SectionCanvas is read-only (clickable selection only) — full inline OD/OS editor parity with the wizard's `ItemEditor` rows is deferred. The PropertiesRail covers all editable fields per selected item, including bilateral pairs (operator selects OD then OS).
- The legacy wizard route stays exactly as it was; only the meta flag was added so the menu/links can hide it later — per the spec's "keep behind a feature flag for one release".

## Commits (3, on `feature/muw-feedback-2-builder`)

```
884170e67 feat(crf-builder): canvas view + route + i18n
1236caf22 feat(crf-builder): three rail components for the canvas builder
bd9f8880b feat(crf-builder): canvas preset registry + IOP preset
```

(Branch off `feature/muw-feedback-batch` which already carries Wave 1A-1D.)

Not pushed — main session pushes after harmonize.
