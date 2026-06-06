/**
 * Phase E.6 — Ophthalmology bilateral examination preset catalog.
 *
 * <p>A curated catalog of ~15 standard ophthalmology measurements used
 * at the MUW Ophthalmology eCRF authoring side. Each entry describes
 * one bilateral measurement (i.e. one paired OD / OS field on the
 * examination mask). The CRF authoring wizard surfaces this catalog
 * via a "Add Ophthalmology bilateral examination" picker; the operator
 * selects which measurements to include, the section generator pairs
 * each selection into two items (one per eye), and the wizard appends
 * them to a single non-repeating OPHTH_EXAM item group.
 *
 * <p><b>Clinical convention</b>: OD (oculus dexter, right eye) renders
 * on the <b>left</b> of the examination mask; OS (oculus sinister, left
 * eye) renders on the <b>right</b>. This mirrors the face-to-face
 * clinician/patient seating arrangement and is the universal
 * ophthalmology examination-form convention. The renderer enforces the
 * convention; the {@link OphthPresetItem.laterality} metadata is the
 * source of truth.
 *
 * <p><b>Wire shape</b>: the persisted {@code item.oid} (e.g.
 * {@code OD_BCVA_LETTERS}) is what reaches the backend. The
 * {@code laterality} + {@code bilateralPair} fields are
 * <i>wizard-only</i> client-side metadata: they're consulted by the
 * SPA renderer to lay out the examination mask in OD-left / OS-right
 * order, but they're not part of the {@code CrfVersionAuthoringRequest}
 * payload — the eye affinity is encoded in the {@code OD_} / {@code OS_}
 * OID prefix.
 *
 * <p>Backend compatibility: every entry's {@link OphthPresetEntry.dataType}
 * is one of the canonical Milestone B authoring data types
 * (ST / INT / REAL / DATE / PDATE / FILE / BL). BL re-entered the
 * taxonomy alongside this preset — see {@link AuthoringDataType} in
 * {@code crfAuthoring.ts}.
 */

import type { AuthoringDataType, AuthoringItem } from '@/stores/crfAuthoring'

/**
 * Eye affinity for a single examination item. Used by the SPA renderer
 * to lay out the bilateral mask in OD-left / OS-right order.
 *
 * <ul>
 *   <li>{@code 'OD'} — oculus dexter (right eye, renders LEFT)</li>
 *   <li>{@code 'OS'} — oculus sinister (left eye, renders RIGHT)</li>
 *   <li>{@code 'OU'} — oculus uterque (both eyes, single-row entry)</li>
 * </ul>
 */
export type OphthLaterality = 'OD' | 'OS' | 'OU'

/**
 * One catalog entry — a single bilateral measurement. The entry is
 * eye-agnostic; the {@link generateOphthSection} helper expands each
 * selected entry into a paired OD + OS item.
 */
export interface OphthPresetEntry {
  /** Stable catalog key, e.g. {@code BCVA_LETTERS}. Used as the suffix on the generated OIDs. */
  key: string
  /** i18n key resolving to the human label, e.g. {@code ophthPreset.entry.BCVA_LETTERS.label}. */
  labelKey: string
  /** Authoring data type. Matches {@link AuthoringDataType}. */
  dataType: AuthoringDataType
  /** Inclusive numeric range — only meaningful for INT / REAL. {@code null} for BL / ST. */
  range: { min: number; max: number } | null
  /** Display unit (e.g. {@code mmHg}, {@code µm}, {@code D}). Empty string when not applicable (BL, dimensionless). */
  unit: string
  /**
   * Whether the measurement is bilateral by default. All current entries
   * are bilateral, but the flag is explicit so a future OU-only entry
   * (e.g. a global "examined by" field) can opt out cleanly.
   */
  defaultBilateral: true
}

/**
 * The catalog. ~15 standard ophthalmology bilateral measurements
 * curated for the MUW Ophthalmology eCRF authoring side. Order is
 * stable so the picker checklist renders deterministically.
 */
