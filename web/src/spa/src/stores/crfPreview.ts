import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { computeItemErrors } from '@/stores/crfEntry'
import type {
  CrfEntryStatus,
  CrfItem,
  CrfSchema,
  CrfValues,
} from '@/types/crf'
import type {
  AuthoringDraft,
  AuthoringItem,
  AuthoringResponseSet,
  AuthoringResponseType,
  InlineResponseSet,
} from '@/stores/crfAuthoring'
import {
  buildItemIndex,
  isItemHiddenByRule,
} from '@/components/showWhen'

/**
 * Phase E.6 — CRF authoring **live preview** store.
 *
 * <p>Mirrors the shape of {@link useCrfEntryStore} (`schema`, `values`,
 * `status`, `itemErrors`, `isComplete`) but never touches the network:
 *
 * <ul>
 *   <li>{@link load} takes a {@link CrfSchema} (or an
 *       {@link AuthoringDraft}, converted in-flight via
 *       {@link draftToPreviewSchema}) instead of fetching from an
 *       endpoint;</li>
 *   <li>{@link save} writes to in-memory state only — no `apiPost`;</li>
 *   <li>{@link markComplete} flips the local status;</li>
 *   <li>{@link reset} clears every typed value;</li>
 *   <li>{@link fillSampleData} populates each item with a realistic
 *       sample so the operator can see the rendered form light up.</li>
 * </ul>
 *
 * <p>Validation reuses {@code computeItemErrors} from
 * {@link './crfEntry'} so the preview surfaces the same range / regex /
 * required-field rules the production runtime applies. The operator
 * SEES that BCVA letters > 100 is rejected, IOP > 80 fails the range
 * check, etc.
 *
 * <p>Preview MUST work for every CRF — the authoring wizard's
 * ophth presets and a vanilla Demographics draft alike. The store is
 * intentionally schema-shape-only: it doesn't carry any
 * preset-specific assumptions.
 */

const SAMPLE_DATE_SEED = '2026-06-15'
const SAMPLE_STRING = 'Sample text'

/**
 * Map authoring data-type tokens (canonical names per the wizard) to
 * the runtime {@link CrfItem#dataType} tokens used by the entry view.
 *
 * <p>The current wizard taxonomy is the subset
 * {@code ST | INT | REAL | DATE | PDATE | FILE}; <b>BL</b> was in
 * Milestone A and got dropped during Milestone B — the underlying
 * adapter still accepts it via the same code path used by the XLS
 * uploader, so the preview taxonomy keeps the {@code BL} branch so a
 * future restoration is a one-line change.
 */
function authoringDataTypeToRuntime(
  authoring: AuthoringItem['dataType'] | 'BL',
  responseType: AuthoringResponseType,
): CrfItem['dataType'] {
  // Option-bearing response types override the raw data type: a
  // radio / single-select renders as `select-one`, a multi-select /
  // checkbox group renders as `select-multi`, regardless of the
  // declared item dataType (the workbook adapter follows the same
  // rule — single source of truth = the response set).
  switch (responseType) {
    case 'radio':
    case 'single-select':
      return 'select-one'
    case 'multi-select':
    case 'checkbox':
      return 'select-multi'
    case 'file':
      return 'file'
    case 'text':
    case 'textarea':
    default:
      break
  }
  switch (authoring) {
    case 'INT':
      return 'integer'
    case 'REAL':
      return 'real'
    case 'DATE':
      return 'date'
    case 'PDATE':
      return 'partial-date'
    case 'FILE':
      return 'file'
    case 'BL':
      return 'boolean'
    case 'ST':
    default:
      return 'string'
  }
}

/**
 * Extract the inline option list from an authoring item's response
 * set. Pickers (radio / select / checkbox) carry the options on the
 * inline branch; the by-ref branch leaves resolution to the catalog
 * — for the preview we render the literal label and surface no options
 * (the runtime would have resolved them from the catalog by then).
 */
function extractOptions(
  rs: AuthoringResponseSet,
): { code: string; label: string }[] | undefined {
  if (!rs) return undefined
  if ('ref' in rs) return undefined
  const inline = rs as InlineResponseSet
  if (!inline.options || inline.options.length === 0) return undefined
  return inline.options.map((opt) => ({
    code: opt.value || opt.text,
    label: opt.text || opt.value,
  }))
}

/**
 * Convert the authoring wizard's draft into the runtime CRF schema
 * shape the entry view expects. Used by {@link useCrfPreviewStore.load}
 * when the caller hands us an {@link AuthoringDraft} directly (the
 * common case — the wizard's Preview button captures the live draft).
 */
