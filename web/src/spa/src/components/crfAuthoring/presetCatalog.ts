/**
 * App-feedback Wave 2 (2026-06-19) — canvas preset registry.
 *
 * <p>Lists the presets the canvas's left rail surfaces in the "Preset
 * einfügen" section. Each entry pairs an opaque {@code id} with a
 * generator that materialises a list of items, plus presentation
 * metadata (i18n labelKey + descriptionKey).
 *
 * <p>The store ({@code crfAuthoring.ts}) dispatches on the id via
 * {@code applyPreset(id, sectionId)}; the canvas drop handler also
 * dispatches on the id when an entry is dragged into a section.
 *
 * <p>Adding a preset:
 *
 * <ol>
 *   <li>Create the generator under {@code presets/}.</li>
 *   <li>Add i18n keys under {@code crfAuthoring.presets.<id>.*}.</li>
 *   <li>Append a {@link PresetDescriptor} entry here.</li>
 * </ol>
 */

import type { AuthoringItem, AuthoringDataType } from '@/stores/crfAuthoring'
import { IOP_PRESET_ID, generateIopPresetItems } from './presets/iopPreset'
import {
  OPHTH_EXAM_PRESET_ID,
  generateOphthExamPresetItems,
} from './presets/ophthExamPreset'

export type PresetTranslator = (key: string) => string

/**
 * One canvas preset. The generator is responsible for producing the
 * full {@code Omit<AuthoringItem, 'uid'>[]} ordered list (parent first,
 * conditional children after); the store injects uids when applying.
 */
export interface PresetDescriptor {
  /** Stable id used by the store + tests. */
  id: string
  /** i18n key for the picker label. */
  labelKey: string
  /** i18n key for the picker description. */
  descriptionKey: string
  /**
   * When the preset's items are dropped into a section, set the
   * section's {@code bilateral} flag accordingly. Defaults to false.
   */
  bilateralSection?: boolean
  /**
   * Generate the preset's items. The translator is forwarded into
   * the preset generator so labels can be localised.
   */
  generate(translate: PresetTranslator): Array<Omit<AuthoringItem, 'uid'>>
}

export const PRESET_CATALOG: ReadonlyArray<PresetDescriptor> = [
  {
    id: IOP_PRESET_ID,
    labelKey: 'crfAuthoring.presets.iop.label',
    descriptionKey: 'crfAuthoring.presets.iop.description',
    bilateralSection: false,
    generate: (t) => generateIopPresetItems(t),
  },
  {
    id: OPHTH_EXAM_PRESET_ID,
    labelKey: 'crfAuthoring.presets.ophthExam.label',
    descriptionKey: 'crfAuthoring.presets.ophthExam.description',
    bilateralSection: true,
    generate: (t) => generateOphthExamPresetItems(t),
  },
]

export function findPreset(id: string): PresetDescriptor | undefined {
  return PRESET_CATALOG.find((p) => p.id === id)
}

/**
 * Authoring primitives surfaced in the palette rail. Order is operator-
 * visible.
 */
export interface PalettePrimitive {
  /** Data type the primitive materialises. */
  dataType: AuthoringDataType
  /** i18n label key. */
  labelKey: string
  /** i18n description key. */
  descriptionKey: string
}

export const PALETTE_PRIMITIVES: ReadonlyArray<PalettePrimitive> = [
  { dataType: 'ST', labelKey: 'crfAuthoring.canvas.palette.prim.ST.label', descriptionKey: 'crfAuthoring.canvas.palette.prim.ST.description' },
  { dataType: 'INT', labelKey: 'crfAuthoring.canvas.palette.prim.INT.label', descriptionKey: 'crfAuthoring.canvas.palette.prim.INT.description' },
  { dataType: 'REAL', labelKey: 'crfAuthoring.canvas.palette.prim.REAL.label', descriptionKey: 'crfAuthoring.canvas.palette.prim.REAL.description' },
  { dataType: 'DATE', labelKey: 'crfAuthoring.canvas.palette.prim.DATE.label', descriptionKey: 'crfAuthoring.canvas.palette.prim.DATE.description' },
  { dataType: 'BL', labelKey: 'crfAuthoring.canvas.palette.prim.BL.label', descriptionKey: 'crfAuthoring.canvas.palette.prim.BL.description' },
  { dataType: 'TRISTATE_REASON', labelKey: 'crfAuthoring.canvas.palette.prim.TRISTATE_REASON.label', descriptionKey: 'crfAuthoring.canvas.palette.prim.TRISTATE_REASON.description' },
  { dataType: 'FILE', labelKey: 'crfAuthoring.canvas.palette.prim.FILE.label', descriptionKey: 'crfAuthoring.canvas.palette.prim.FILE.description' },
]
