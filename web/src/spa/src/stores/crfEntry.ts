import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type {
  CrfEntry,
  CrfEntryStatus,
  CrfItem,
  CrfSchema,
  CrfValues,
} from '@/types/crf'

/**
 * Phase E.5.3 + E.4 M5 — CRF Entry store.
 *
 * Calls `GET /pages/api/v1/eventCrfs/{id}` (adapter shipped in
 * E.4 M5). The endpoint accepts a numeric event_crf_id as a path
 * param; the SPA carries it as a string (the `eventCrfOid` field
 * in `CrfEntry`) since the legacy `event_crf` table doesn't have
 * an OID column. Future Subject Matrix link generation will pass
 * the numeric id as the same string.
 *
 * The `pendingChanges` flag drives the "unsaved · auto-saving…" tell
 * in the header. Real save/markComplete land in M6 — for now those
 * actions update local state only and remain `TODO(E.4 M6)`.
 *
 * Mock removal — per the polished-jumping-swan plan's "hard removal"
 * policy: the previous `loadMock()` Demographics builder + the
 * `decodeContext` / `KNOWN_EVENTS` / `humaniseTokens` helpers are
 * deleted in this PR. If the backend is unreachable the store sets
 * `error` so the view can render a clear message rather than
 * silently falling back to mock data.
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
    entry.value = null
    try {
      entry.value = await apiGet<CrfEntry>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(eventCrfOid)}`,
      )
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        // Let the router-level auth guard handle these — propagate so
        // the calling view doesn't silently render a stale entry.
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — CRF kann nicht geladen werden. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Fehler beim Laden des CRF (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden des CRF.'
      }
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
      // TODO(E.4 M6): apiPost(`/pages/api/v1/eventCrfs/${entry.value.eventCrfOid}/items`, values).
      // M5 wired the read path; M6 covers incremental save + markComplete.
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
/* Phase E.4 M5 (2026-06-01): the hardcoded Demographics mock loader +         */
/* OID-decoding helpers (decodeContext, humaniseTokens, KNOWN_EVENTS) were     */
/* removed. The store now hydrates exclusively from                            */
/* `GET /pages/api/v1/eventCrfs/{id}` via apiGet above.                        */
/* -------------------------------------------------------------------------- */