export const OPHTH_PRESET_CATALOG: ReadonlyArray<OphthPresetEntry> = [
  {
    key: 'BCVA_LETTERS',
    labelKey: 'ophthPreset.entry.BCVA_LETTERS.label',
    dataType: 'INT',
    range: { min: 0, max: 100 },
    unit: 'letters',
    defaultBilateral: true,
  },
  {
    key: 'BCVA_SNELLEN',
    labelKey: 'ophthPreset.entry.BCVA_SNELLEN.label',
    dataType: 'ST',
    range: null,
    unit: '',
    defaultBilateral: true,
  },
  {
    key: 'IOP',
    labelKey: 'ophthPreset.entry.IOP.label',
    dataType: 'INT',
    range: { min: 0, max: 80 },
    unit: 'mmHg',
    defaultBilateral: true,
  },
  {
    key: 'SPECTRALIS_DONE',
    labelKey: 'ophthPreset.entry.SPECTRALIS_DONE.label',
    dataType: 'BL',
    range: null,
    unit: '',
    defaultBilateral: true,
  },
  {
    key: 'PLEX_ELITE_DONE',
    labelKey: 'ophthPreset.entry.PLEX_ELITE_DONE.label',
    dataType: 'BL',
    range: null,
    unit: '',
    defaultBilateral: true,
  },
  // Refraction quartet. MUW Ophthalmology nomenclature:
  // Sphere (D) / Torus (cylinder, D) / Angle (axis, °) / Visus
  // (refraction-corrected visual acuity, decimal 0-2). Operators
  // typically grab all four together; they're kept as four flat
  // catalog entries so single-eye partial refractions still work.
  {
    key: 'REFRACTION_SPHERE',
    labelKey: 'ophthPreset.entry.REFRACTION_SPHERE.label',
    dataType: 'REAL',
    range: { min: -30, max: 30 },
    unit: 'D',
    defaultBilateral: true,
  },
  {
    key: 'REFRACTION_TORUS',
    labelKey: 'ophthPreset.entry.REFRACTION_TORUS.label',
    dataType: 'REAL',
    range: { min: -10, max: 10 },
    unit: 'D',
    defaultBilateral: true,
  },
  {
    key: 'REFRACTION_ANGLE',
    labelKey: 'ophthPreset.entry.REFRACTION_ANGLE.label',
    dataType: 'INT',
    range: { min: 0, max: 180 },
    unit: '°',
    defaultBilateral: true,
  },
  {
    key: 'REFRACTION_VISUS',
    labelKey: 'ophthPreset.entry.REFRACTION_VISUS.label',
    dataType: 'REAL',
    range: { min: 0, max: 2 },
    unit: '',
    defaultBilateral: true,
  },
  {
    key: 'CCT',
    labelKey: 'ophthPreset.entry.CCT.label',
    dataType: 'INT',
    range: { min: 200, max: 800 },
    unit: 'µm',
    defaultBilateral: true,
  },
  {
    key: 'CRT',
    labelKey: 'ophthPreset.entry.CRT.label',
    dataType: 'INT',
    range: { min: 100, max: 800 },
    unit: 'µm',
    defaultBilateral: true,
  },
  {
    key: 'FUNDUS_PHOTO_DONE',
    labelKey: 'ophthPreset.entry.FUNDUS_PHOTO_DONE.label',
    dataType: 'BL',
    range: null,
    unit: '',
    defaultBilateral: true,
  },
  {
    key: 'VISUAL_FIELD_DONE',
    labelKey: 'ophthPreset.entry.VISUAL_FIELD_DONE.label',
    dataType: 'BL',
    range: null,
    unit: '',
    defaultBilateral: true,
  },
  {
    key: 'OCT_A_DONE',
    labelKey: 'ophthPreset.entry.OCT_A_DONE.label',
    dataType: 'BL',
    range: null,
    unit: '',
    defaultBilateral: true,
  },
  {
    key: 'ACD',
    labelKey: 'ophthPreset.entry.ACD.label',
    dataType: 'REAL',
    range: { min: 0.5, max: 5.0 },
    unit: 'mm',
    defaultBilateral: true,
  },
  {
    key: 'AXIAL_LENGTH',
    labelKey: 'ophthPreset.entry.AXIAL_LENGTH.label',
    dataType: 'REAL',
    range: { min: 18, max: 32 },
    unit: 'mm',
    defaultBilateral: true,
  },
] as const

/**
 * The name of the item group the preset generator emits. Non-repeating
 * by convention — one ophthalmology examination per visit per subject.
 */
export const OPHTH_GROUP_NAME = 'OPHTH_EXAM'

/**
 * Look up a catalog entry by key. Returns {@code undefined} if the
 * key is unknown (caller should treat that as a programmer error).
 */
export function findPresetEntry(key: string): OphthPresetEntry | undefined {
  return OPHTH_PRESET_CATALOG.find((e) => e.key === key)
}

/**
 * Resolve an entry's display label to plain text. Defers to the
 * supplied {@code translate} function so the generator stays
 * i18n-agnostic (the wizard passes {@code t()} from {@code useI18n()};
 * tests pass an identity-like stub).
 */
