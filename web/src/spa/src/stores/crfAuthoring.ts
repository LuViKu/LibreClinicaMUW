import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import { markTerminologyOid, terminologySystemFromOid } from '@/components/terminologyOid'
import type { CrfVersion } from '@/types/crfLibrary'

/**
 * Phase E.6 Milestone B — manual eCRF authoring store.
 *
 * <p>Milestone A locked the scope to one section + two-three items +
 * one TEXT response set. Milestone B widens the surface to the full
 * non-formula taxonomy:
 *
 * <ul>
 *   <li>Data types {@code ST, INT, REAL, DATE, PDATE, FILE, BL} matching
 *       the backend {@code CrfVersionAuthoringRequest.Item.dataType}.
 *       {@code BL} (boolean) was restored from Milestone A in Phase E.6
 *       once the wizard taxonomy was reconciled with the legacy
 *       {@code item.data_type=11} branch the XLS uploader still emits.</li>
 *   <li>Response types {@code text, textarea, radio, single-select,
 *       multi-select, checkbox, file} (canonical names per
 *       {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResponseType}).</li>
 *   <li>Per-item validation (regexp + errorMessage), {@code defaultValue},
 *       {@code rightItemText}, {@code units}.</li>
 *   <li>Inline OR by-ref response set (catalog-pick via the
 *       cross-CRF {@code GET /api/v1/response-sets} virtual catalog).</li>
 * </ul>
 *
 * <p><b>Persistence strategy</b>: final-submit only. No POSTs fire
 * until the operator hits "Create" — closing the wizard discards the
 * local draft. Per the {@code :preview} dry-run endpoint, the SPA can
 * surface structured validation errors at the Review step without
 * partial commits.
 */

/**
 * Authoring data type — matches the canonical names accepted by
 * {@code CrfJsonValidator.ALLOWED_DATA_TYPES}. The legacy {@code INTEGER}
 * and {@code BOOLEAN} aliases still work on the wire but the SPA writes
 * the canonical short forms ({@code INT}, {@code BL}) per the
 * Milestone B contract.
 *
 * <p>{@code BL} (boolean, legacy {@code item.data_type=11}) was in the
 * Milestone A taxonomy, dropped during the B/C extension, and restored
 * here for the Phase E.6 Ophthalmology preset (BL is the canonical type
 * for "done / not done" examination flags). The wizard skips the
 * response-set picker for BL and the runtime renders it as a checkbox.
 * The backend adapter accepts BL via the same code path used by the
 * XLS uploader.
 *
 * <p>{@code TRISTATE_REASON} — "Ja / Nein / Unbekannt" with a
 * conditional reason textarea shown only on "Nein". NOT a wire-level
 * data type: at persistence it materialises as TWO {@code item_data}
 * rows — a parent (select-one, three options) plus a child string item
 * driven by show-when (parent == "Nein"). The render side opts the
 * parent into the three-pill widget via authoring dataType
 * TRISTATE_REASON.
 */
export type AuthoringDataType = 'ST' | 'INT' | 'REAL' | 'DATE' | 'PDATE' | 'FILE' | 'BL' | 'TRISTATE_REASON'

/**
 * Authoring response type — canonical names per the backend
 * {@code ResponseType}. CALCULATION variants are out of scope for
 * Milestone B (deferred to C).
 */
export type AuthoringResponseType =
  | 'text'
  | 'textarea'
  | 'radio'
  | 'single-select'
  | 'multi-select'
  | 'checkbox'
  | 'file'

/**
 * One option in a response set. Mirrors the backend
 * {@code CrfVersionAuthoringRequest.Option} record.
 */
export interface ResponseSetOption {
  text: string
  value: string
}

/**
 * Inline response set authored on the item. Mirrors
 * {@code CrfVersionAuthoringRequest.ResponseSet} (inline branch).
 */
export interface InlineResponseSet {
  type: AuthoringResponseType
  label: string
  options: ResponseSetOption[]
}

/**
 * By-reference response set — operator picked a catalog entry. The
 * controller re-materialises the inline definition by label before
 * synthesising the workbook. Mirrors
 * {@code CrfVersionAuthoringRequest.ResponseSetRef}.
 */
export interface ResponseSetRef {
  ref: { label: string }
}

export type AuthoringResponseSet = InlineResponseSet | ResponseSetRef | null

export interface AuthoringValidation {
  regexp: string
  errorMessage: string
}

/**
 * Comparator operators supported by the per-item show-when rule editor.
 * The set is intentionally small (6 binary comparators, no logical
 * combinators) per the locked Phase E.6 spec — single condition only.
 */
export type ShowWhenComparator = '==' | '!=' | '>' | '<' | '>=' | '<='

/**
 * Per-item show-when rule. The rule is a single binary comparison of
 * another item's value against a literal. When unset the item is
 * always shown.
 *
 * <p>The {@code sourceItemOid} references an item that is declared
 * BEFORE this one (the authoring picker enforces this so evaluation
 * order can't break at runtime). The {@code literal} is parsed by the
 * runtime renderer against the source item's data type — see Agent 3's
 * {@code crfEntry.ts} for the resolved-value comparison logic.
 *
 * <p>Hidden value handling is the runtime renderer's responsibility
 * (client-state only — values for hidden items are preserved in
 * reactive state but not POSTed). This authoring side only ships the
 * rule definition.
 */
export interface ShowWhenRule {
  sourceItemOid: string
  comparator: ShowWhenComparator
  literal: string
}

/**
 * Eye affinity for an item generated by the Ophthalmology bilateral
 * preset. Wizard-only client-side metadata — not part of the wire
 * payload. The renderer uses this to lay out OD on the LEFT and OS on
 * the RIGHT of the examination mask (face-to-face clinician/patient
 * convention). The persisted {@code item.oid} (e.g. {@code OD_BCVA_LETTERS})
 * is the source of truth on the backend.
 */
export type AuthoringLaterality = 'OD' | 'OS' | 'OU'

/**
 * #26 (2026-08-12) — terminology data source for an autocomplete-bound
 * text field. A loose string (not a closed union) so a new catalogue can
 * be ingested and wired up without a code change; the known values are
 * {@code 'icd10gm'} (ICD-10-GM diagnoses, ingested) and {@code 'medication'}
 * (ATC/drug catalogue — Slice 2, carries strength/unit/form properties the
 * fill map fans out).
 */
export type TerminologySystem = string

/**
 * One fill rule on an autocomplete binding: when the operator picks a
 * catalogue suggestion, copy the concept's {@code fromProperty} (a key in
 * the terminology row's JSONB properties, e.g. {@code 'strength'}) into a
 * sibling field named by {@code toKey}.
 *
 * <p>{@code toKey} resolves against the SAME repeating-table row when the
 * binding lives on a table column (matched by column {@link RepeatingTableColumn.key});
 * on a flat item it names a sibling item OID in the same section. Explicit
 * mapping (per the 2026-08-12 design decision) so it survives column
 * reordering and works for both table + flat fields.
 */
export interface AutocompleteFill {
  fromProperty: string
  toKey: string
}

/**
 * #26 — opt-in terminology autocomplete on a text field. Absent = plain
 * text input. When present the field renders {@code TerminologyAutocomplete}
 * against {@link system}; picking a suggestion writes "CODE — Display" into
 * the field and fans {@link fills} out into sibling fields.
 *
 * <p>Wizard-only for now: NOT serialised by {@code buildItemPayload} (the
 * workbook adapter has no column for it yet — that's the backend
 * follow-up). Preview + live-entry rendering read it directly from the
 * draft, so the behaviour is verifiable in-SPA today.
 */
export interface AutocompleteBinding {
  system: TerminologySystem
  fills: AutocompleteFill[]
}

/**
 * Column value kind for a repeating-table cell. {@code laterality} is an
 * ophthalmology fixed OD/OS/OU choice (right / left / both eyes) — useful on
 * an eye-diagnosis or per-eye medication table.
 */
export type RepeatingTableColumnType = 'text' | 'number' | 'date' | 'laterality'

/**
 * Fixed OD/OS/OU choice for a {@code laterality} column. Value = the Latin
 * standard code (persisted / stored); label carries the German gloss. See the
 * ophthalmology laterality convention: OD = right eye, OS = left, OU = both.
 */
export const LATERALITY_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'OD', label: 'OD (rechts)' },
  { value: 'OS', label: 'OS (links)' },
  { value: 'OU', label: 'OU (beide)' },
]

/**
 * One operator-defined column of a repeating-table item. A {@code text}
 * column may opt into terminology autocomplete via {@link autocomplete}.
 */
export interface RepeatingTableColumn {
  /** Stable key — the per-row cell address and the fill-map {@code toKey} target. */
  key: string
  label: string
  type: RepeatingTableColumnType
  autocomplete?: AutocompleteBinding
}

/**
 * #26 — a generic repeating-table item: the operator defines the columns
 * and the clinician adds/removes rows at entry time (medication list,
 * diagnosis list, …). Wizard-only metadata (see {@link AutocompleteBinding}).
 */
export interface RepeatingTableSpec {
  columns: RepeatingTableColumn[]
  minRows: number
  maxRows: number
}

