import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiDelete, apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import { apiUpload } from '@/api/upload'
import type {
  CrfEntry,
  CrfEntryStatus,
  CrfGroupRow,
  CrfItem,
  CrfItemGroup,
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
  const groups = computed<CrfItemGroup[]>(() => entry.value?.groups ?? [])

  /** Phase E.6 — per-group, per-row dirty values awaiting flush in save(). */
  const dirtyGroupRows = ref<
    Map<string, Map<number, Map<string, unknown>>>
  >(new Map())

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

  /**
   * Phase E.6 — write the value for an item inside a repeating group's
   * row. Mirrors {@link setValue} but routes the write into the matching
   * {@link CrfGroupRow#values}; the row is also tracked in
   * {@code dirtyGroupRows} so {@link save} flushes it to the backend.
   */
  function setValueInRow(
    groupOid: string,
    rowOrdinal: number,
    itemOid: string,
    value: unknown,
  ): void {
    if (!entry.value) return
    const group = entry.value.groups.find((g) => g.oid === groupOid)
    if (!group) return
    let row = group.rows.find((r) => r.ordinal === rowOrdinal)
    if (!row) {
      row = { ordinal: rowOrdinal, values: {} }
      group.rows.push(row)
      group.rows.sort((a, b) => a.ordinal - b.ordinal)
    }
    row.values[itemOid] = value
    let byOrd = dirtyGroupRows.value.get(groupOid)
    if (!byOrd) {
      byOrd = new Map()
      dirtyGroupRows.value.set(groupOid, byOrd)
    }
    let byItem = byOrd.get(rowOrdinal)
    if (!byItem) {
      byItem = new Map()
      byOrd.set(rowOrdinal, byItem)
    }
    byItem.set(itemOid, value)
    pendingChanges.value = true
    if (entry.value.status === 'not-started') entry.value.status = 'in-progress'
  }

  /**
   * Phase E.6 — POST a new row into a repeating group. Backend
   * allocates the next ordinal and returns it; the SPA appends a
   * fresh blank row to the in-memory group so the user can start
   * typing into it immediately. Rejects when {@code repeatMax} is
   * already reached (backend returns 409 + REPEAT_MAX_REACHED).
   */
  async function addGroupRow(groupOid: string): Promise<CrfGroupRow | null> {
    if (!entry.value) return null
    const target = entry.value
    const group = target.groups.find((g) => g.oid === groupOid)
    if (!group) {
      error.value = `Unknown repeating group: ${groupOid}`
      return null
    }
    if (group.rows.length >= group.repeatMax) {
      // The view should grey out the button already, but guard anyway.
      error.value = `crfEntry.group.repeatMaxReached`
      return null
    }
    isSaving.value = true
    error.value = null
    try {
      const res = await apiPost<AddGroupRowResponse>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(target.eventCrfOid)}` +
          `/groups/${encodeURIComponent(groupOid)}/rows`,
        {},
      )
      const newRow: CrfGroupRow = { ordinal: res.rowOrdinal, values: res.values ?? {} }
      group.rows.push(newRow)
      group.rows.sort((a, b) => a.ordinal - b.ordinal)
      return newRow
    } catch (e) {
      handleApiError(e, 'Hinzufügen einer Zeile')
      return null
    } finally {
      isSaving.value = false
    }
  }

  /**
   * Phase E.6 — DELETE a row from a repeating group. The backend
   * soft-deletes every {@code item_data} row at the matching ordinal;
   * the SPA drops the row from the in-memory group on success.
   */
  async function deleteGroupRow(
    groupOid: string,
    rowOrdinal: number,
  ): Promise<boolean> {
    if (!entry.value) return false
    const target = entry.value
    const group = target.groups.find((g) => g.oid === groupOid)
    if (!group) return false
    isSaving.value = true
    error.value = null
    try {
      await apiDelete<void>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(target.eventCrfOid)}` +
          `/groups/${encodeURIComponent(groupOid)}/rows/${rowOrdinal}`,
      )
      group.rows = group.rows.filter((r) => r.ordinal !== rowOrdinal)
      const byOrd = dirtyGroupRows.value.get(groupOid)
      byOrd?.delete(rowOrdinal)
      return true
    } catch (e) {
      handleApiError(e, 'Löschen der Zeile')
      return false
    } finally {
      isSaving.value = false
    }
  }

  /**
   * Phase E.6 — upload a file for a file-typed item. POSTs the file
   * via multipart/form-data; the backend stores the bytes under
   * {@code attached_file_location} and writes the resolved path
   * into {@code item_data.value}. The SPA pre-validates the size
   * + extension against the cap surfaced in {@link CrfEntry}.
   */
  async function uploadFile(
    itemOid: string,
    file: File,
    rowOrdinal = 1,
  ): Promise<boolean> {
    if (!entry.value) return false
    const target = entry.value
    // Client-side pre-check; backend enforces too.
    if (target.maxFileBytes > 0 && file.size > target.maxFileBytes) {
      error.value = 'crfEntry.file.tooBig'
      return false
    }
    if (target.fileExtensions) {
      const ext = (file.name.split('.').pop() ?? '').toLowerCase()
      const allowed = target.fileExtensions.split(',').map((s) => s.trim().toLowerCase())
      if (ext && !allowed.includes(ext)) {
        error.value = 'crfEntry.file.badExtension'
        return false
      }
    }
    isSaving.value = true
    error.value = null
    try {
      const res = await apiUpload<FileUploadResponse>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(target.eventCrfOid)}` +
          `/items/${encodeURIComponent(itemOid)}/file`,
        file,
        { rowOrdinal: String(rowOrdinal) },
      )
      const fileRef = {
        filename: res.filename,
        bytes: res.bytes,
        contentType: res.contentType,
        storedPath: res.storedPath,
        rowOrdinal: res.rowOrdinal ?? rowOrdinal,
      }
      if (rowOrdinal === 1) {
        target.values[itemOid] = fileRef
      } else {
        for (const g of target.groups) {
          const row = g.rows.find((r) => r.ordinal === rowOrdinal)
          if (row && g.itemOids.includes(itemOid)) {
            row.values[itemOid] = fileRef
            break
          }
        }
      }
      target.lastSavedAt = res.lastSavedAt ?? new Date().toISOString()
      return true
    } catch (e) {
      handleApiError(e, 'Hochladen der Datei')
      return false
    } finally {
      isSaving.value = false
    }
  }

  /** Phase E.6 — DELETE an uploaded file for a file-typed item. */
  async function deleteFile(itemOid: string, rowOrdinal = 1): Promise<boolean> {
    if (!entry.value) return false
    const target = entry.value
    isSaving.value = true
    error.value = null
    try {
      await apiDelete<void>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(target.eventCrfOid)}` +
          `/items/${encodeURIComponent(itemOid)}/file?rowOrdinal=${rowOrdinal}`,
      )
      if (rowOrdinal === 1) {
        delete target.values[itemOid]
      } else {
        for (const g of target.groups) {
          const row = g.rows.find((r) => r.ordinal === rowOrdinal)
          if (row) delete row.values[itemOid]
        }
      }
      return true
    } catch (e) {
      handleApiError(e, 'Löschen der Datei')
      return false
    } finally {
      isSaving.value = false
    }
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
      // Phase E.6 — bundle dirty repeating-group row writes with the
      // top-level values payload so the backend gets a single saveItems
      // call. The backend response carries groupRowsSaved which we
      // surface for the optional Toast.
      const groupsPayload: Array<{
        groupOid: string
        rowOrdinal: number
        values: Record<string, unknown>
      }> = []
      for (const [groupOid, byOrd] of dirtyGroupRows.value.entries()) {
        for (const [rowOrdinal, byItem] of byOrd.entries()) {
          const vals: Record<string, unknown> = {}
          byItem.forEach((v, k) => {
            vals[k] = v
          })
          groupsPayload.push({ groupOid, rowOrdinal, values: vals })
        }
      }
      // Phase E.6 — combine RFC reasons (admin-rfc) with repeating-group
      // rows (crf-data-types) in a single saveItems payload.
      const payload: SaveItemsRequest = { ...body, groups: groupsPayload }
      const response = await apiPost<SaveItemsResponse>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(target.eventCrfOid)}/items`,
        payload,
      )
      target.lastSavedAt = response.lastSavedAt ?? new Date().toISOString()
      if (response.status === 'in-progress' && target.status === 'not-started') {
        target.status = 'in-progress'
      }
      dirtyGroupRows.value.clear()
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

  /** Phase E.6 — shared error handler for the new mutating actions. */
  function handleApiError(e: unknown, op: string): void {
    if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
      const body = e.body as { message?: string } | null
      error.value = body?.message ?? `${op} verweigert (HTTP ${e.status}).`
      return
    }
    if (e instanceof ApiNetworkError) {
      error.value = `Backend nicht erreichbar — ${op} fehlgeschlagen.`
    } else if (e instanceof ApiError) {
      const body = e.body as { message?: string; code?: string } | null
      if (body?.code === 'REPEAT_MAX_REACHED') {
        error.value = 'crfEntry.group.repeatMaxReached'
      } else {
        error.value = body?.message ?? `${op} fehlgeschlagen (HTTP ${e.status}).`
      }
    } else {
      error.value = e instanceof Error ? e.message : `Unbekannter Fehler — ${op}.`
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
    groups,
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
    setValueInRow,
    addGroupRow,
    deleteGroupRow,
    uploadFile,
    deleteFile,
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
  /** Phase E.6 — count of repeating-group item writes that landed. */
  groupRowsSaved?: number
  lastSavedAt: string | null
  status: CrfEntryStatus
}

/** Phase E.6 — wire shape of POST /eventCrfs/{id}/groups/{groupOid}/rows. */
interface AddGroupRowResponse {
  groupOid: string
  rowOrdinal: number
  values?: Record<string, unknown>
}

/** Phase E.6 — wire shape of POST /eventCrfs/{id}/items/{itemOid}/file. */
interface FileUploadResponse {
  itemOid: string
  rowOrdinal?: number
  filename: string
  bytes: number
  contentType: string | null
  storedPath: string
  lastSavedAt: string | null
}

/** Wire shape of the POST /markComplete endpoint response (M6). */
interface MarkCompleteResponse {
  eventCrfOid: string
  status: CrfEntryStatus
  lastSavedAt: string | null
}