export function draftToPreviewSchema(draft: AuthoringDraft): CrfSchema {
  return {
    oid: 'F_PREVIEW',
    name: draft.versionName?.trim() || 'CRF preview',
    version: draft.versionDescription?.trim() || 'draft',
    sections: draft.sections.map((section) => ({
      oid: section.uid || `S_${section.ordinal}`,
      title: section.title?.trim() || section.label?.trim() || `Section ${section.ordinal}`,
      instructions: section.instructions?.trim() || undefined,
      items: section.items.map((item) => authoringItemToRuntime(item)),
    })),
  }
}

function authoringItemToRuntime(item: AuthoringItem): CrfItem {
  const dataType = authoringDataTypeToRuntime(item.dataType, item.responseType)
  const options = extractOptions(item.responseSet)
  return {
    oid: item.oid?.trim() || item.uid,
    label: item.descriptionLabel?.trim() || item.name?.trim() || item.uid,
    dataType,
    required: item.required,
    options,
    helper: item.rightItemText?.trim() || undefined,
    // Numeric ranges aren't authored explicitly in the wizard's
    // Milestone B item editor — they ride along on the validation
    // regex. The preview renders them undefined so the entry-view
    // input doesn't clip out-of-range typing prematurely; the
    // validator still flags it via computeItemErrors.
    min: undefined,
    max: undefined,
  } as CrfItem
}

export const useCrfPreviewStore = defineStore('crfPreview', () => {
  const schema = ref<CrfSchema | null>(null)
  const values = ref<CrfValues>({})
  const status = ref<CrfEntryStatus>('not-started')
  const isOpen = ref(false)
  const crfName = ref<string>('')

  /**
   * Phase E.6 polish-runtime — preview mirror of the runtime store's
   * value-preservation map. See {@code crfEntry.ts} for the rationale;
   * the two stores SHARE the show-when evaluator via
   * {@code @/components/showWhen} so the operator sees identical
   * hide/show behaviour at authoring time and at runtime.
   */
  const hiddenValues = ref<Record<string, unknown>>({})
  const warnedDanglingSources = ref<Set<string>>(new Set())

  const itemIndex = computed(() => buildItemIndex(schema.value))

  const hiddenItemOids = computed<Set<string>>(() => {
    const out = new Set<string>()
    if (!schema.value) return out
    for (const section of schema.value.sections) {
      for (const item of section.items) {
        if (isItemHiddenByRule(item, values.value, itemIndex.value, warnedDanglingSources.value)) {
          out.add(item.oid)
        }
      }
    }
    return out
  })

  function isItemHidden(itemOid: string): boolean {
    return hiddenItemOids.value.has(itemOid)
  }

  // Phase E.6 polish-runtime — same watcher pattern as crfEntry.ts.
  // Mutate `values` in place so the watcher doesn't re-trigger via a
  // wholesale reassignment (which would otherwise create a synchronous
  // ping-pong with the {flush:'sync'} watcher).
  let lastHiddenSnapshot = new Set<string>()
  watch(
    hiddenItemOids,
    (current) => {
      for (const oid of current) {
        if (!lastHiddenSnapshot.has(oid)) {
          const v = values.value[oid]
          if (v !== undefined) {
            hiddenValues.value = { ...hiddenValues.value, [oid]: v }
            delete values.value[oid]
          }
        }
      }
      for (const oid of lastHiddenSnapshot) {
        if (!current.has(oid)) {
          if (Object.prototype.hasOwnProperty.call(hiddenValues.value, oid)) {
            values.value[oid] = hiddenValues.value[oid]
            const next = { ...hiddenValues.value }
            delete next[oid]
            hiddenValues.value = next
          }
        }
      }
      lastHiddenSnapshot = new Set(current)
    },
    { flush: 'sync' },
  )

  const itemErrors = computed<Record<string, string>>(() => {
    if (!schema.value) return {}
    return computeItemErrors(schema.value, values.value)
  })

  const isComplete = computed<boolean>(() => {
    if (!schema.value) return false
    // Phase E.6 polish-runtime — required-when-shown: hidden items
    // never block "complete" status.
    for (const oid of Object.keys(itemErrors.value)) {
      if (!hiddenItemOids.value.has(oid)) return false
    }
    return schema.value.sections.every((s) =>
      s.items.every((item) => {
        if (!item.required) return true
        if (hiddenItemOids.value.has(item.oid)) return true
        return hasValue(values.value[item.oid])
      }),
    )
  })

  /**
   * Load a draft for preview. Accepts either a runtime {@link CrfSchema}
   * (the simple path — useful for tests + future schema-based callers)
   * or an {@link AuthoringDraft} (the common path — the wizard's
   * Preview button captures its live draft via this branch).
   */
  function load(draftSchema: CrfSchema | AuthoringDraft, opts?: { crfName?: string }): void {
    const resolved = isAuthoringDraft(draftSchema)
      ? draftToPreviewSchema(draftSchema)
      : draftSchema
    schema.value = resolved
    values.value = {}
    status.value = 'not-started'
    crfName.value = opts?.crfName?.trim() || resolved.name || ''
    isOpen.value = true
    // Phase E.6 polish-runtime — reset show-when bookkeeping on fresh load.
    hiddenValues.value = {}
    warnedDanglingSources.value = new Set()
    lastHiddenSnapshot = new Set()
  }

  function setValue(itemOid: string, value: unknown): void {
    if (!schema.value) return
    values.value[itemOid] = value
    if (status.value === 'not-started') status.value = 'in-progress'
  }

  /**
   * In-memory "save" — flips the status to in-progress but never
   * fires a network request. Rendered as a no-op stub button on the
   * preview view so the operator sees the save-flow chrome without
   * persisting anything.
   */
  function save(): void {
    if (!schema.value) return
    if (status.value === 'not-started') status.value = 'in-progress'
  }

  /**
   * In-memory "mark complete" — flips the local status when the form
   * is valid + every required item is filled. Never calls the backend.
   */
  function markComplete(): void {
    if (!schema.value) return
    if (!isComplete.value) return
    status.value = 'complete'
  }

  /** Clear every typed value + reset the status. Schema is preserved. */
  function reset(): void {
    values.value = {}
    status.value = 'not-started'
    hiddenValues.value = {}
    lastHiddenSnapshot = new Set()
  }

  /** Close the preview overlay + drop the loaded schema. */
  function close(): void {
    isOpen.value = false
    schema.value = null
    values.value = {}
    status.value = 'not-started'
    crfName.value = ''
    hiddenValues.value = {}
    warnedDanglingSources.value = new Set()
    lastHiddenSnapshot = new Set()
  }

  /**
   * Populate each item with a realistic sample value so the operator
   * can see the rendered form light up without typing. Uses a fixed
   * seed date (`2026-06-15`) — calling the current-time built-in
   * throws in this execution environment.
   */
  function fillSampleData(): void {
    if (!schema.value) return
    const out: CrfValues = {}
    for (const section of schema.value.sections) {
      for (const item of section.items) {
        out[item.oid] = sampleValueFor(item)
      }
    }
    values.value = out
    if (status.value === 'not-started') status.value = 'in-progress'
  }

  return {
    schema,
    values,
    status,
    isOpen,
    crfName,
    itemErrors,
    isComplete,
    // Phase E.6 polish-runtime — show-when bookkeeping (preview parity).
    hiddenValues,
    hiddenItemOids,
    isItemHidden,
    load,
    setValue,
    save,
    markComplete,
    reset,
    close,
    fillSampleData,
  }
})