/** Monotonic column-key source (`col_1`, `col_2`, …). Module-level, so it
 *  resets to 0 on every page load — {@link reseedTableColumnKeySeq} advances it
 *  past a restored draft's existing keys so newly-minted keys never collide. */
let tableColumnKeySeq = 0

/** A fresh blank text column with a unique {@link RepeatingTableColumn.key}. */
export function newRepeatingTableColumn(label = ''): RepeatingTableColumn {
  tableColumnKeySeq += 1
  return { key: `col_${tableColumnKeySeq}`, label, type: 'text' }
}

/**
 * A restored draft already carries column keys minted in an earlier session,
 * but the module counter resets to 0 on each page load. Without this, the next
 * {@link newRepeatingTableColumn} would re-mint `col_1` and collide with a
 * restored column — two columns sharing one key silently mirror each other's
 * cell state (a table's row store is keyed by column key). Advance the counter
 * past the highest `col_N` any restored table column holds.
 */
export function reseedTableColumnKeySeq(d: AuthoringDraft): void {
  let max = tableColumnKeySeq
  for (const section of d.sections ?? []) {
    for (const item of section.items ?? []) {
      for (const col of item.table?.columns ?? []) {
        const m = /^col_(\d+)$/.exec(col.key)
        if (m) max = Math.max(max, Number(m[1]))
      }
    }
  }
  tableColumnKeySeq = max
}

/**
 * Item OIDs must be unique across the whole CRF: the entry/preview store keys
 * every item's value by its OID, so two items sharing one OID silently share a
 * single slot — their cells mirror each other (observed live as a laterality
 * pick bleeding into a sibling table). Returns the offending items (trimmed,
 * non-empty OID appearing on more than one item); empty OIDs are left to
 * required-field validation. Deterministic order: draft order.
 */
export function findDuplicateOidItems(d: AuthoringDraft): { uid: string; oid: string }[] {
  const counts = new Map<string, number>()
  const items: { uid: string; oid: string }[] = []
  for (const section of d.sections ?? []) {
    for (const item of section.items ?? []) {
      const oid = item.oid.trim()
      if (!oid) continue
      items.push({ uid: item.uid, oid })
      counts.set(oid, (counts.get(oid) ?? 0) + 1)
    }
  }
  return items.filter((x) => (counts.get(x.oid) ?? 0) > 1)
}

/** Default two-column generic table seeded by the TABLE palette block. */
export function defaultRepeatingTableSpec(): RepeatingTableSpec {
  return { columns: [newRepeatingTableColumn(''), newRepeatingTableColumn('')], minRows: 1, maxRows: 20 }
}

export interface AuthoringItem {
  /**
   * Stable client-side identity used by vuedraggable's `item-key` so the
   * row doesn't remount when the operator-typed OID mutates — without
   * this, the auto-suggest watch on `name → oid` rewrites the key on
   * every keystroke and the focused input gets destroyed.
   */
  uid: string
  name: string
  oid: string
  descriptionLabel: string
  leftItemText: string
  rightItemText: string
  units: string
  dataType: AuthoringDataType
  responseType: AuthoringResponseType
  defaultValue: string
  required: boolean
  responseSet: AuthoringResponseSet
  validation: AuthoringValidation
  /**
   * Optional eye affinity — set by the Ophthalmology bilateral preset
   * generator. Wizard-only metadata (see {@link AuthoringLaterality}).
   */
  laterality?: AuthoringLaterality
  /**
   * Optional OID of the paired item on the opposite eye — set by the
   * Ophthalmology bilateral preset generator. Wizard-only metadata.
   */
  bilateralPair?: string
  /**
   * Optional show-when rule — when set the item is conditionally
   * shown at runtime based on another item's value. See
   * {@link ShowWhenRule} for the shape contract.
   */
  showWhen?: ShowWhenRule
  /**
   * Phase E.6 ophth-field-catalog (2026-06-11): when set, the backend
   * adapter (see {@code CrfJsonToWorkbookAdapter.materializeCatalogItems})
   * back-fills any blank item fields (descriptionLabel, leftItemText,
   * rightItemText, units, dataType, responseSet) from the matching
   * {@code ophth_field_catalog} row before synthesising the workbook.
   * The wizard populates this when the operator drops an item via the
   * "Pick from catalog" picker — the picker still stages the item with
   * a canonical OID + name so the operator can edit afterwards if
   * they want to drift from the catalog defaults.
   */
  catalogCode?: string
  /**
   * #26 (2026-08-12) — opt-in terminology autocomplete on a FLAT text
   * item (ST / text). When set the entry + preview render
   * {@code TerminologyAutocomplete}; {@code fills[].toKey} names sibling
   * item OIDs in the same section. Wizard-only (not persisted yet).
   */
  autocomplete?: AutocompleteBinding
  /**
   * #26 (2026-08-12) — when set, this item is a generic repeating table
   * (operator-defined columns; clinician adds/removes rows). The scalar
   * {@code dataType}/{@code responseType} are inert for a table item.
   *
   * <p>Persistence (Slice 3): {@code buildTableItemPayloads} expands this
   * into one grouped item per column (shared {@code groupLabel}), which the
   * backend materialises as an OpenClinica repeating item-group — so the
   * table STRUCTURE round-trips and renders at entry via RepeatingGroupSection.
   * The per-column terminology {@link AutocompleteBinding} is NOT persisted
   * yet (no backend column) — it stays preview-only until that follow-up.
   */
  table?: RepeatingTableSpec
}

export interface AuthoringSection {
  /** Stable uid for vuedraggable item-key; see {@link AuthoringItem.uid}. */
  uid: string
  label: string
  title: string
  instructions: string
  ordinal: number
  items: AuthoringItem[]
  /**
   * Phase E.6 ophth-bilateral — wizard-only flag. When {@code true} the
   * section renders in the OD-LEFT / OS-RIGHT bilateral grid (paired
   * items by OID suffix, compound rows for refraction-style quartets);
   * when {@code false} the section renders as a flat draggable list.
   *
   * <p>Source of truth for the runtime renderer is still the
   * {@code OD_} / {@code OS_} / {@code OU_} OID prefix on each item,
   * so the flag is NOT persisted to the backend — {@code buildPayload}
   * filters it out via the existing wizard-only whitelist.
   *
   * <p>Set by:
   * <ul>
   *   <li>The OPHTH_EXAM / OPHTHA_EXAM Shift+Enter hotkey when the
   *       picker confirms (replaceAtIndex path).</li>
   *   <li>The picker when it appends a new section.</li>
   *   <li>A toggle on each section's header (operators can flip any
   *       section into bilateral mode without going through the
   *       hotkey).</li>
   * </ul>
   */
  bilateral?: boolean
}

export interface AuthoringDraft {
  versionName: string
  versionDescription: string
  revisionNotes: string
  sections: AuthoringSection[]
}

/**
 * One entry in the cross-CRF response-set catalog. Mirrors
 * {@code ResponseSetCatalogEntry} (i.e. {@code ResponseSetDto}).
 */
export interface ResponseSetCatalogEntry {
  label: string
  responseType: AuthoringResponseType | string
  options: ResponseSetOption[]
  usageCount: number
  inActiveStudy: boolean
}

export type AuthoringSubmitResult =
  | { ok: true; version: CrfVersion }
  | { ok: false; fieldErrors: Record<string, string>; parseErrors: string[]; message?: string }

/**
 * 2026-06-21 user-feedback round 6 — wire shape for the fork-from-version
 * endpoint ({@code GET /pages/api/v1/crfs/{oid}/versions/{vid}/contents}).
 * Mirrors {@code CrfVersionAuthoringRequest} on the backend; only the
 * fields the SPA hydrates are typed (extra fields are ignored).
 */
export interface ForkContentsWire {
  versionName?: string
  versionDescription?: string
  revisionNotes?: string
  sections?: ForkSectionWire[]
  /** Fork recovery — per-group row bounds, matched to a reconstructed table
   *  by {@code label} (== the group label its columns carry). */
  groups?: Array<{ label?: string; repeatNumber?: number | null; repeatMax?: number | null }> | null
}

interface ForkSectionWire {
  label?: string
  title?: string
  instructions?: string
  ordinal?: number
  items?: ForkItemWire[]
}

interface ForkItemWire {
  name?: string
  oid?: string
  descriptionLabel?: string
  leftItemText?: string
  rightItemText?: string
  units?: string
  dataType?: string
  defaultValue?: string
  required?: boolean
  responseSet?: ForkResponseSetWire | null
  validation?: { regexp?: string; errorMessage?: string } | null
  /**
   * Fork recovery — the item's repeating-group label. Items sharing a
   * (non-blank) label are reconstructed into one repeating-table item
   * (the inverse of {@code buildTableItemPayloads}).
   */
  groupLabel?: string | null
  /**
   * Fork recovery — conditional display, surfaced on the legacy fields the
   * backend adapter round-trips: {@code parentItemOid} is the source item's
   * OID, {@code showItem} the trigger literal (from {@code scd_item_metadata}).
   * The legacy schema stores equality only, so we rehydrate comparator "==".
   */
  showItem?: string | null
  parentItemOid?: string | null
}

interface ForkResponseSetWire {
  type?: string
  label?: string
  options?: Array<{ text?: string; value?: string }>
  ref?: { label?: string } | null
}