export type Translator = (key: string) => string

/**
 * Format the human-visible item name for a generated pair, e.g.
 * {@code "BCVA letters"} (or {@code "BCVA letters (mmHg)"} when a unit
 * is present). The same string is used as both the wizard's
 * {@code item.name} and the {@code descriptionLabel} — operators can
 * tweak either after generation.
 */
function formatItemName(entry: OphthPresetEntry, translate: Translator): string {
  const label = translate(entry.labelKey)
  if (entry.unit) return `${label} (${entry.unit})`
  return label
}

/**
 * Build the {@code units} field on the generated item. For BL entries
 * the wizard surfaces no unit; for measurement entries the unit string
 * from the catalog is used verbatim.
 */
function unitsFor(entry: OphthPresetEntry): string {
  if (entry.dataType === 'BL') return ''
  return entry.unit
}

/**
 * Compose a regex (and matching error message) from the catalog
 * entry's range. INT becomes a digit pattern with the bounds enforced
 * in the wizard's validation error message; REAL accepts an optional
 * sign + optional decimal. BL / ST entries get no validation.
 *
 * <p>The regex is intentionally permissive — the wizard exposes one
 * pattern string per item and the backend parser re-runs the standard
 * INT / REAL range checks. The point here is to nudge operators with a
 * sensible default they can refine.
 */
function validationFor(entry: OphthPresetEntry, translate: Translator): { regexp: string; errorMessage: string } {
  if (entry.range == null) return { regexp: '', errorMessage: '' }
  const { min, max } = entry.range
  // The regex is operator-facing nudge only; the backend rejects
  // out-of-range numerics via the standard data_type=INT/REAL checks
  // regardless of the regex pattern.
  if (entry.dataType === 'INT') {
    return {
      regexp: '^-?\\d+$',
      errorMessage: translate('ophthPreset.validation.intRange')
        .replace('{min}', String(min))
        .replace('{max}', String(max)),
    }
  }
  if (entry.dataType === 'REAL') {
    return {
      regexp: '^-?\\d+(\\.\\d+)?$',
      errorMessage: translate('ophthPreset.validation.realRange')
        .replace('{min}', String(min))
        .replace('{max}', String(max)),
    }
  }
  return { regexp: '', errorMessage: '' }
}

/**
 * BL preset entries that semantically mean "imaging modality performed?".
 * For these entries the generator emits a follow-up "reason if not done"
 * text item per eye, gated by a show-when rule on the parent boolean.
 * Operators can delete the reason items in the wizard if a particular
 * study doesn't need them.
 */
const IMAGING_BL_KEYS: ReadonlySet<string> = new Set([
  'SPECTRALIS_DONE',
  'PLEX_ELITE_DONE',
  'FUNDUS_PHOTO_DONE',
  'VISUAL_FIELD_DONE',
  'OCT_A_DONE',
])

/**
 * Build the OD or OS follow-up "reason if not done" item for an imaging
 * BL entry. The follow-up is a text field that's only shown at runtime
 * when the parent boolean was answered "No" (literal {@code '0'}) — the
 * runtime renderer keeps hidden values in client state but does NOT
 * persist them, so toggling Yes after typing a reason cleanly suppresses
 * the reason from the submission (Phase E.6 show-when spec).
 *
 * <p>Item OID convention: {@code OD_<KEY>_REASON} / {@code OS_<KEY>_REASON}.
 * The {@code _REASON} suffix makes the bilateral grid group OD/OS
 * follow-ups into their own row (the grid pairs by exact OID suffix).
 */
function buildImagingReasonFollowup(
  entry: OphthPresetEntry,
  eye: 'OD' | 'OS',
  translate: Translator,
): Omit<AuthoringItem, 'uid'> {
  const parentLabel = translate(entry.labelKey)
  const reasonSuffix = translate('ophthPreset.entry.reasonIfNotDone')
  const oidParent = `${eye}_${entry.key}`
  const oidReason = `${eye}_${entry.key}_REASON`
  const oidPair = `${eye === 'OD' ? 'OS' : 'OD'}_${entry.key}_REASON`
  return {
    name: oidReason,
    oid: oidReason,
    descriptionLabel: `${parentLabel} — ${reasonSuffix}`,
    leftItemText: eye,
    rightItemText: '',
    units: '',
    dataType: 'ST',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: { regexp: '', errorMessage: '' },
    laterality: eye,
    bilateralPair: oidPair,
    showWhen: {
      sourceItemOid: oidParent,
      comparator: '==',
      literal: '0',
    },
  }
}

