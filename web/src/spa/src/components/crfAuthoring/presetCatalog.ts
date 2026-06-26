/**
 * CRF authoring preset registry — id + generator + metadata. The store
 * dispatches on the id via {@code applyPreset(id, sectionId)}; the canvas
 * drop handler does likewise when an entry is dragged into a section.
 */

import type { AuthoringItem, AuthoringDataType } from '@/stores/crfAuthoring'
import { IOP_PRESET_ID, generateIopPresetItems } from './presets/iopPreset'
import {
  OPHTH_EXAM_PRESET_ID,
  generateOphthExamPresetItems,
} from './presets/ophthExamPreset'
import { BCVA_PRESET_ID, generateBcvaPresetItems } from './presets/bcvaPreset'
import { BCVA_DECIMAL_PRESET_ID, generateBcvaDecimalPresetItems } from './presets/bcvaDecimalPreset'
import { CRT_PRESET_ID, generateCrtPresetItems } from './presets/crtPreset'
import { RNFL_PRESET_ID, generateRnflPresetItems } from './presets/rnflPreset'
import {
  THICKNESS_MAP_PRESET_ID,
  generateThicknessMapPresetItems,
} from './presets/thicknessMapPreset'
import {
  SLIT_LAMP_PRESET_ID,
  generateSlitLampPresetItems,
} from './presets/slitLampPreset'
import {
  SPECTRALIS_OCT_PRESET_ID,
  generateSpectralisOctPresetItems,
} from './presets/spectralisOctPreset'
import {
  SPECTRALIS_FAF_PRESET_ID,
  generateSpectralisFafPresetItems,
} from './presets/spectralisFafPreset'
import {
  PLEX_ELITE_OCTA_PRESET_ID,
  generatePlexEliteOctaPresetItems,
} from './presets/plexEliteOctaPreset'
import {
  ZEISS_CLARUS_PRESET_ID,
  generateZeissClarusPresetItems,
} from './presets/zeissClarusPreset'
import {
  TOPCON_MAESTRO2_OCT_PRESET_ID,
  generateTopconMaestro2OctPresetItems,
} from './presets/topconMaestro2OctPreset'
import {
  PL_BCVA_PRESET_ID,
  LL_BCVA_PRESET_ID,
  generatePlBcvaPresetItems,
  generateLlBcvaPresetItems,
} from './presets/bcvaVariantPreset'

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
   * 2026-06-21 user-feedback round 3 — short, stable section tag the
   * canvas surfaces next to the section title (e.g. "IOP", "EXAM").
   * When omitted the section keeps its auto-numbered "S1"/"S2" tag.
   * The tag also drives the rendered `section.label` on the wire so
   * the backend's CRF JSON validator can keep its section-label
   * uniqueness check working.
   */
  sectionLabel?: string
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
    // 2026-06-21 — eye-related presets default bilateral per user
    // feedback. Section toggle lets the operator switch to unilateral
    // when only one eye is monitored (e.g. monocular follow-up).
    bilateralSection: true,
    sectionLabel: 'IOP',
    generate: (t) => generateIopPresetItems(t),
  },
  {
    id: OPHTH_EXAM_PRESET_ID,
    labelKey: 'crfAuthoring.presets.ophthExam.label',
    descriptionKey: 'crfAuthoring.presets.ophthExam.description',
    bilateralSection: true,
    sectionLabel: 'EXAM',
    generate: (t) => generateOphthExamPresetItems(t),
  },
  {
    id: BCVA_PRESET_ID,
    labelKey: 'crfAuthoring.presets.bcva.label',
    descriptionKey: 'crfAuthoring.presets.bcva.description',
    bilateralSection: true,
    sectionLabel: 'BCVA',
    generate: (t) => generateBcvaPresetItems(t),
  },
  {
    // 2026-06-24 user-feedback round — decimal-flavoured BCVA preset
    // backing the public BCVA-entry portal. Captures the
    // autorefractometer's native decimal + signed partial offset
    // rather than ETDRS letters.
    id: BCVA_DECIMAL_PRESET_ID,
    labelKey: 'crfAuthoring.presets.bcvaDecimal.label',
    descriptionKey: 'crfAuthoring.presets.bcvaDecimal.description',
    bilateralSection: true,
    sectionLabel: 'BCVA_DEC',
    generate: (t) => generateBcvaDecimalPresetItems(t),
  },
  {
    // 2026-06-24 — CRT (central 1 mm) auto-populated by the GA + BM
    // job pair. Operators can override; the audit trail records the
    // source ga_job_id + bm_job_id.
    id: CRT_PRESET_ID,
    labelKey: 'crfAuthoring.presets.crt.label',
    descriptionKey: 'crfAuthoring.presets.crt.description',
    bilateralSection: true,
    sectionLabel: 'CRT',
    generate: (t) => generateCrtPresetItems(t),
  },
  {
    id: RNFL_PRESET_ID,
    labelKey: 'crfAuthoring.presets.rnfl.label',
    descriptionKey: 'crfAuthoring.presets.rnfl.description',
    bilateralSection: true,
    sectionLabel: 'RNFL',
    generate: (t) => generateRnflPresetItems(t),
  },
  {
    id: THICKNESS_MAP_PRESET_ID,
    labelKey: 'crfAuthoring.presets.thicknessMap.label',
    descriptionKey: 'crfAuthoring.presets.thicknessMap.description',
    bilateralSection: true,
    sectionLabel: 'THICK',
    generate: (t) => generateThicknessMapPresetItems(t),
  },
  {
    id: SLIT_LAMP_PRESET_ID,
    labelKey: 'crfAuthoring.presets.slitLamp.label',
    descriptionKey: 'crfAuthoring.presets.slitLamp.description',
    bilateralSection: true,
    sectionLabel: 'SLIT',
    generate: (t) => generateSlitLampPresetItems(t),
  },
  // 2026-06-23 user-feedback round — device-specific imaging acquisition
  // presets. Each materialises the same minimum acquisition pattern
  // (acquired? / quality / failure reason / notes) with a per-device
  // OID prefix so a single visit can carry multiple modalities
  // side-by-side without OID collisions.
  {
    id: SPECTRALIS_OCT_PRESET_ID,
    labelKey: 'crfAuthoring.presets.spectralisOct.label',
    descriptionKey: 'crfAuthoring.presets.spectralisOct.description',
    bilateralSection: true,
    sectionLabel: 'SPEC_OCT',
    generate: (t) => generateSpectralisOctPresetItems(t),
  },
  {
    id: SPECTRALIS_FAF_PRESET_ID,
    labelKey: 'crfAuthoring.presets.spectralisFaf.label',
    descriptionKey: 'crfAuthoring.presets.spectralisFaf.description',
    bilateralSection: true,
    sectionLabel: 'SPEC_FAF',
    generate: (t) => generateSpectralisFafPresetItems(t),
  },
  {
    id: PLEX_ELITE_OCTA_PRESET_ID,
    labelKey: 'crfAuthoring.presets.plexEliteOcta.label',
    descriptionKey: 'crfAuthoring.presets.plexEliteOcta.description',
    bilateralSection: true,
    sectionLabel: 'OCTA',
    generate: (t) => generatePlexEliteOctaPresetItems(t),
  },
  {
    id: ZEISS_CLARUS_PRESET_ID,
    labelKey: 'crfAuthoring.presets.zeissClarus.label',
    descriptionKey: 'crfAuthoring.presets.zeissClarus.description',
    bilateralSection: true,
    sectionLabel: 'CLARUS',
    generate: (t) => generateZeissClarusPresetItems(t),
  },
  {
    id: TOPCON_MAESTRO2_OCT_PRESET_ID,
    labelKey: 'crfAuthoring.presets.topconMaestro2Oct.label',
    descriptionKey: 'crfAuthoring.presets.topconMaestro2Oct.description',
    bilateralSection: true,
    sectionLabel: 'TOPCON_OCT',
    generate: (t) => generateTopconMaestro2OctPresetItems(t),
  },
  {
    id: PL_BCVA_PRESET_ID,
    labelKey: 'crfAuthoring.presets.plBcva.label',
    descriptionKey: 'crfAuthoring.presets.plBcva.description',
    bilateralSection: true,
    sectionLabel: 'PL_BCVA',
    generate: (t) => generatePlBcvaPresetItems(t),
  },
  {
    id: LL_BCVA_PRESET_ID,
    labelKey: 'crfAuthoring.presets.llBcva.label',
    descriptionKey: 'crfAuthoring.presets.llBcva.description',
    bilateralSection: true,
    sectionLabel: 'LL_BCVA',
    generate: (t) => generateLlBcvaPresetItems(t),
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
