import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiDelete as _apiDelete, apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type {
  CreateEventDefinitionInput,
  EventDefinition,
  UpdateEventDefinitionInput,
} from '@/types/eventDefinition'

// Suppress unused import warning — apiDelete is part of the public
// client surface; we intentionally don't use DELETE here (disable
// is a POST flip), but importing keeps future imports terse.
void _apiDelete

/**
 * Phase E A8.2 — event-definition CRUD store.
 *
 * One studyOid at a time; the list is hydrated on demand and the
 * mutation actions update the in-memory list in place so the view
 * doesn't need to refetch after every change.
 *
 * Mock removal: no fallback fixtures — backend is the only source
 * of truth.
 */
export const useEventDefinitionsStore = defineStore('eventDefinitions', () => {
  const rows = ref<EventDefinition[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  async function load(studyOid: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      rows.value = await apiGet<EventDefinition[]>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/event-definitions`,
      )
    } catch (e) {
      rows.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value = 'Backend nicht erreichbar — Visiten können nicht geladen werden.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Fehler beim Laden der Visiten (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden der Visiten.'
      }
    } finally {
      isLoading.value = false
    }
  }

  type MutationResult =
    | { ok: true; eventDef: EventDefinition }
    | { ok: false; fieldErrors: Record<string, string>; message?: string }

  async function create(studyOid: string, input: CreateEventDefinitionInput): Promise<MutationResult> {
    return mutate(
      () => apiPost<EventDefinition>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/event-definitions`,
        input,
      ),
      'create',
      (eventDef) => { rows.value = [...rows.value, eventDef] },
    )
  }

  async function update(
    studyOid: string,
    sedOid: string,
    patch: UpdateEventDefinitionInput,
  ): Promise<MutationResult> {
    return mutate(
      () => apiPut<EventDefinition>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/event-definitions/${encodeURIComponent(sedOid)}`,
        patch,
      ),
      'update',
      (eventDef) => {
        const idx = rows.value.findIndex((r) => r.oid === sedOid)
        if (idx >= 0) rows.value[idx] = eventDef
      },
    )
  }

  async function disable(studyOid: string, sedOid: string): Promise<boolean> {
    try {
      const updated = await apiPost<EventDefinition>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/event-definitions/${encodeURIComponent(sedOid)}/disable`,
        {},
      )
      const idx = rows.value.findIndex((r) => r.oid === sedOid)
      if (idx >= 0) rows.value[idx] = updated
      return true
    } catch (e) {
      handleNonValidationError(e, 'disable')
      return false
    }
  }

  /**
   * Reorder bulk-updates ordinals. The view passes the new ordering
   * as an explicit list of OIDs — the backend re-emits the full list
   * with refreshed ordinals so the store can swap state atomically.
   */
  async function reorder(studyOid: string, orderedOids: string[]): Promise<boolean> {
    try {
      const refreshed = await apiPost<EventDefinition[]>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/event-definitions/reorder`,
        { orderedOids },
      )
      rows.value = refreshed
      return true
    } catch (e) {
      handleNonValidationError(e, 'reorder')
      return false
    }
  }

  async function mutate(
    op: () => Promise<EventDefinition>,
    label: 'create' | 'update',
    onSuccess: (e: EventDefinition) => void,
  ): Promise<MutationResult> {
    try {
      const eventDef = await op()
      onSuccess(eventDef)
      return { ok: true, eventDef }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Visite ${label} nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiError) {
        const errBody = e.body as
          | { message?: string; errors?: Array<{ field: string; message: string }> }
          | null
        const fieldErrors: Record<string, string> = {}
        if (errBody?.errors) for (const fe of errBody.errors) fieldErrors[fe.field] = fe.message
        return {
          ok: false,
          fieldErrors,
          message: errBody?.message ?? `Visite ${label} fehlgeschlagen (HTTP ${e.status}).`,
        }
      }
      if (e instanceof ApiNetworkError) {
        return {
          ok: false,
          fieldErrors: {},
          message: `Backend nicht erreichbar — Visite ${label} fehlgeschlagen.`,
        }
      }
      return {
        ok: false,
        fieldErrors: {},
        message: e instanceof Error ? e.message : `Unbekannter Fehler beim Visite-${label}.`,
      }
    }
  }

  function handleNonValidationError(e: unknown, op: string) {
    if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
      const body = e.body as { message?: string } | null
      error.value = body?.message ?? `Visite ${op} nicht erlaubt (HTTP ${e.status}).`
      throw e
    }
    if (e instanceof ApiNetworkError) {
      error.value = `Backend nicht erreichbar — Visite ${op} fehlgeschlagen.`
    } else if (e instanceof ApiError) {
      const body = e.body as { message?: string } | null
      error.value = body?.message ?? `Visite ${op} fehlgeschlagen (HTTP ${e.status}).`
    } else {
      error.value = e instanceof Error ? e.message : `Unbekannter Fehler beim Visite-${op}.`
    }
  }

  return {
    rows,
    isLoading,
    error,
    load,
    create,
    update,
    disable,
    reorder,
  }
})