/**
 * Generate one paired (OD, OS) item tuple for a single catalog entry.
 *
 * <p>OD is emitted first so the wire-side ordering matches the
 * face-to-face renderer convention (OD on the LEFT). The
 * {@code bilateralPair} cross-link lets the renderer find the
 * opposite-eye sibling without a separate index.
 *
 * <p>For imaging BL entries (see {@link IMAGING_BL_KEYS}) the generator
 * additionally emits an OD + OS "reason if not done" text follow-up
 * gated by a show-when rule on the parent boolean. The result is
 * {@code [OD_DONE, OS_DONE, OD_REASON, OS_REASON]} so the bilateral
 * grid renders the imaging done? row first and the reason row directly
 * below it.
 *
 * <p>BL items render as the canonical Yes(1)/No(0) selector at runtime
 * via the BL-specific branch of {@code buildResponseSetPayload} — the
 * preset doesn't override {@code responseType} for BL, the data type
 * does the work.
 *
 * <p>The {@code uid} field is left empty — callers (the store) inject
 * a stable uid via the same {@code nextUid()} helper used by manual
 * {@code addItem()} so vuedraggable's item-key stays unique.
 */
export function generateBilateralPair(
  entry: OphthPresetEntry,
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  // `descriptionLabel` is the human-visible label (e.g. "BCVA letters
  // (mmHg)") — operators see it on the per-item editor row. `name` is
  // the workbook column name and the wizard's canSubmit guard enforces
  // `\w+`, so we reuse the OID (which is already `\w+` by construction).
  const description = formatItemName(entry, translate)
  const units = unitsFor(entry)
  const validation = validationFor(entry, translate)
  const oidOd = `OD_${entry.key}`
  const oidOs = `OS_${entry.key}`
  const odItem: Omit<AuthoringItem, 'uid'> = {
    name: oidOd,
    oid: oidOd,
    descriptionLabel: description,
    leftItemText: 'OD',
    rightItemText: '',
    units,
    dataType: entry.dataType,
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation,
    laterality: 'OD',
    bilateralPair: oidOs,
  }
  const osItem: Omit<AuthoringItem, 'uid'> = {
    name: oidOs,
    oid: oidOs,
    descriptionLabel: description,
    leftItemText: 'OS',
    rightItemText: '',
    units,
    dataType: entry.dataType,
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation,
    laterality: 'OS',
    bilateralPair: oidOd,
  }
  if (entry.dataType === 'BL' && IMAGING_BL_KEYS.has(entry.key)) {
    return [
      odItem,
      osItem,
      buildImagingReasonFollowup(entry, 'OD', translate),
      buildImagingReasonFollowup(entry, 'OS', translate),
    ]
  }
  return [odItem, osItem]
}

/**
 * Generate the full set of items for a multi-entry selection. Returns
 * a flat array of paired {@code [OD, OS, OD, OS, …]} items in the same
 * order as the input selection. Unknown keys are silently skipped — the
 * picker UI is expected to only emit known keys, so an unknown key is
 * a programmer error rather than user input.
 *
 * <p>Example: passing {@code ['BCVA_LETTERS', 'IOP']} returns four
 * items in the order:
 * <ol>
 *   <li>{@code OD_BCVA_LETTERS} (laterality=OD, bilateralPair=OS_BCVA_LETTERS)</li>
 *   <li>{@code OS_BCVA_LETTERS} (laterality=OS, bilateralPair=OD_BCVA_LETTERS)</li>
 *   <li>{@code OD_IOP} (laterality=OD, bilateralPair=OS_IOP)</li>
 *   <li>{@code OS_IOP} (laterality=OS, bilateralPair=OD_IOP)</li>
 * </ol>
 */
export function generateOphthSectionItems(
  selectedKeys: ReadonlyArray<string>,
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  const out: Array<Omit<AuthoringItem, 'uid'>> = []
  for (const key of selectedKeys) {
    const entry = findPresetEntry(key)
    if (!entry) continue
    out.push(...generateBilateralPair(entry, translate))
  }
  return out
}

/** Helper exported for tests / dev tools — full list of catalog keys. */
export function allPresetKeys(): string[] {
  return OPHTH_PRESET_CATALOG.map((e) => e.key)
}

/** Filter the catalog by data type — handy for the picker UI's grouping. */
export function presetEntriesByDataType(dataType: AuthoringDataType): OphthPresetEntry[] {
  return OPHTH_PRESET_CATALOG.filter((e) => e.dataType === dataType)
}