/* -------------------------------------------------------------------------- */
/* Helpers                                                                    */
/* -------------------------------------------------------------------------- */

function hasValue(v: unknown): boolean {
  if (v == null) return false
  if (typeof v === 'string') return v.trim().length > 0
  if (Array.isArray(v)) return v.length > 0
  return true
}

function isAuthoringDraft(x: CrfSchema | AuthoringDraft): x is AuthoringDraft {
  return (
    typeof x === 'object' &&
    x !== null &&
    'versionName' in x &&
    Array.isArray((x as AuthoringDraft).sections) &&
    (x as AuthoringDraft).sections.every((s) => 'items' in s && Array.isArray(s.items))
  )
}

/**
 * Pick a realistic sample value for an item — used by
 * {@link useCrfPreviewStore.fillSampleData}. Behaviour per the
 * Phase E.6 preview spec:
 *
 * <ul>
 *   <li>ST → "Sample text"</li>
 *   <li>INT with range → midpoint</li>
 *   <li>REAL with range → midpoint, 1 decimal</li>
 *   <li>DATE → fixed seed `2026-06-15`</li>
 *   <li>partial-date → fixed seed `2026-06` (YYYY-MM)</li>
 *   <li>BL (boolean) → true</li>
 *   <li>select-one → first option</li>
 *   <li>select-multi → first two options</li>
 *   <li>file → undefined (no fake upload)</li>
 * </ul>
 *
 * <p>Exposed so unit tests can hit it without hydrating the store.
 */
export function sampleValueFor(item: CrfItem): unknown {
  switch (item.dataType) {
    case 'string':
      return SAMPLE_STRING
    case 'integer': {
      const min = item.min ?? 0
      const max = item.max ?? 100
      return Math.round((min + max) / 2)
    }
    case 'real': {
      const min = item.min ?? 0
      const max = item.max ?? 100
      return Math.round(((min + max) / 2) * 10) / 10
    }
    case 'date':
      return SAMPLE_DATE_SEED
    case 'partial-date':
      // 'YYYY-MM' partial — the runtime accepts dashed prefixes via
      // the partial-date branch. Mirrors the date seed month/year.
      return SAMPLE_DATE_SEED.slice(0, 7)
    case 'boolean':
      return true
    case 'select-one':
      return item.options?.[0]?.code ?? ''
    case 'select-multi':
      return (item.options ?? []).slice(0, 2).map((o) => o.code)
    case 'file':
      return undefined
    default:
      return undefined
  }
}
