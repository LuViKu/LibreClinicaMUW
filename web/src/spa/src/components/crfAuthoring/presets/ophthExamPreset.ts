/**
 * App-feedback Wave 2 (2026-06-19) — OPHTH_EXAM canvas-preset adapter.
 *
 * <p>The full Ophthalmology bilateral examination catalog already lives
 * in {@code /types/ophthPreset.ts} and produces paired OD / OS items via
 * {@link generateOphthSectionItems}. This adapter is the canvas-side
 * thin wrapper that:
 *
 * <ul>
 *   <li>exposes the OPHTH_EXAM catalog under a single canvas preset id
 *       so the {@link presetCatalog} registry can list it next to the
 *       IOP preset, and</li>
 *   <li>generates a default selection (the most clinically common
 *       quartet — BCVA letters + IOP + CCT + CRT) when the operator
 *       drops the preset without going through the per-key picker.
 *       The picker remains available via the legacy wizard or a future
 *       picker dialog on the canvas; this adapter unblocks the
 *       one-click drop.</li>
 * </ul>
 *
 * <p>The {@code bilateral} section flag is set by the canvas {@code
 * applyPreset} when this preset is dropped — operators get the OD-left
 * / OS-right grid layout for free.
 */

import type { AuthoringItem } from '@/stores/crfAuthoring'
import {
  OPHTH_PRESET_CATALOG,
  generateOphthSectionItems,
  type Translator,
} from '@/types/ophthPreset'

/**
 * Canvas-preset id for the OPHTH_EXAM bundle. Keep stable — referenced
 * by the {@link presetCatalog} registry and any tests.
 */
export const OPHTH_EXAM_PRESET_ID = 'ophthExam'

/**
 * Default selection if the canvas drops the preset directly (no picker).
 * The four entries form a one-click "standard examination row" the team
 * uses for almost every visit. Operators can delete unwanted items or
 * add more via the legacy wizard picker.
 */
const DEFAULT_SELECTION: ReadonlyArray<string> = [
  'BCVA_LETTERS',
  'IOP',
  'CCT',
  'CRT',
]

/**
 * Produce the OPHTH_EXAM preset's items. Returns the paired OD / OS
 * items in the order produced by {@link generateOphthSectionItems}.
 *
 * <p>{@code selectedKeys} is optional — when omitted, the default
 * selection is used.
 */
export function generateOphthExamPresetItems(
  translate: Translator,
  selectedKeys?: ReadonlyArray<string>,
): Array<Omit<AuthoringItem, 'uid'>> {
  const keys = selectedKeys && selectedKeys.length > 0
    ? selectedKeys
    : DEFAULT_SELECTION
  return generateOphthSectionItems(keys, translate)
}

/**
 * Re-export the catalog so the picker UI can iterate without a second
 * import.
 */
export { OPHTH_PRESET_CATALOG }
