import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiDelete, apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import { i18n } from '@/i18n'
import type {
  CreateLocationInput,
  Location,
  LocationDetail,
  LocationFieldError,
  UpdateLocationInput,
} from '@/types/location'

const BASE = '/pages/api/v1/admin/locations'

/**
 * A mutation the server refused, or that never got an answer.
 *
 * `fieldErrors` is filled for a 400 (one message per field, keyed by the
 * server's field name: `code`, `name`, `street`, `city`, `zip`, `country`);
 * `message` is the server's own text when it sent one, so the 409 of a
 * disable ("… still in use by 3 studies or sites") reaches the person who
 * asked, and a localised fallback when it did not.
 */
export interface MutationFailure {
  ok: false
  /** The HTTP status; null when no answer arrived. */
  status: number | null
  fieldErrors: Record<string, string>
  message: string
}
export type LocationMutation = { ok: true; location: Location } | MutationFailure
export type LocationDetailMutation = { ok: true; location: LocationDetail } | MutationFailure

/**
 * The global locations (Standorte), for a system administrator.
 *
 * Hydrates the list from `GET /pages/api/v1/admin/locations` (removed ones
 * included) and one location's detail, with its studies and explicitly
 * assigned people, from `GET …/{id}`. Every mutation answers with the
 * changed location, counts included, so the list and the open detail are
 * patched from the answer rather than reloaded: no second request, no
 * flicker, and the counts stay the server's.
 */
export const useAdminLocationsStore = defineStore('adminLocations', () => {
  const locations = ref<Location[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const loaded = ref(false)

  const selectedId = ref<number | null>(null)
  const detail = ref<LocationDetail | null>(null)
  const detailLoading = ref(false)
  const detailError = ref<string | null>(null)
  /** Counts detail requests, so a late answer for a former selection is dropped. */
  let detailSeq = 0

  async function load(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      locations.value = await apiGet<Location[]>(BASE)
      loaded.value = true
    } catch (e) {
      locations.value = []
      error.value = readFailure(e, 'adminLocations.error.loadFailed').message
    } finally {
      isLoading.value = false
    }
  }

  /** Opens one location: its detail replaces whatever was open. */
  async function select(id: number): Promise<void> {
    const seq = ++detailSeq
    selectedId.value = id
    detail.value = null
    detailError.value = null
    detailLoading.value = true
    try {
      const d = await apiGet<LocationDetail>(`${BASE}/${segment(id)}`)
      if (seq !== detailSeq) return
      detail.value = d
      patchList(d)
    } catch (e) {
      if (seq !== detailSeq) return
      detailError.value = readFailure(e, 'adminLocations.error.detailFailed').message
    } finally {
      if (seq === detailSeq) detailLoading.value = false
    }
  }

  function clearSelection(): void {
    detailSeq++
    selectedId.value = null
    detail.value = null
    detailError.value = null
    detailLoading.value = false
  }

  async function create(input: CreateLocationInput): Promise<LocationMutation> {
    try {
      const created = await apiPost<Location>(BASE, input)
      locations.value = sortedByName([...locations.value, summaryOf(created)])
      return { ok: true, location: created }
    } catch (e) {
      return readFailure(e, 'adminLocations.error.saveFailed')
    }
  }

  /** Edits the address and the name; the code is fixed once created. */
  async function update(id: number, input: UpdateLocationInput): Promise<LocationDetailMutation> {
    return changeDetail(() => apiPut<LocationDetail>(`${BASE}/${segment(id)}`, input))
  }

  async function disable(id: number): Promise<LocationMutation> {
    return changeSummary(() => apiPost<Location>(`${BASE}/${segment(id)}/disable`, {}))
  }

  async function restore(id: number): Promise<LocationMutation> {
    return changeSummary(() => apiPost<Location>(`${BASE}/${segment(id)}/restore`, {}))
  }

  /** Assigns a person by username; assigning twice changes nothing. */
  async function assignUser(id: number, username: string): Promise<LocationDetailMutation> {
    return changeDetail(() => apiPut<LocationDetail>(`${BASE}/${segment(id)}/users/${segment(username)}`, {}))
  }

  async function unassignUser(id: number, username: string): Promise<LocationDetailMutation> {
    return changeDetail(() => apiDelete<LocationDetail>(`${BASE}/${segment(id)}/users/${segment(username)}`))
  }

  async function changeDetail(call: () => Promise<LocationDetail>): Promise<LocationDetailMutation> {
    try {
      const changed = await call()
      patchList(changed)
      if (detail.value?.id === changed.id) detail.value = changed
      return { ok: true, location: changed }
    } catch (e) {
      return readFailure(e, 'adminLocations.error.saveFailed')
    }
  }

  async function changeSummary(call: () => Promise<Location>): Promise<LocationMutation> {
    try {
      const changed = await call()
      patchList(changed)
      // The answer carries no studies or people: keep the open ones.
      if (detail.value?.id === changed.id) detail.value = { ...detail.value, ...summaryOf(changed) }
      return { ok: true, location: changed }
    } catch (e) {
      return readFailure(e, 'adminLocations.error.saveFailed')
    }
  }

  /** Replaces the row of a location the list already holds; never adds one. */
  function patchList(changed: Location): void {
    if (!locations.value.some((l) => l.id === changed.id)) return
    locations.value = sortedByName(
      locations.value.map((l) => (l.id === changed.id ? summaryOf(changed) : l)),
    )
  }

  function reset(): void {
    clearSelection()
    locations.value = []
    isLoading.value = false
    error.value = null
    loaded.value = false
  }

  return {
    locations,
    isLoading,
    error,
    loaded,
    selectedId,
    detail,
    detailLoading,
    detailError,
    load,
    select,
    clearSelection,
    create,
    update,
    disable,
    restore,
    assignUser,
    unassignUser,
    reset,
  }
})

