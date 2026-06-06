import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
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
 * <p>{@code BL} (boolean) is a fixed yes/no — the wizard skips the
 * response-set picker for it and the runtime renders it as a checkbox
 * (legacy {@code item.data_type=11}).
 */
export type AuthoringDataType = 'ST' | 'INT' | 'REAL' | 'DATE' | 'PDATE' | 'FILE' | 'BL'

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
}

export interface AuthoringSection {
  /** Stable uid for vuedraggable item-key; see {@link AuthoringItem.uid}. */
  uid: string
  label: string
  title: string
  instructions: string
  ordinal: number
  items: AuthoringItem[]
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

function emptyValidation(): AuthoringValidation {
  return { regexp: '', errorMessage: '' }
}

let uidCounter = 0
function nextUid(prefix: string): string {
  uidCounter += 1
  return `${prefix}-${uidCounter}`
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

  function reset(): void {
    draft.value = emptyDraft()
    error.value = null
    isSubmitting.value = false
    isPreviewing.value = false
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

  function addSection(seed?: Partial<AuthoringSection>): void {
    const next = draft.value.sections.length + 1
    draft.value.sections.push({
      uid: seed?.uid ?? nextUid('sec'),
      label: seed?.label ?? `S${next}`,
      title: seed?.title ?? `Section ${next}`,
      instructions: seed?.instructions ?? '',
      ordinal: seed?.ordinal ?? next,
      items: seed?.items ?? [],
    })
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
    section.items.push({ ...emptyItem(), ...seed })
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
        items: s.items.map((it) => buildItemPayload(it)),
      })),
    }
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
    return out
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
      label: rs.label.trim(),
      options: rs.options
        .map((opt) => ({ text: opt.text.trim(), value: opt.value.trim() }))
        .filter((opt) => opt.text !== '' || opt.value !== ''),
    }
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
    reset,
    setMetadata,
    setVersionName,
    setVersionDescription,
    addSection,
    removeSection,
    reorderSections,
    addItem,
    setItemField,
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
