/**
 * Canvas adapter for the OPHTH_EXAM catalog (types/ophthPreset.ts). On a plain
 * drop (no picker) it emits the default quartet (BCVA letters + IOP + CCT + CRT)
 * as paired OD/OS items; canvas applyPreset sets the bilateral flag (OD-left grid).
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