/** A path segment: ids and usernames may hold characters a URL does not. */
function segment(part: number | string): string {
  return encodeURIComponent(String(part))
}

/** Just the list fields, so a detail answer does not drag its arrays into the list. */
function summaryOf(l: Location): Location {
  return {
    id: l.id,
    code: l.code,
    name: l.name,
    status: l.status,
    street: l.street,
    city: l.city,
    zip: l.zip,
    country: l.country,
    siteCount: l.siteCount,
    studyCount: l.studyCount,
    ingestItemCount: l.ingestItemCount,
    userCount: l.userCount,
  }
}

/** The server lists by name; the store keeps that order when a name changes or a row is added. */
function sortedByName(list: Location[]): Location[] {
  return [...list].sort((a, b) => a.name.localeCompare(b.name, 'de', { sensitivity: 'base' }))
}

function t(key: string, named?: Record<string, unknown>): string {
  return named ? i18n.global.t(key, named) : i18n.global.t(key)
}

/**
 * What a failed call tells the person: the server's message when it sent
 * one, per-field messages for a 400, else a localised text naming the
 * status (or the missing connection).
 */
function readFailure(e: unknown, fallbackKey: string): MutationFailure {
  if (e instanceof ApiError) {
    const body = e.body as { message?: unknown; errors?: unknown } | null
    const fieldErrors: Record<string, string> = {}
    if (body && Array.isArray(body.errors)) {
      for (const fe of body.errors as Array<Partial<LocationFieldError> | null>) {
        if (typeof fe?.field === 'string' && typeof fe.message === 'string' && !(fe.field in fieldErrors)) {
          fieldErrors[fe.field] = fe.message
        }
      }
    }
    const sent = body && typeof body.message === 'string' ? body.message : ''
    return {
      ok: false,
      status: e.status,
      fieldErrors,
      message: sent !== '' ? sent : t(fallbackKey, { status: e.status }),
    }
  }
  if (e instanceof ApiNetworkError) {
    return { ok: false, status: null, fieldErrors: {}, message: t('adminLocations.error.network') }
  }
  return {
    ok: false,
    status: null,
    fieldErrors: {},
    message: e instanceof Error && e.message !== '' ? e.message : t('adminLocations.error.unknown'),
  }
}
