import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type {
  CrfEntry,
  CrfEntryStatus,
  CrfItem,
  CrfSchema,
  CrfValues,
} from '@/types/crf'

/**
 * Phase E.5.3 — CRF Entry store.
 *
 * Backs the CrfEntryView. Same pattern as the subjects store: hydrates
 * from a mock loader shaped exactly like the planned
 * `GET /pages/api/v1/eventCrfs/{id}` response so the view code is
 * written against the production contract.
 *
 * The `pendingChanges` flag drives the "unsaved · auto-saving…" tell
 * in the header. A real auto-save implementation lands in a follow-up;
 * for now the manual save action flushes `values` to the mock layer.
 */
export const useCrfEntryStore = defineStore('crfEntry', () => {
  const entry = ref<CrfEntry | null>(null)
  const isLoading = ref(false)
  const isSaving = ref(false)
  const error = ref<string | null>(null)
  const pendingChanges = ref(false)

  const schema = computed<CrfSchema | null>(() => entry.value?.schema ?? null)
  const values = computed<CrfValues>(() => entry.value?.values ?? {})
  const status = computed<CrfEntryStatus>(() => entry.value?.status ?? 'not-started')

  /** Item oids whose validation currently fails (used by the section badge). */
  const itemErrors = computed<Record<string, string>>(() => {
    if (!entry.value) return {}
    return computeItemErrors(entry.value.schema, entry.value.values)
  })

  const isComplete = computed<boolean>(() => {
    if (!entry.value) return false
    if (Object.keys(itemErrors.value).length > 0) return false
    return entry.value.schema.sections.every((s) =>
      s.items.every((item) => !item.required || hasValue(entry.value!.values[item.oid])),
    )
  })

  async function load(eventCrfOid: string): Promise<void> {
    isLoading.value = true
    error.value = null
    pendingChanges.value = false
    try {
      // TODO(E.4): apiGet<CrfEntry>(`/pages/api/v1/eventCrfs/${eventCrfOid}`).
      entry.value = await loadMock(eventCrfOid)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error loading CRF'
    } finally {
      isLoading.value = false
    }
  }

  function setValue(itemOid: string, value: unknown): void {
    if (!entry.value) return
    entry.value.values[itemOid] = value
    pendingChanges.value = true
    if (entry.value.status === 'not-started') entry.value.status = 'in-progress'
  }

  async function save(): Promise<void> {
    if (!entry.value || !pendingChanges.value) return
    isSaving.value = true
    try {
      // TODO(E.4): apiPost(`/pages/api/v1/eventCrfs/${entry.value.eventCrfOid}/items`, values).
      await new Promise((resolve) => setTimeout(resolve, 50))
      entry.value.lastSavedAt = new Date().toISOString()
      pendingChanges.value = false
    } finally {
      isSaving.value = false
    }
  }

  async function markComplete(): Promise<void> {
    if (!entry.value) return
    if (!isComplete.value) {
      error.value = 'Required items are missing or invalid — fix them before marking the CRF complete.'
      return
    }
    await save()
    entry.value.status = 'complete'
  }

  return {
    entry,
    isLoading,
    isSaving,
    error,
    pendingChanges,
    schema,
    values,
    status,
    itemErrors,
    isComplete,
    load,
    setValue,
    save,
    markComplete,
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

/**
 * Per-item validation: returns a flat oid → message map.
 * Exposed so unit tests can hit it directly without hydrating the store.
 */
export function computeItemErrors(schema: CrfSchema, values: CrfValues): Record<string, string> {
  const out: Record<string, string> = {}
  for (const section of schema.sections) {
    for (const item of section.items) {
      const msg = validateItem(item, values[item.oid])
      if (msg) out[item.oid] = msg
    }
  }
  return out
}

function validateItem(item: CrfItem, raw: unknown): string | null {
  if (item.required && !hasValue(raw)) {
    return `${item.label} is required.`
  }
  if (!hasValue(raw)) return null

  switch (item.dataType) {
    case 'integer': {
      const n = Number(raw)
      if (!Number.isInteger(n)) return `${item.label} must be a whole number.`
      if (item.min != null && n < item.min) return `${item.label} must be ≥ ${item.min}.`
      if (item.max != null && n > item.max) return `${item.label} must be ≤ ${item.max}.`
      return null
    }
    case 'real': {
      const n = Number(raw)
      if (!Number.isFinite(n)) return `${item.label} must be a number.`
      if (item.min != null && n < item.min) return `${item.label} must be ≥ ${item.min}.`
      if (item.max != null && n > item.max) return `${item.label} must be ≤ ${item.max}.`
      return null
    }
    case 'date': {
      if (typeof raw !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(raw)) {
        return `${item.label} must be a YYYY-MM-DD date.`
      }
      return null
    }
    case 'select-one': {
      if (!item.options?.some((o) => o.code === String(raw))) {
        return `${item.label}: choose one of the allowed values.`
      }
      return null
    }
    case 'select-multi': {
      if (!Array.isArray(raw)) return `${item.label}: expected a list of codes.`
      if (raw.some((v) => !item.options?.some((o) => o.code === String(v)))) {
        return `${item.label}: contains an unknown code.`
      }
      return null
    }
    default:
      return null
  }
}

/* -------------------------------------------------------------------------- */
/* Mock loader — production-shape CrfEntry for M-001 · V1 Inclusion ·         */
/* Demographics. Replace with `apiGet<CrfEntry>` when the E.4 adapter lands.   */
/* -------------------------------------------------------------------------- */

async function loadMock(eventCrfOid: string): Promise<CrfEntry> {
  await new Promise((resolve) => setTimeout(resolve, 30))

  const schema: CrfSchema = {
    oid: 'F_DEMOGRAPHICS_V1',
    name: 'Demographics',
    version: 'v1.0',
    sections: [
      {
        oid: 'S_IDENT',
        title: 'Identification',
        instructions: 'Source-document fields. Do not re-enter the Subject ID — it is pre-filled from the matrix.',
        items: [
          {
            oid: 'I_CONSENT_DATE',
            label: 'Date of informed consent',
            dataType: 'date',
            required: true,
            helper: 'YYYY-MM-DD. Must be on or before the enrolment date.',
          },
          {
            oid: 'I_CONSENT_SIGNED',
            label: 'Consent signed?',
            dataType: 'select-one',
            required: true,
            options: [
              { code: 'Y', label: 'Yes' },
              { code: 'N', label: 'No' },
            ],
          },
        ],
      },
      {
        oid: 'S_VITALS',
        title: 'Vitals',
        instructions: 'Captured at the V1 visit, before any study intervention.',
        items: [
          {
            oid: 'I_HEIGHT_CM',
            label: 'Height',
            dataType: 'integer',
            required: true,
            min: 50,
            max: 250,
            helper: 'cm — whole number.',
          },
          {
            oid: 'I_WEIGHT_KG',
            label: 'Weight',
            dataType: 'real',
            required: true,
            min: 1,
            max: 300,
            helper: 'kg — one decimal place.',
          },
          {
            oid: 'I_BLOOD_PRESSURE_SYS',
            label: 'Systolic BP',
            dataType: 'integer',
            required: false,
            min: 50,
            max: 250,
            helper: 'mmHg — optional this visit.',
          },
        ],
      },
    ],
  }

  return {
    eventCrfOid,
    subjectId: 'M-001',
    eventLabel: 'V1 Inclusion',
    schema,
    values: {},
    status: 'not-started',
    lastSavedAt: null,
  }
}
