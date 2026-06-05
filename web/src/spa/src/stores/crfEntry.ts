import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  CrfEntry,
  CrfEntryStatus,
  CrfItem,
  CrfSchema,
  CrfValues,
  MissingReasonsError,
  SaveItemsRequest,
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

  /**
   * Phase E.6 admin-rfc — staged reason-for-change text per dirty item.
   * Populated by {@link stageReason} when the user submits the
   * {@code ReasonForChangeModal}; sent alongside `values` on `save()`
   * when the backing entry is `requiresReasonForChange`.
   *
   * Cleared on successful save; partially re-armed when the backend
   * returns a 400 with `missingReasonItemOids` (we drop the served
   * oids' staged reasons so the modal asks again for just those).
   */
  const pendingReasons = ref<Record<string, string>>({})

  /**
   * Phase E.6 admin-rfc — item OIDs the modal should currently solicit
   * reasons for. The view watches this; non-empty means open the modal
   * with one prompt per oid.
   */
  const missingReasonItemOids = ref<string[]>([])

  const schema = computed<CrfSchema | null>(() => entry.value?.schema ?? null)
  const values = computed<CrfValues>(() => entry.value?.values ?? {})
  const status = computed<CrfEntryStatus>(() => entry.value?.status ?? 'not-started')

  /** True when the backing entry needs an RFC for every edit (post-complete). */
  const requiresReasonForChange = computed<boolean>(
    () => entry.value?.requiresReasonForChange === true,
  )

  /**
   * Phase E.6 — dirty item OIDs (set via setValue since the last save).
   * The modal prompts for one reason per dirty oid; the view also reads
   * this so Save stays disabled while any dirty oid lacks a staged
   * reason on a post-complete entry.
   */
  const dirtyItemOids = ref<Set<string>>(new Set())

  /** OIDs that need a reason before Save can fire (post-complete only). */
  const itemsAwaitingReason = computed<string[]>(() => {
    if (!requiresReasonForChange.value) return []
    return Array.from(dirtyItemOids.value).filter(
      (oid) => !pendingReasons.value[oid] || pendingReasons.value[oid].trim().length === 0,
    )
  })

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
    pendingReasons.value = {}
    missingReasonItemOids.value = []
    dirtyItemOids.value = new Set()
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
    // Phase E.6 admin-rfc — track dirty oids so the modal knows which
    // items still need a reason before Save can fire.
    if (entry.value.requiresReasonForChange) {
      dirtyItemOids.value = new Set([...dirtyItemOids.value, itemOid])
    }
  }

  /**
   * Phase E.6 admin-rfc — record a reason for one item OID. Called by
   * the {@code ReasonForChangeModal} per prompt. Trims whitespace; an
   * empty reason removes the staged entry so the modal will re-ask.
   */
  function stageReason(itemOid: string, reason: string): void {
    const trimmed = reason.trim()
    if (trimmed.length === 0) {
      const { [itemOid]: _drop, ...rest } = pendingReasons.value
      pendingReasons.value = rest
      return
    }
    pendingReasons.value = { ...pendingReasons.value, [itemOid]: trimmed }
    // Drop this oid from the missing list so the modal can dismiss
    // once every prompt has been answered.
    missingReasonItemOids.value = missingReasonItemOids.value.filter((o) => o !== itemOid)
  }

  /**
   * Phase E.6 admin-rfc — explicit modal-dismiss helper. Clears the
   * `missingReasonItemOids` ref so the view's `v-model:open` flips back
   * to closed; pending reasons stay staged for the next save attempt.
   */
  function dismissReasonModal(): void {
    missingReasonItemOids.value = []
  }

  async function save(): Promise<void> {
    if (!entry.value || !pendingChanges.value) return
    const target = entry.value
    isSaving.value = true
    error.value = null
    // Phase E.6 admin-rfc — short-circuit if the entry is post-complete
    // and any dirty item is missing a staged reason. The view shouldn't
    // call save() in this state (the button is gated), but the guard
    // makes the contract explicit + simplifies tests.
    if (requiresReasonForChange.value && itemsAwaitingReason.value.length > 0) {
      missingReasonItemOids.value = [...itemsAwaitingReason.value]
      isSaving.value = false
      return
    }
    // Build the request body. Pre-complete edits omit `reasons`; the
    // backend treats null + missing identically.
    const body: SaveItemsRequest = requiresReasonForChange.value
      ? { values: target.values, reasons: { ...pendingReasons.value } }
      : { values: target.values }
    try {
      const response = await apiPost<SaveItemsResponse>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(target.eventCrfOid)}/items`,
        body,
      )
      target.lastSavedAt = response.lastSavedAt ?? new Date().toISOString()
      if (response.status === 'in-progress' && target.status === 'not-started') {
        target.status = 'in-progress'
      }
      pendingChanges.value = false
      // Save committed — drop staged reasons + clear dirty oid tracker.
      pendingReasons.value = {}
      missingReasonItemOids.value = []
      dirtyItemOids.value = new Set()
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      // Phase E.6 admin-rfc — 400 with `missingReasonItemOids` re-arms
      // the modal scoped to the offending oids so the operator can
      // supply the reasons + retry without losing typed values.
      if (e instanceof ApiError && e.status === 400) {
        const body = e.body as MissingReasonsError | { message?: string } | null
        const oids = (body as MissingReasonsError | null)?.missingReasonItemOids
        if (Array.isArray(oids) && oids.length > 0) {
          missingReasonItemOids.value = [...oids]
          // Drop the offending oids' staged reasons so the modal
          // re-prompts (the backend won't trust a reason we already
          // sent + it rejected as missing).
          const next = { ...pendingReasons.value }
          for (const oid of oids) delete next[oid]
          pendingReasons.value = next
          error.value = (body as { message?: string } | null)?.message
            ?? 'Bitte Begründung für die markierten Felder eintragen.'
          return
        }
        const errBody = e.body as { message?: string } | null
        error.value = errBody?.message ?? `Speichern fehlgeschlagen (HTTP ${e.status}).`
        return
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Speichern fehlgeschlagen. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Speichern fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Speichern.'
      }
    } finally {
      isSaving.value = false
    }
  }

  async function markComplete(): Promise<void> {
    if (!entry.value) return
    if (!isComplete.value) {
      error.value =
        'Required items are missing or invalid — fix them before marking the CRF complete.'
      return
    }
    const target = entry.value
    // Flush pending edits first; if save fails, abort the markComplete.
    if (pendingChanges.value) {
      await save()
      if (error.value) return
    }
    isSaving.value = true
    error.value = null
    try {
      const response = await apiPost<MarkCompleteResponse>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(target.eventCrfOid)}/markComplete`,
        {},
      )
      target.status = (response.status as typeof target.status) ?? 'complete'
      target.lastSavedAt = response.lastSavedAt ?? target.lastSavedAt
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — markComplete fehlgeschlagen. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `markComplete fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler.'
      }
    } finally {
      isSaving.value = false
    }
  }

  /**
   * Phase E A5 — reopen a previously-completed CRF for editing.
   * The backend (`POST /api/v1/eventCrfs/{id}/markIncomplete`)
   * enforces role gates (Investigator / CRC / DM / Admin permitted;
   * Monitor / RA forbidden) plus state guards (locked / signed / not
   * currently complete return 409).
   *
   * <p>On success the entry's status flips back to whatever
   * `computeStatus` reports (typically `in-progress`); the SPA's
   * form fields become editable again. The lastSavedAt timestamp
   * stays at the previous save (reopen doesn't write item data).
   */
  async function reopen(): Promise<void> {
    if (!entry.value) return
    if (entry.value.status !== 'complete') {
      error.value =
        'CRF ist nicht abgeschlossen — kein Wiedereröffnen erforderlich.'
      return
    }
    const target = entry.value
    isSaving.value = true
    error.value = null
    try {
      const response = await apiPost<MarkCompleteResponse>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(target.eventCrfOid)}/markIncomplete`,
        {},
      )
      target.status = (response.status as typeof target.status) ?? 'in-progress'
      target.lastSavedAt = response.lastSavedAt ?? target.lastSavedAt
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Wiedereröffnen nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Wiedereröffnen fehlgeschlagen. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Wiedereröffnen fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Wiedereröffnen.'
      }
    } finally {
      isSaving.value = false
    }
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
    // Phase E.6 admin-rfc — RFC capture surface.
    pendingReasons,
    missingReasonItemOids,
    dirtyItemOids,
    requiresReasonForChange,
    itemsAwaitingReason,
    load,
    setValue,
    save,
    markComplete,
    reopen,
    stageReason,
    dismissReasonModal,
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
/* Phase E.4 M5 + M6 (2026-06-01): the hardcoded Demographics mock loader +    */
/* OID-decoding helpers (decodeContext, humaniseTokens, KNOWN_EVENTS) were     */
/* removed. The store now hydrates exclusively from                            */
/* `GET /pages/api/v1/eventCrfs/{id}` via apiGet above, and persists via       */
/* POST /pages/api/v1/eventCrfs/{id}/items  + POST .../markComplete (M6).      */
/* -------------------------------------------------------------------------- */

/** Wire shape of the POST /items endpoint response (M6 + E.6). */
interface SaveItemsResponse {
  eventCrfOid: string
  savedItemCount: number
  rejectedItemCount: number
  /** Phase E.6 admin-rfc — count of `discrepancy_note` rows written. */
  rfcCreatedCount?: number
  lastSavedAt: string | null
  status: CrfEntryStatus
}

/** Wire shape of the POST /markComplete endpoint response (M6). */
interface MarkCompleteResponse {
  eventCrfOid: string
  status: CrfEntryStatus
  lastSavedAt: string | null
}