export interface AuthoringPreviewSuccess {
  crfOid: string
  versionName: string
  sectionCount: number
  itemCount: number
}

export type AuthoringPreviewResult =
  | { ok: true; preview: AuthoringPreviewSuccess }
  | { ok: false; fieldErrors: Record<string, string>; parseErrors: string[]; message?: string }

/**
 * Response types that carry a finite option list — radio, single-/
 * multi-select, checkbox. The SPA wizard renders the response-set
 * picker exactly for these.
 */
const OPTION_RESPONSE_TYPES = new Set<AuthoringResponseType>([
  'radio',
  'single-select',
  'multi-select',
  'checkbox',
])

export function responseTypeRequiresOptions(t: AuthoringResponseType): boolean {
  return OPTION_RESPONSE_TYPES.has(t)
}

/**
 * BL (boolean) is a fixed yes/no — the wizard locks the response type
 * to {@code checkbox} and hides the response-set picker (the synthesised
 * workbook emits the canonical yes/no option list, matching what the
 * legacy XLS uploader does for {@code DATA_TYPE=BL}).
 */
export function dataTypeIsBoolean(t: AuthoringDataType): boolean {
  return t === 'BL'
}

/**
 * Datentyp → Antworttyp restriction matrix.
 *
 * <p>Locks the response-type dropdown so operators can only pick a
 * response shape that is compatible with the selected data type. The
 * spec matrix references conceptual response types like {@code
 * text-numeric}, {@code date}, {@code partial-date} and {@code
 * calculation} that aren't (yet) members of the canonical
 * {@link AuthoringResponseType} union — those collapse onto the
 * closest existing entry (e.g. {@code INT} → {@code text} for the
 * "text-numeric" bucket). Specialised input shapes will be added in a
 * later phase without breaking this contract.
 *
 * <p>Per the brief: the union itself is NOT shrunk; we filter at the
 * UI layer only. Persisted CRFs that already use a response type
 * outside the allowed set continue to round-trip unchanged.
 *
 * <p>Returned ordering matches the canonical {@link
 * AuthoringResponseType} union so the dropdown preserves the same
 * visual order as the unfiltered list.
 */
export function allowedResponseTypesForDataType(
  dt: AuthoringDataType,
): AuthoringResponseType[] {
  switch (dt) {
    case 'ST':
      return ['text', 'textarea', 'radio', 'single-select', 'multi-select', 'checkbox']
    case 'INT':
      // text covers the numeric input bucket; radio/single-select
      // cover Likert-style discrete options.
      return ['text', 'radio', 'single-select']
    case 'REAL':
      // text covers the numeric input bucket. Calculation is deferred.
      return ['text']
    case 'DATE':
      // No date-specific entry in the response-type union yet — fall
      // back to text so the operator can still pick something.
      return ['text']
    case 'PDATE':
      return ['text']
    case 'FILE':
      return ['file']
    case 'BL':
      // BL is hardwired to a fixed Yes/No single-select; the wizard
      // disables the dropdown but the allowed set is still {single-select}.
      return ['single-select']
    case 'TRISTATE_REASON':
      // App-feedback Wave 1D — Ja/Nein/Unbekannt + conditional reason.
      // The parent item is rendered as a custom three-pill segmented
      // control; the synthesised wire payload at materialisation time
      // (Wave 2 builder) uses a single-select response set with the
      // three canonical options. Locked to single-select here so the
      // wizard dropdown stays consistent if the operator drops the
      // primitive directly.
      return ['single-select']
  }
}

/**
 * Whether the item carries an active show-when rule. Returns
 * {@code false} when the rule is absent or its source-item OID is
 * empty (the rule is incomplete and ignored at runtime).
 */
export function hasShowWhen(item: AuthoringItem): boolean {
  return item.showWhen != null && item.showWhen.sourceItemOid.trim() !== ''
}

/**
 * Canonical Ja / Nein / Unbekannt trio backing {@link AuthoringDataType}
 * {@code TRISTATE_REASON}.
 *
 * <p>The VALUES are the contract, not decoration — the conditional
 * children generated by the IOP + imaging-acquisition presets compare
 * against exactly these literals in their show-when rules (see
 * {@code presets/iopPreset.ts}). The display texts are the German
 * source-of-truth strings and stay operator-editable afterwards.
 */
export const TRISTATE_OPTION_VALUES = {
  JA: 'JA',
  NEIN: 'NEIN',
  UNBEKANNT: 'UNBEKANNT',
} as const

export function tristateOptions(): ResponseSetOption[] {
  return [
    { text: 'Ja', value: TRISTATE_OPTION_VALUES.JA },
    { text: 'Nein', value: TRISTATE_OPTION_VALUES.NEIN },
    { text: 'Unbekannt', value: TRISTATE_OPTION_VALUES.UNBEKANNT },
  ]
}

/**
 * Two blank starter rows so the operator can see where to type. Blank
 * rows are filtered out again at serialise time
 * ({@link buildResponseSetPayload}) and never reach the wire.
 */
function blankOptionRows(): ResponseSetOption[] {
  return [
    { text: '', value: '' },
    { text: '', value: '' },
  ]
}

/**
 * Bring {@code responseType} + {@code responseSet} into a shape the
 * backend can accept, given the item's {@code dataType}. Idempotent;
 * mutates in place.
 *
 * <p>This invariant used to live in a watcher inside the (now dead)
 * {@code ItemEditor.vue}, which is precisely why the palette-drop path
 * never got it: dropping a {@code TRISTATE_REASON} or {@code FILE}
 * primitive left {@code responseType: 'text'} — out of the data type's
 * allowed matrix — plus a null response set, and the properties rail
 * then *disabled* the response-type select (singleton allowed set) while
 * displaying a value the model didn't hold. The operator could not
 * correct it and the item silently saved as open text.
 *
 * <p>Living in the store means the drop path ({@link addItem}) and the
 * rail path ({@link setItemField}) cannot diverge again.
 */
export function reconcileItemResponseShape(item: AuthoringItem): void {
  // 1. Clamp the response type into the data type's allowed matrix.
  const allowed = allowedResponseTypesForDataType(item.dataType)
  if (allowed.length > 0 && !allowed.includes(item.responseType)) {
    item.responseType = allowed[0]!
  }
  // 2. BL pins a synthesised Yes/No at buildResponseSetPayload time —
  //    never let an inline set linger underneath it.
  if (dataTypeIsBoolean(item.dataType)) {
    item.responseSet = null
    return
  }
  // 3. Open-text / file branches carry no options.
  if (!responseTypeRequiresOptions(item.responseType)) {
    item.responseSet = null
    return
  }
  const rs = item.responseSet
  // 4. A catalog link is the operator's explicit choice — leave it be.
  if (rs != null && 'ref' in rs) return
  // 5. Seed an inline set so the rail's options editor always has rows
  //    to bind against. Label stays blank on purpose: the per-item
  //    label is derived at serialise time (see implicitChoiceLabel).
  if (rs == null) {
    item.responseSet = {
      type: item.responseType,
      label: '',
      options: item.dataType === 'TRISTATE_REASON' ? tristateOptions() : blankOptionRows(),
    }
    return
  }
  // 6. Inline set already present — keep the operator's rows, sync type.
  rs.type = item.responseType
}

function emptyValidation(): AuthoringValidation {
  return { regexp: '', errorMessage: '' }
}

let uidCounter = 0
function nextUid(prefix: string): string {
  uidCounter += 1
  return `${prefix}-${uidCounter}`
}

/**
 * Section/item UIDs (`sec-1`, `item-3`) come from {@link nextUid}, whose
 * counter also resets to 0 on page load. A restored draft already holds UIDs,
 * so without re-seeding, the next added item re-mints a colliding UID — two
 * items sharing a UID break selection and drag-reorder, which key by UID.
 * Advance the counter past the highest suffix any restored UID holds (the
 * counter is shared across prefixes, so scan both sections and items).
 */
export function reseedUidCounter(d: AuthoringDraft): void {
  let max = uidCounter
  const bump = (uid: string | undefined): void => {
    const m = /-(\d+)$/.exec(uid ?? '')
    if (m) max = Math.max(max, Number(m[1]))
  }
  for (const section of d.sections ?? []) {
    bump(section.uid)
    for (const item of section.items ?? []) bump(item.uid)
  }
  uidCounter = max
}

/**
 * Convert the fork-from-version wire payload into an {@link AuthoringDraft}.
 * Defensive against missing/null fields. {@code versionName} is blanked
 * so the operator types a fresh name (the backend's unique-name check
 * would otherwise reject the prior version's name).
 */
/** Recovered row bounds for a reconstructed table, keyed by group label. */
type ForkGroupBounds = Map<string, { minRows: number; maxRows: number }>

function forkContentsToDraft(wire: ForkContentsWire): AuthoringDraft {
  const bounds: ForkGroupBounds = new Map()
  for (const g of wire.groups ?? []) {
    const label = (g?.label ?? '').trim()
    if (!label) continue
    bounds.set(label, {
      minRows: Math.max(1, g?.repeatNumber ?? 1),
      maxRows: Math.max(1, g?.repeatMax ?? 20),
    })
  }
  const sections = (wire.sections ?? []).map((s, idx) => forkSection(s, idx + 1, bounds))
  if (sections.length === 0) {
    sections.push({
      uid: nextUid('sec'),
      label: 'S1',
      title: 'Section 1',
      instructions: '',
      ordinal: 1,
      items: [],
    })
  }
  return {
    versionName: '',
    versionDescription: wire.versionDescription ?? '',
    revisionNotes: wire.revisionNotes ?? '',
    sections,
  }
}

