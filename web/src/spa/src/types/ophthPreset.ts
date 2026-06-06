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
  {
    key: 'REFRACTION_SPHERE',
    labelKey: 'ophthPreset.entry.REFRACTION_SPHERE.label',
    dataType: 'REAL',
    range: { min: -30, max: 30 },
    unit: 'D',
    defaultBilateral: true,
  },
  {
    key: 'REFRACTION_CYLINDER',
    labelKey: 'ophthPreset.entry.REFRACTION_CYLINDER.label',
    dataType: 'REAL',
    range: { min: -10, max: 10 },
    unit: 'D',
    defaultBilateral: true,
  },
  {
    key: 'REFRACTION_AXIS',
    labelKey: 'ophthPreset.entry.REFRACTION_AXIS.label',
    dataType: 'INT',
    range: { min: 0, max: 180 },
    unit: '°',
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
 * Generate one paired (OD, OS) item tuple for a single catalog entry.
 *
 * <p>OD is emitted first so the wire-side ordering matches the
 * face-to-face renderer convention (OD on the LEFT). The
 * {@code bilateralPair} cross-link lets the renderer find the
 * opposite-eye sibling without a separate index.
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
    // All preset items default to a single-line text response — the
    // operator can flip BL items to a radio Yes/No via the per-item
    // editor after generation. Keeping the response type uniform here
    // avoids surfacing the response-set picker for unedited BL rows
    // and keeps the implicit open-text branch in {@link buildResponseSetPayload}.
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
    // All preset items default to a single-line text response — see the
    // note on the OD item above.
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation,
    laterality: 'OS',
    bilateralPair: oidOd,
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