function forkSection(wire: ForkSectionWire, fallbackOrdinal: number, bounds: ForkGroupBounds): AuthoringSection {
  return {
    uid: nextUid('sec'),
    label: wire.label?.trim() || `S${fallbackOrdinal}`,
    title: wire.title?.trim() || `Section ${fallbackOrdinal}`,
    instructions: wire.instructions ?? '',
    ordinal: wire.ordinal ?? fallbackOrdinal,
    items: forkItems(wire.items ?? [], bounds),
  }
}

/**
 * Fork recovery — rebuild a section's items, folding every run of items that
 * share a repeating-group label back into a single {@link RepeatingTableSpec}
 * item (the inverse of {@code buildTableItemPayloads}). Ungrouped items map
 * 1:1 via {@link forkItem}. The table item is placed where its group first
 * appears; columns keep wire order. The fill-map is NOT recovered — it is not
 * persisted (see terminologyOid.ts) — so bindings come back autocomplete-only.
 */
function forkItems(wires: ForkItemWire[], bounds: ForkGroupBounds): AuthoringItem[] {
  const items: AuthoringItem[] = []
  const tableByLabel = new Map<string, AuthoringItem>()
  for (const w of wires) {
    const label = (w.groupLabel ?? '').trim()
    if (!label) {
      items.push(forkItem(w))
      continue
    }
    let table = tableByLabel.get(label)
    if (!table) {
      table = forkTableItem(label, bounds.get(label))
      tableByLabel.set(label, table)
      items.push(table)
    }
    table.table!.columns.push(forkTableColumn(w))
  }
  return items
}

/** A fresh repeating-table item whose oid/name is the recovered group label. */
function forkTableItem(groupLabel: string, bounds?: { minRows: number; maxRows: number }): AuthoringItem {
  return {
    uid: nextUid('item'),
    name: groupLabel,
    oid: groupLabel,
    descriptionLabel: groupLabel,
    leftItemText: '',
    rightItemText: '',
    units: '',
    dataType: 'ST',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: { regexp: '', errorMessage: '' },
    // Recover the persisted row bounds (wire groups[]) when present; else the
    // authoring defaults.
    table: { columns: [], minRows: bounds?.minRows ?? 1, maxRows: bounds?.maxRows ?? 20 },
  }
}

/** Reconstruct one repeating-table column from a persisted grouped item. */
function forkTableColumn(w: ForkItemWire): RepeatingTableColumn {
  const label = (w.descriptionLabel ?? '').trim() || (w.leftItemText ?? '').trim() || (w.name ?? '').trim()
  const col = newRepeatingTableColumn(label)
  col.type = forkColumnType(w)
  // A text column's terminology system was encoded in the OID marker; decode
  // it. The fill map isn't persisted, so it comes back empty.
  const system = terminologySystemFromOid(w.oid)
  if (col.type === 'text' && system) col.autocomplete = { system, fills: [] }
  return col
}

/**
 * Infer a recovered column's type from its persisted shape (inverse of
 * {@code buildTableItemPayloads}): a single-select whose options are exactly
 * OD/OS/OU is a laterality column; REAL/INT → number; DATE/PDATE → date;
 * everything else (incl. terminology-bound text) → text.
 */
function forkColumnType(w: ForkItemWire): RepeatingTableColumnType {
  if (w.responseSet?.type === 'single-select' && isLateralityOptionSet(w.responseSet.options)) {
    return 'laterality'
  }
  const dt = (w.dataType ?? '').toUpperCase()
  if (dt === 'REAL' || dt === 'INT' || dt === 'INTEGER') return 'number'
  if (dt === 'DATE' || dt === 'PDATE') return 'date'
  return 'text'
}

/** True when a response set's option values are a non-empty subset of OD/OS/OU. */
function isLateralityOptionSet(options: Array<{ value?: string }> | undefined): boolean {
  const allowed = new Set(LATERALITY_OPTIONS.map((o) => o.value))
  const values = (options ?? []).map((o) => (o.value ?? '').trim().toUpperCase()).filter(Boolean)
  return values.length > 0 && values.every((v) => allowed.has(v))
}

function forkItem(wire: ForkItemWire): AuthoringItem {
  const responseType: AuthoringResponseType =
    wire.responseSet?.type && isAuthoringResponseType(wire.responseSet.type)
      ? wire.responseSet.type
      : 'text'
  const item: AuthoringItem = {
    uid: nextUid('item'),
    name: wire.name ?? '',
    oid: wire.oid ?? '',
    descriptionLabel: wire.descriptionLabel ?? '',
    leftItemText: wire.leftItemText ?? '',
    rightItemText: wire.rightItemText ?? '',
    units: wire.units ?? '',
    dataType: normaliseDataType(wire.dataType),
    responseType,
    defaultValue: wire.defaultValue ?? '',
    required: wire.required ?? false,
    responseSet: forkResponseSet(wire.responseSet ?? null),
    validation: {
      regexp: wire.validation?.regexp ?? '',
      errorMessage: wire.validation?.errorMessage ?? '',
    },
  }
  // Fork recovery — reattach a conditional-display rule when the backend
  // surfaced one (source OID present). The legacy store is equality-only.
  const parentOid = (wire.parentItemOid ?? '').trim()
  if (parentOid !== '') {
    item.showWhen = {
      sourceItemOid: parentOid,
      comparator: '==',
      literal: wire.showItem ?? '',
    }
  }
  return item
}

function forkResponseSet(wire: ForkResponseSetWire | null): AuthoringResponseSet {
  if (!wire) return null
  if (wire.ref?.label) {
    return { ref: { label: wire.ref.label } }
  }
  const type = wire.type && isAuthoringResponseType(wire.type) ? wire.type : 'text'
  const opts = (wire.options ?? [])
    .map((o) => ({ text: o.text ?? '', value: o.value ?? '' }))
    .filter((o) => o.text !== '' || o.value !== '')
  // Only emit an InlineResponseSet when the type implies options; the
  // open-text branches stay null so the canvas's empty-picker state
  // shows up as expected.
  if (opts.length === 0 && !OPTION_RESPONSE_TYPES.has(type)) return null
  return { type, label: wire.label ?? '', options: opts }
}

function normaliseDataType(raw: string | undefined): AuthoringDataType {
  switch ((raw ?? '').toUpperCase()) {
    case 'INT':
    case 'INTEGER':
      return 'INT'
    case 'REAL':
      return 'REAL'
    case 'DATE':
      return 'DATE'
    case 'PDATE':
      return 'PDATE'
    case 'FILE':
      return 'FILE'
    case 'BL':
      return 'BL'
    case 'TRISTATE_REASON':
      return 'TRISTATE_REASON'
    case 'ST':
    default:
      return 'ST'
  }
}

function isAuthoringResponseType(s: string): s is AuthoringResponseType {
  return s === 'text' || s === 'textarea' || s === 'radio'
    || s === 'single-select' || s === 'multi-select'
    || s === 'checkbox' || s === 'file'
}

function emptyDraft(): AuthoringDraft {
  return {
    versionName: '',
    versionDescription: '',
    revisionNotes: '',
    sections: [
      {
        uid: nextUid('sec'),
        label: 'S1',
        title: 'Section 1',
        instructions: '',
        ordinal: 1,
        items: [],
      },
    ],
  }
}

function emptyItem(): AuthoringItem {
  return {
    uid: nextUid('item'),
    name: '',
    oid: '',
    descriptionLabel: '',
    leftItemText: '',
    rightItemText: '',
    units: '',
    dataType: 'ST',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: emptyValidation(),
  }
}

export const useCrfAuthoringStore = defineStore('crfAuthoring', () => {
  const draft = ref<AuthoringDraft>(emptyDraft())
  const isSubmitting = ref(false)
  const isPreviewing = ref(false)
  const error = ref<string | null>(null)

  /** Cached catalog from {@code GET /api/v1/response-sets}. */
  const responseSetCatalog = ref<ResponseSetCatalogEntry[]>([])
  const isLoadingCatalog = ref(false)

  /**
   * Canvas selection — the single highlighted item, keyed on
   * {@link AuthoringItem.uid} so it survives reorder. {@code null}
   * clears it (properties rail shows its empty state).
   */
  const selectedItemUid = ref<string | null>(null)

  function selectItem(uid: string | null): void {
    selectedItemUid.value = uid
  }

  function reset(): void {
    draft.value = emptyDraft()
    error.value = null
    isSubmitting.value = false
    isPreviewing.value = false
    selectedItemUid.value = null
  }

  /**
   * Fork-from-version — pre-seed the canvas with a prior version's
   * contents so the operator tweaks only what changed.
   *
   * <p>{@code GET /pages/api/v1/crfs/{oid}/versions/{vid}/contents}
   * returns the wire-shaped {@code CrfVersionAuthoringRequest}; this
   * shape-converts it into an {@link AuthoringDraft} and replaces
   * {@link draft}. Returns {@code true} on success; on failure the draft
   * stays untouched and {@link error} is populated for the canvas banner.
   */
  async function loadFromVersion(
    crfOid: string,
    versionOid: string,
  ): Promise<boolean> {
    try {
      const body = await apiGet<ForkContentsWire>(
        `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions/${encodeURIComponent(versionOid)}/contents`,
      )
      draft.value = forkContentsToDraft(body)
      selectedItemUid.value = null
      return true
    } catch (e) {
      if (e instanceof ApiError) {
        const errBody = e.body as { message?: string } | null
        error.value = errBody?.message
          ?? `Vorversion konnte nicht geladen werden (HTTP ${e.status}).`
      } else if (e instanceof ApiNetworkError) {
        error.value = 'Backend nicht erreichbar — Vorversion konnte nicht geladen werden.'
      } else {
        error.value = e instanceof Error ? e.message : 'Vorversion konnte nicht geladen werden.'
      }
      return false
    }
  }

  /**
   * Replace the whole draft with a restored copy (e.g. from the
   * local autosave in {@code useCrfDraftPersistence}). Mirrors the
   * wholesale replacement {@link loadFromVersion} does, clearing the
   * canvas selection so the properties rail doesn't dangle on a uid
   * that may no longer exist.
   */
  function hydrateDraft(next: AuthoringDraft): void {
    draft.value = next
    selectedItemUid.value = null
    error.value = null
    reseedTableColumnKeySeq(next)
    reseedUidCounter(next)
  }

  function setMetadata(patch: Partial<Pick<AuthoringDraft, 'versionName' | 'versionDescription' | 'revisionNotes'>>): void {
    if (patch.versionName !== undefined) draft.value.versionName = patch.versionName
    if (patch.versionDescription !== undefined) draft.value.versionDescription = patch.versionDescription
    if (patch.revisionNotes !== undefined) draft.value.revisionNotes = patch.revisionNotes
  }

  function setVersionName(versionName: string): void {
    draft.value.versionName = versionName
  }

  function setVersionDescription(versionDescription: string): void {
    draft.value.versionDescription = versionDescription
  }

  function addSection(seed?: Partial<AuthoringSection>): string {
    const next = draft.value.sections.length + 1
    const uid = seed?.uid ?? nextUid('sec')
    draft.value.sections.push({
      uid,
      label: seed?.label ?? `S${next}`,
      title: seed?.title ?? `Section ${next}`,
      instructions: seed?.instructions ?? '',
      ordinal: seed?.ordinal ?? next,
      items: seed?.items ?? [],
      bilateral: seed?.bilateral ?? false,
    })
    return uid
  }

  /**
   * Phase E.6 ophth-bilateral — flip a section's bilateral flag. Used by
   * the per-section toggle in the wizard header so operators can put
   * any section into the OD/OS grid layout (not just OPHTH_EXAM ones
   * generated by the preset hotkey).
   */
  function setSectionBilateral(sectionIndex: number, value: boolean): void {
    const section = draft.value.sections[sectionIndex]
    if (!section) return
    section.bilateral = value
  }

  function removeSection(sectionIndex: number): void {
    if (draft.value.sections.length <= 1) return
    draft.value.sections.splice(sectionIndex, 1)
    // Re-number ordinals so the persisted payload is contiguous.
    draft.value.sections.forEach((s, i) => {
      s.ordinal = i + 1
    })
  }

  function reorderSections(reordered: AuthoringSection[]): void {
    draft.value.sections = reordered.map((s, i) => ({ ...s, ordinal: i + 1 }))
  }

  function addItem(sectionIndex: number, seed?: Partial<AuthoringItem>): void {
    const section = draft.value.sections[sectionIndex]
    if (!section) return
    const item: AuthoringItem = { ...emptyItem(), ...seed }
    // Palette drops seed a dataType (and sometimes a responseType) but
    // never a responseSet — reconcile fills in the rest so a dropped
    // block is immediately valid and editable in the properties rail.
    reconcileItemResponseShape(item)
    section.items.push(item)
  }

  /**
   * Phase E.6 ophth-bilateral — append a paired (OD, OS) item to a
   * bilateral section. The wizard's OPHTH_EXAM grid wires its
   * "+ Item hinzufügen" button to this so adding a row populates BOTH
   * eyes at once rather than the LEFT-only behaviour of
   * {@link addItem}.
   *
   * <p>The pair gets a unique suffix derived from the current count
   * of bilateral pairs in the section ({@code NEW_ITEM_<n>}) so the
   * generated OIDs (e.g. {@code OD_NEW_ITEM_1} / {@code OS_NEW_ITEM_1})
   * never collide with seeded preset items. Operators are expected to
   * rename the OID + label per item via the per-row ItemEditor;
   * keeping the OD/OS suffix in sync is then a manual rename on each
   * side (the {@code bilateralPair} cross-link metadata is left empty
   * to avoid stale references after operator edits).
   */
  function addBilateralPair(sectionIndex: number): void {
    const section = draft.value.sections[sectionIndex]
    if (!section) return
    let nextN = 1
    const used = new Set<string>()
    for (const it of section.items) {
      const m = /^O[DS]_NEW_ITEM_(\d+)$/.exec(it.oid)
      if (m) used.add(m[1]!)
    }
    while (used.has(String(nextN))) nextN++
    const suffix = `NEW_ITEM_${nextN}`
    const odOid = `OD_${suffix}`
    const osOid = `OS_${suffix}`
    section.items.push({
      ...emptyItem(),
      name: odOid,
      oid: odOid,
      leftItemText: 'OD',
      laterality: 'OD',
      bilateralPair: osOid,
    })
    section.items.push({
      ...emptyItem(),
      name: osOid,
      oid: osOid,
      leftItemText: 'OS',
      laterality: 'OS',
      bilateralPair: odOid,
    })
  }

  /**
   * Phase E.6 ophth-field-catalog (2026-06-11): drop a catalog entry
   * into a section as one or two pre-filled items (one for non-
   * bilateral entries, OD + OS for bilateral). Each item carries
   * {@code catalogCode} so the backend materialises blank fields
   * from the catalog row.
   *
   * <p>The wizard's picker is the only caller — it knows the catalog
   * entry's {@code bilateral} flag + {@code oidPrefix} + {@code code}
   * so it can pass them in here.
   */
  function addCatalogItem(
    sectionIndex: number,
    opts: {
      code: string
      labelDe: string
      bilateral: boolean
      oidPrefix: string
      dataType: AuthoringDataType
    },
  ): void {
    const section = draft.value.sections[sectionIndex]
    if (!section) return
    const namespace = opts.oidPrefix && opts.oidPrefix.trim().length > 0 ? opts.oidPrefix.trim() : 'OPHTH'
    if (opts.bilateral) {
      const odOid = `I_${namespace}_OD_${opts.code}`
      const osOid = `I_${namespace}_OS_${opts.code}`
      section.items.push({
        ...emptyItem(),
        name: `OD_${opts.code}`,
        oid: odOid,
        descriptionLabel: opts.labelDe,
        leftItemText: opts.labelDe,
        dataType: opts.dataType,
        laterality: 'OD',
        bilateralPair: osOid,
        catalogCode: opts.code,
      })
      section.items.push({
        ...emptyItem(),
        name: `OS_${opts.code}`,
        oid: osOid,
        descriptionLabel: opts.labelDe,
        leftItemText: opts.labelDe,
        dataType: opts.dataType,
        laterality: 'OS',
        bilateralPair: odOid,
        catalogCode: opts.code,
      })
    } else {
      const ouOid = `I_${namespace}_OU_${opts.code}`
      section.items.push({
        ...emptyItem(),
        name: `OU_${opts.code}`,
        oid: ouOid,
        descriptionLabel: opts.labelDe,
        leftItemText: opts.labelDe,
        dataType: opts.dataType,
        laterality: 'OU',
        catalogCode: opts.code,
      })
    }
  }

  /**
   * Append a new section to the draft and populate it with paired
   * OD / OS items generated from the Ophthalmology bilateral preset.
   * Mirrors {@code addItem} for callers that want to append several
   * pre-built items at once (the preset picker).
   *
   * <p>The section is labelled {@code OPHTH_EXAM} by convention — the
   * authoring backend treats {@code section.label} as the item group
   * name, and "one item group named OPHTH_EXAM (repeating: false)" is
   * the preset's contract per the Phase E.6 brief.
   */
  function addOphthPresetSection(opts: {
    /** The pre-generated items (already paired by {@code generateOphthSectionItems}). */
    items: Array<Omit<AuthoringItem, 'uid'>>
    /** Display title for the section. Defaults to {@code "Ophthalmology examination"}. */
    title?: string
    instructions?: string
    /**
     * When set, REPLACE the section at this index (keeping the
     * existing {@code uid} + {@code ordinal}) instead of appending a
     * new section. Used by the magic-label hotkey path on the canvas
     * surface: operators type {@code OPHTHA_EXAM} as a section label
     * and press Shift+Enter, which opens the picker; on confirm the
     * picker overwrites the trigger section in place so the operator
     * doesn't end up with a leftover empty trigger row. (Originated
     * in the now-removed legacy CrfAuthoringWizard keydown handler.)
     */
    replaceAtIndex?: number
  }): void {
    const sectionLabel = 'OPHTH_EXAM'
    const seeded: AuthoringItem[] = opts.items.map((item) => ({
      ...item,
      uid: nextUid('item'),
    }))
    if (opts.replaceAtIndex != null) {
      const existing = draft.value.sections[opts.replaceAtIndex]
      if (existing) {
        existing.label = sectionLabel
        existing.title = opts.title ?? 'Ophthalmology examination'
        existing.instructions = opts.instructions ?? ''
        existing.items = seeded
        existing.bilateral = true
        return
      }
      // Fall through to append if the index was stale.
    }
    const next = draft.value.sections.length + 1
    draft.value.sections.push({
      uid: nextUid('sec'),
      label: sectionLabel,
      title: opts.title ?? 'Ophthalmology examination',
      instructions: opts.instructions ?? '',
      ordinal: next,
      items: seeded,
      bilateral: true,
    })
  }

  function setItemField<K extends keyof AuthoringItem>(
    sectionIndex: number,
    itemIndex: number,
    field: K,
    value: AuthoringItem[K],
  ): void {
    const section = draft.value.sections[sectionIndex]
    if (!section) return
    const item = section.items[itemIndex]
    if (!item) return
    item[field] = value
    // Only the two shape-bearing fields trigger reconciliation. Editing
    // a name or label on a preset-generated item must never disturb its
    // authored options.
    if (field === 'dataType' || field === 'responseType') {
      reconcileItemResponseShape(item)
    }
  }

  /**
   * Replace an item's response set — the write-through used by the
   * properties rail's options editor. Keeps {@code responseSet.type}
   * slaved to {@code item.responseType}; the editor mutates options
   * only, never the response type itself.
   */
  function setItemResponseSet(
    sectionIndex: number,
    itemIndex: number,
    rs: AuthoringResponseSet,
  ): void {
    const item = draft.value.sections[sectionIndex]?.items[itemIndex]
    if (!item) return
    item.responseSet = rs
    if (rs != null && !('ref' in rs)) rs.type = item.responseType
  }

  function removeItem(sectionIndex: number, itemIndex: number): void {
    const section = draft.value.sections[sectionIndex]
    if (!section) return
    section.items.splice(itemIndex, 1)
  }

  function reorderItems(sectionIndex: number, reordered: AuthoringItem[]): void {
    const section = draft.value.sections[sectionIndex]
    if (!section) return
    section.items = reordered
  }

  /**
   * Apply a canvas preset's items into the section identified by
   * {@code targetSectionUid}. When the preset declares
   * {@code bilateralSection}, the section's bilateral flag is set.
   * Returns the count of items appended, or {@code 0} when the preset
   * id is unknown or the target section is missing. Registry + translator
   * are passed in to avoid a store → presetCatalog → store import cycle.
   */
  function applyPreset(
    presetId: string,
    targetSectionUid: string,
    opts: {
      registry: ReadonlyArray<{
        id: string
        bilateralSection?: boolean
        generate: (translate: (k: string) => string) => Array<Omit<AuthoringItem, 'uid'>>
      }>
      translate: (key: string) => string
    },
  ): number {
    const preset = opts.registry.find((p) => p.id === presetId)
    if (!preset) return 0
    const section = draft.value.sections.find((s) => s.uid === targetSectionUid)
    if (!section) return 0
    const items = preset.generate(opts.translate)
    const seeded: AuthoringItem[] = items.map((item) => ({
      ...item,
      uid: nextUid('item'),
    }))
    section.items.push(...seeded)
    if (preset.bilateralSection) {
      section.bilateral = true
    }
    return seeded.length
  }

  /**
   * Apply a preset as a whole section (not items mixed into an existing
   * one — that produced confused bilateral grids).
   *
   * <ul>
   *   <li>Empty target section → transform it in place (re-title, set
   *       bilateral flag from the preset, fill with the preset's items).</li>
   *   <li>Otherwise → insert a NEW section immediately after the target.</li>
   * </ul>
   *
   * <p>Returns the new section's uid, or {@code null} when the preset id
   * is unknown.
   */
  function applyPresetAsSection(
    presetId: string,
    targetSectionUid: string,
    opts: {
      registry: ReadonlyArray<{
        id: string
        labelKey: string
        bilateralSection?: boolean
        sectionLabel?: string
        generate: (translate: (k: string) => string) => Array<Omit<AuthoringItem, 'uid'>>
      }>
      translate: (key: string) => string
    },
  ): string | null {
    const preset = opts.registry.find((p) => p.id === presetId)
    if (!preset) return null

    const items = preset.generate(opts.translate)
    const bilateral = preset.bilateralSection ?? false
    const presetTitle = opts.translate(preset.labelKey)
    // 2026-06-21 round 3 — preset overrides the section tag in addition to
    // the title. Uniqueness across siblings is enforced by appending a
    // numeric suffix when the bare tag already exists (e.g. dropping IOP
    // twice produces IOP + IOP_2).
    const presetTag = preset.sectionLabel?.trim() ?? ''

    const target = draft.value.sections.find((s) => s.uid === targetSectionUid)
    // Transform an empty target section in place rather than leaving a
    // dangling empty Section 1 above the new content — common case
    // when the operator drops the very first preset onto a fresh draft.
    if (target && target.items.length === 0) {
      const resolvedLabel = presetTag ? uniqueSectionLabel(presetTag, target.uid) : null
      // 2026-06-21 round 6 — when the resolved section tag collides
      // with a sibling and gets the `_N` suffix, the items inside
      // would still collide on OID/name (e.g. dropping IOP twice
      // gave two OD_IOP_GEMESSEN items). Apply the same suffix to
      // the items so the CRF JSON validator's uniqueness check
      // passes without operator-side renaming.
      const suffix = resolvedLabel ? extractCollisionSuffix(presetTag, resolvedLabel) : ''
      target.title = presetTitle
      target.bilateral = bilateral
      target.items = seedPresetItems(items, suffix)
      if (resolvedLabel) target.label = resolvedLabel
      return target.uid
    }

    const nextOrdinal = draft.value.sections.length + 1
    const fallbackLabel = `S${nextOrdinal}`
    const resolvedLabel = presetTag ? uniqueSectionLabel(presetTag, null) : fallbackLabel
    const suffix = presetTag ? extractCollisionSuffix(presetTag, resolvedLabel) : ''
    const newSection: AuthoringSection = {
      uid: nextUid('sec'),
      label: resolvedLabel,
      title: presetTitle,
      instructions: '',
      ordinal: nextOrdinal,
      items: seedPresetItems(items, suffix),
      bilateral,
    }
    // Insert immediately after the drop target if found; otherwise
    // append.
    const idx = target
      ? draft.value.sections.findIndex((s) => s.uid === target.uid)
      : -1
    if (idx >= 0) {
      draft.value.sections.splice(idx + 1, 0, newSection)
    } else {
      draft.value.sections.push(newSection)
    }
    // Re-number ordinals so the persisted payload stays contiguous.
    draft.value.sections.forEach((s, i) => {
      s.ordinal = i + 1
    })
    return newSection.uid
  }

  /**
   * 2026-06-21 round 6 — derive the {@code _N} collision suffix from a
   * resolved section label vs its bare preset tag. Returns the empty
   * string when no collision happened (resolved equals base).
   *
   * <p>Examples:
   * <ul>
   *   <li>{@code base="IOP"}, {@code resolved="IOP"} → {@code ""}</li>
   *   <li>{@code base="IOP"}, {@code resolved="IOP_2"} → {@code "_2"}</li>
   *   <li>{@code base="IOP"}, {@code resolved="IOP_3"} → {@code "_3"}</li>
   * </ul>
   *
   * <p>The caller appends this suffix to the per-item OID + name + the
   * children's show-when source OID so a second drop of the same preset
   * produces items that don't collide with the first drop.
   */
  function extractCollisionSuffix(base: string, resolved: string): string {
    const trimmedBase = base.trim().toUpperCase()
    const trimmedResolved = resolved.trim().toUpperCase()
    if (trimmedBase === trimmedResolved) return ''
    if (trimmedResolved.startsWith(trimmedBase + '_')) {
      return trimmedResolved.slice(trimmedBase.length)
    }
    return ''
  }

  /**
   * 2026-06-21 round 6 — materialise preset items with an optional
   * collision suffix. When {@code suffix} is empty the items are seeded
   * verbatim (just with fresh uids). When set (e.g. {@code "_2"}) every
   * item's {@code name} + {@code oid} get the suffix appended AND every
   * child's {@code showWhen.sourceItemOid} is rewritten through the
   * same rename map so the conditional show-when references still point
   * at the (now renamed) parent.
   */
  function seedPresetItems(
    items: Array<Omit<AuthoringItem, 'uid'>>,
    suffix: string,
  ): AuthoringItem[] {
    const seeded: AuthoringItem[] = items.map((item) => ({
      ...item,
      uid: nextUid('item'),
    }))
    if (!suffix) return seeded
    // Build the OID rename map from the (now seeded but not yet
    // suffixed) items so the showWhen rewrites can hit any sibling
    // reference, not just the parent-with-children pair.
    const renameMap = new Map<string, string>()
    for (const it of seeded) {
      if (it.oid) renameMap.set(it.oid, it.oid + suffix)
    }
    for (const it of seeded) {
      if (it.name) it.name = it.name + suffix
      if (it.oid) it.oid = it.oid + suffix
      if (it.showWhen?.sourceItemOid) {
        const renamed = renameMap.get(it.showWhen.sourceItemOid)
        if (renamed) it.showWhen = { ...it.showWhen, sourceItemOid: renamed }
      }
    }
    return seeded
  }

  /**
   * 2026-06-21 round 3 — return {@code base} if no other section is
   * using that exact label, otherwise append {@code _2}, {@code _3}, …
   * until a free slot is found. {@code excludeUid} skips the named
   * section's own label (used when transforming an empty section
   * in-place, where the section being renamed must not collide with
   * itself).
   */
  function uniqueSectionLabel(base: string, excludeUid: string | null): string {
    const taken = new Set(
      draft.value.sections
        .filter((s) => s.uid !== excludeUid)
        .map((s) => s.label.trim().toUpperCase()),
    )
    const seed = base.trim().toUpperCase()
    if (!taken.has(seed)) return seed
    for (let i = 2; i < 1000; i++) {
      const candidate = `${seed}_${i}`
      if (!taken.has(candidate)) return candidate
    }
    return seed
  }

  /**
   * Flip a section's bilateral flag by uid (the SectionCanvas toggle
   * does NOT have the array index to hand). Mirrors {@link
   * setSectionBilateral} but keyed on the uid rather than the
   * positional index, which is the right primitive for the canvas
   * because the user can reorder sections.
   */
  function setSectionBilateralByUid(sectionUid: string, value: boolean): void {
    const section = draft.value.sections.find((s) => s.uid === sectionUid)
    if (!section) return
    section.bilateral = value
  }

  /**
   * Auto-suggest an item OID from the operator-typed item name.
   * Simple convention: uppercase + collapse non-word chars to a
   * single underscore. Operators can override the suggestion at the
   * Item editor — `setItemField(s, i, 'oid', …)`.
   */
  function suggestOid(name: string): string {
    if (!name) return ''
    return name
      .trim()
      .toUpperCase()
      .replace(/[^A-Z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '')
  }

  /**
   * Build the wire payload from the draft. Trims whitespace and
   * drops empty optional sub-objects so the wire shape matches the
   * backend's null-tolerant record fields.
   */
  function buildPayload(): Record<string, unknown> {
    return {
      versionName: draft.value.versionName.trim(),
      versionDescription: draft.value.versionDescription.trim(),
      revisionNotes: draft.value.revisionNotes.trim(),
      sections: draft.value.sections.map((s, idx) => ({
        label: s.label.trim(),
        title: s.title.trim(),
        instructions: s.instructions.trim(),
        ordinal: s.ordinal || idx + 1,
        // #26 Slice 3 persistence — a repeating-table item expands into one
        // grouped item per column (shared groupLabel → the backend workbook
        // adapter materialises an OpenClinica repeating item-group, which the
        // runtime renders via RepeatingGroupSection). A plain item serialises
        // as itself.
        items: s.items.flatMap((it) => (it.table ? buildTableItemPayloads(it) : [buildItemPayload(it)])),
      })),
      // #26 — per-group row bounds so the adapter persists the operator's
      // min/max instead of its 1/40 default (and fork recovers them). One
      // entry per repeating-table item, keyed by the same label its columns
      // carry (tableBaseOid == groupLabel).
      groups: draft.value.sections
        .flatMap((s) => s.items)
        .filter((it) => it.table)
        .map((it) => ({
          label: tableBaseOid(it),
          repeatNumber: Math.max(1, it.table!.minRows ?? 1),
          repeatMax: Math.max(1, it.table!.maxRows ?? 20),
        })),
    }
  }

  /** The repeating-group label a table item's columns share (its base OID). */
  function tableBaseOid(it: AuthoringItem): string {
    return (it.oid.trim() || it.name.trim() || 'TABLE').replace(/[^A-Za-z0-9_]/g, '_')
  }

  /**
   * #26 Slice 3 persistence — expand a repeating-table item into its column
   * items. Each column becomes an item in the SAME flat repeating group
   * (groupLabel = the table's OID); the column type maps to a scalar data
   * type. Terminology autocomplete bindings are NOT persisted here yet (no
   * backend column) — they remain wizard/preview-only; a saved table renders
   * as a plain repeating group at entry until that follow-up lands.
   */
  function buildTableItemPayloads(it: AuthoringItem): Record<string, unknown>[] {
    const table = it.table!
    const baseOid = tableBaseOid(it)
    const groupLabel = baseOid
    return table.columns.map((col, i) => {
      // A terminology-bound text column encodes its system in the OID so the
      // binding survives to live entry (see terminologyOid.ts). The fill map
      // is not persistable in an OID and stays preview-only.
      const rawColOid = `${baseOid}_${(col.key || `COL${i + 1}`).replace(/[^A-Za-z0-9_]/g, '_').toUpperCase()}`
      const colOid = col.type === 'text' && col.autocomplete
        ? markTerminologyOid(rawColOid, col.autocomplete.system)
        : rawColOid
      const dataType: AuthoringDataType =
        col.type === 'number' ? 'REAL' : col.type === 'date' ? 'DATE' : 'ST'
      const label = col.label.trim() || col.key
      // A laterality column persists as a single-select over OD/OS/OU so it
      // round-trips to live entry as a dropdown (RepeatingGroupSection renders
      // select-one columns natively). Other columns keep the open-text branch.
      const responseSet: Record<string, unknown> = col.type === 'laterality'
        ? {
            type: 'single-select',
            label: `${colOid.toLowerCase()}_laterality`,
            options: LATERALITY_OPTIONS.map((o) => ({ text: o.label, value: o.value })),
          }
        : { type: 'text', label: `${colOid.toLowerCase()}_response` }
      return {
        name: colOid,
        oid: colOid,
        descriptionLabel: label,
        leftItemText: label,
        rightItemText: '',
        units: '',
        dataType,
        defaultValue: '',
        required: false,
        groupLabel,
        responseSet,
      }
    })
  }

  function buildItemPayload(it: AuthoringItem): Record<string, unknown> {
    const out: Record<string, unknown> = {
      name: it.name.trim(),
      oid: it.oid.trim(),
      descriptionLabel: it.descriptionLabel.trim(),
      leftItemText: it.leftItemText.trim(),
      rightItemText: it.rightItemText.trim(),
      units: it.units.trim(),
      dataType: it.dataType,
      defaultValue: it.defaultValue.trim(),
      required: it.required,
    }
    const rs = buildResponseSetPayload(it)
    if (rs) out.responseSet = rs
    const v = buildValidationPayload(it.validation)
    if (v) out.validation = v
    const sw = buildShowWhenPayload(it)
    if (sw) {
      out.showWhen = sw
      // The workbook adapter persists conditional display from the legacy
      // showItem/parentItemOid fields (→ scd_item_metadata), NOT from showWhen,
      // so an equality rule is mirrored onto them here to actually save and
      // round-trip through fork. scd is equality-only; non-"==" comparators
      // can't be represented, so they stay display-only (showWhen) and are not
      // persisted. showItem is the trigger value (the adapter reads it as the
      // parentValue half of its "parentValue|message" expression).
      if (it.showWhen!.comparator === '==') {
        out.parentItemOid = it.showWhen!.sourceItemOid.trim()
        out.showItem = it.showWhen!.literal
      }
    }
    // Phase E.6 ophth-field-catalog (2026-06-11): the backend adapter
    // back-fills blank fields from the matching catalog row when
    // catalogCode is present. Pass-through verbatim; the wizard's
    // picker is responsible for setting this on items it materialises.
    if (it.catalogCode && it.catalogCode.trim() !== '') {
      out.catalogCode = it.catalogCode.trim()
    }
    return out
  }

  /**
   * Serialise the per-item show-when rule. Returns {@code null} when
   * the rule is absent or incomplete (empty source OID), in which
   * case the wire payload simply omits the {@code showWhen} field
   * (the backend treats absence as "always show").
   */
  function buildShowWhenPayload(it: AuthoringItem): Record<string, unknown> | null {
    if (!hasShowWhen(it)) return null
    const rule = it.showWhen!
    return {
      sourceItemOid: rule.sourceItemOid.trim(),
      comparator: rule.comparator,
      // Preserve the operator-typed literal verbatim (no trim on
      // leading/trailing space) — Agent 3's runtime parser handles
      // whitespace normalisation per source-item data type.
      literal: rule.literal,
    }
  }

  function buildResponseSetPayload(it: AuthoringItem): Record<string, unknown> | null {
    // BL (boolean) — synthesise the canonical Yes/No option list and a
    // {@code single-select} response type. The wizard hides the picker
    // for BL (the dataType locks the response shape), so we cannot rely
    // on operator-authored options here; mirror what the legacy XLS
    // uploader emits when {@code DATA_TYPE=BL}.
    if (it.dataType === 'BL') {
      return {
        type: 'single-select',
        label: implicitBooleanLabel(it),
        options: [
          { text: 'Yes', value: '1' },
          { text: 'No', value: '0' },
        ],
      }
    }
    // Open-text responses (text / textarea / file) don't need an explicit
    // response-set on the wire — the synthesised workbook treats the
    // dataType + the absence of options as the open-text branch.
    const rs = it.responseSet
    if (rs == null) {
      if (responseTypeRequiresOptions(it.responseType)) {
        // Unreachable through the canvas (reconcileItemResponseShape
        // seeds an inline set), but a forked or legacy draft can still
        // land here. Emit an explicit empty options[] so the backend
        // answers with the actionable — and now localised — "requires at
        // least one option" error instead of failing deep in the
        // workbook parser with a raw message.
        return { type: it.responseType, label: implicitChoiceLabel(it), options: [] }
      }
      // The picker is omitted entirely; surface the implicit responseType
      // so the backend stamps the correct ResponseType on the synthesised
      // workbook (single source of truth = item.responseType).
      return { type: it.responseType, label: implicitOpenLabel(it) }
    }
    if ('ref' in rs) {
      return { ref: { label: rs.ref.label } }
    }
    return {
      type: rs.type,
      label: rs.label.trim() || implicitChoiceLabel(it),
      options: rs.options
        .map((opt) => ({ text: opt.text.trim(), value: opt.value.trim() }))
        .filter((opt) => opt.text !== '' || opt.value !== ''),
    }
  }

  /**
   * Per-item response-set label for choice types.
   *
   * <p>Load-bearing, not cosmetic. The synthesised workbook's parser
   * enforces "same RESPONSE_LABEL ⇒ same RESPONSE_OPTIONS_TEXT" across
   * the whole CRF (page_messages {@code resp_label_with_different_resp_options}),
   * and {@code CrfJsonToWorkbookAdapter.resolveResponseSetCells} stamps a
   * GENERIC {@code single_select_options} on every blank label. Two
   * differently-optioned dropdowns in one CRF therefore collided and the
   * entire version was rejected — so a CRF could hold at most one
   * dropdown. Deriving from the (validator-unique) item name avoids it.
   */
  function implicitChoiceLabel(it: AuthoringItem): string {
    const base = (it.name.trim() || it.oid.trim())
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '')
    return base ? `${base}_options` : 'choice_options'
  }

  function implicitBooleanLabel(it: AuthoringItem): string {
    // Stable per-item label so the parser sees a unique response_set.label
    // (the label uniqueness gate is per-CRF). We derive from the item
    // name when present, falling back to a generic token so the workbook
    // is well-formed even pre-name-entry.
    if (it.name.trim() !== '') return it.name.trim().toLowerCase() + '_yes_no'
    return 'yes_no'
  }

  function implicitOpenLabel(it: AuthoringItem): string {
    // Synthetic label so the parser sees a non-empty response_set.label
    // (it's required at the workbook column level). The label is
    // operator-visible only via the picker once Milestone C lands.
    if (it.name.trim() !== '') return it.name.trim().toLowerCase() + '_response'
    return 'open_response'
  }

  function buildValidationPayload(v: AuthoringValidation): Record<string, string> | null {
    const regexp = v.regexp.trim()
    const errorMessage = v.errorMessage.trim()
    if (regexp === '' && errorMessage === '') return null
    return { regexp, errorMessage }
  }

  async function submit(crfOid: string): Promise<AuthoringSubmitResult> {
    isSubmitting.value = true
    error.value = null
    try {
      const payload = buildPayload()
      const version = await apiPost<CrfVersion>(
        `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions`,
        payload,
      )
      return { ok: true, version }
    } catch (e) {
      return mapError(e) as AuthoringSubmitResult
    } finally {
      isSubmitting.value = false
    }
  }

  /**
   * Phase E.6 Milestone B — dry-run preview against the backend
   * {@code :preview} endpoint. Runs the same {@code CrfJsonValidator}
   * the persist path runs but never touches the workbook adapter or the
   * parser. Surfaces structured errors at the Review step before the
   * operator commits.
   */
  async function preview(crfOid: string): Promise<AuthoringPreviewResult> {
    isPreviewing.value = true
    error.value = null
    try {
      const payload = buildPayload()
      const preview = await apiPost<AuthoringPreviewSuccess>(
        `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions:preview`,
        payload,
      )
      return { ok: true, preview }
    } catch (e) {
      return mapError(e) as AuthoringPreviewResult
    } finally {
      isPreviewing.value = false
    }
  }

  /**
   * Load the cross-CRF response-set catalog from the backend virtual
   * catalog endpoint. The result is cached on the store; re-calling
   * refreshes the cache.
   */
  async function loadResponseSetCatalog(): Promise<void> {
    isLoadingCatalog.value = true
    try {
      const list = await apiGet<ResponseSetCatalogEntry[]>('/pages/api/v1/response-sets')
      responseSetCatalog.value = Array.isArray(list) ? list : []
    } catch (e) {
      // Catalog is a UX affordance, not a hard requirement — swallow
      // network errors and surface an empty catalog so the wizard's
      // inline-create branch still works offline.
      if (e instanceof ApiError) {
        error.value = `Antwortset-Katalog nicht verfügbar (HTTP ${e.status}).`
      } else if (e instanceof ApiNetworkError) {
        error.value = 'Antwortset-Katalog nicht erreichbar.'
      }
      responseSetCatalog.value = []
    } finally {
      isLoadingCatalog.value = false
    }
  }

  /**
   * Virtual-create a catalog entry via the backend
   * {@code POST /api/v1/response-sets} endpoint. The endpoint validates
   * the shape + echoes the entry back (DR-020 — no row is persisted
   * until the next CRF version create). The SPA caches the entry so
   * subsequent picker opens can select it without a round-trip.
   */
  async function createCatalogEntry(
    entry: { label: string; responseType: AuthoringResponseType; options: ResponseSetOption[] },
  ): Promise<ResponseSetCatalogEntry | null> {
    try {
      const created = await apiPost<ResponseSetCatalogEntry>(
        '/pages/api/v1/response-sets',
        {
          label: entry.label.trim(),
          responseType: entry.responseType,
          options: entry.options
            .map((opt) => ({ text: opt.text.trim(), value: opt.value.trim() }))
            .filter((opt) => opt.text !== '' || opt.value !== ''),
        },
      )
      const echoed: ResponseSetCatalogEntry = {
        label: created.label,
        responseType: created.responseType,
        options: created.options ?? [],
        usageCount: created.usageCount ?? 0,
        inActiveStudy: created.inActiveStudy ?? false,
      }
      // De-dupe on (label, responseType) — the catalog is keyed by
      // distinct tuples.
      const existing = responseSetCatalog.value.findIndex(
        (e) => e.label === echoed.label && e.responseType === echoed.responseType,
      )
      if (existing >= 0) responseSetCatalog.value.splice(existing, 1, echoed)
      else responseSetCatalog.value.unshift(echoed)
      return echoed
    } catch (e) {
      if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Antwortset konnte nicht angelegt werden (HTTP ${e.status}).`
      } else {
        error.value = 'Antwortset konnte nicht angelegt werden.'
      }
      return null
    }
  }

  function mapError(e: unknown): {
    ok: false
    fieldErrors: Record<string, string>
    parseErrors: string[]
    message?: string
  } {
    if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
      error.value = (e.body as { message?: string } | null)?.message
        ?? `CRF authoring nicht erlaubt (HTTP ${e.status}).`
      return {
        ok: false,
        fieldErrors: {},
        parseErrors: [],
        message: error.value ?? undefined,
      }
    }
    if (e instanceof ApiError) {
      const body = e.body as { message?: string; errors?: Array<{ field: string; message: string }> } | null
      const fieldErrors: Record<string, string> = {}
      const parseErrors: string[] = []
      if (body?.errors) {
        for (const fe of body.errors) {
          // Parser rejections from the synthesised workbook share
          // field="body" — surface them as a separate list so the view
          // can render them in one place rather than overwriting.
          if (fe.field === 'body') parseErrors.push(fe.message)
          else fieldErrors[fe.field] = fe.message
        }
      }
      return {
        ok: false,
        fieldErrors,
        parseErrors,
        message: body?.message ?? `Authoring fehlgeschlagen (HTTP ${e.status}).`,
      }
    }
    if (e instanceof ApiNetworkError) {
      return {
        ok: false,
        fieldErrors: {},
        parseErrors: [],
        message: 'Backend nicht erreichbar — Authoring fehlgeschlagen.',
      }
    }
    return {
      ok: false,
      fieldErrors: {},
      parseErrors: [],
      message: e instanceof Error ? e.message : 'Unbekannter Fehler.',
    }
  }

  return {
    draft,
    isSubmitting,
    isPreviewing,
    error,
    responseSetCatalog,
    isLoadingCatalog,
    selectedItemUid,
    selectItem,
    reset,
    loadFromVersion,
    hydrateDraft,
    setMetadata,
    setVersionName,
    setVersionDescription,
    addSection,
    removeSection,
    reorderSections,
    setSectionBilateral,
    addItem,
    addBilateralPair,
    addCatalogItem,
    addOphthPresetSection,
    applyPreset,
    applyPresetAsSection,
    setSectionBilateralByUid,
    setItemField,
    setItemResponseSet,
    removeItem,
    reorderItems,
    suggestOid,
    buildPayload,
    submit,
    preview,
    loadResponseSetCatalog,
    createCatalogEntry,
  }
})
