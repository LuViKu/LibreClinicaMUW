import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, apiPut } from '@/api/client'
import { readMutationFailure, type MutationFailure } from '@/lib/mutationFailure'
import type { IssuedUploader, ManagedUploader } from '@/types/uploader'

const BASE = '/pages/api/v1/uploaders'
const LOCATIONS = '/pages/api/v1/locations'

export type { MutationFailure }
/** A change that answers with the changed uploader (rename, revoke). */
export type UploaderMutation = { ok: true; uploader: ManagedUploader } | MutationFailure
/**
 * A change that issued a token (create, replace). The token is in this
 * result and nowhere else: the store does not keep it, the caller shows it
 * once and lets it go.
 */
export type IssuedMutation = { ok: true; uploader: ManagedUploader; token: string } | MutationFailure

/**
 * The uploaders of the locations the session may manage, and their tokens.
 *
 * `GET /pages/api/v1/uploaders` answers with every uploader the caller may
 * see: all locations for a system administrator, their own for a
 * Standort-Admin. The page filters that list by the location it shows, so
 * switching location costs no request. Every change answers with the
 * changed uploader, so the list is patched from the answer rather than
 * reloaded.
 *
 * A token is returned by exactly two calls, create and replace. It is handed
 * to the caller in the result and never stored here: a store is long-lived
 * and shared, and a token that outlived the dialog showing it would defeat
 * "shown once".
 */
export const useUploadersStore = defineStore('uploaders', () => {
  const uploaders = ref<ManagedUploader[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const loaded = ref(false)
  /** Counts list requests, so an answer that a later request has overtaken is dropped. */
  let loadSeq = 0

  /**
   * Fetches the list. The rows already shown stay while it runs and when it
   * fails: a refresh that hiccups should not empty a page someone is working on.
   */
  async function load(): Promise<void> {
    const seq = ++loadSeq
    isLoading.value = true
    error.value = null
    try {
      const list = await apiGet<ManagedUploader[]>(BASE)
      if (seq !== loadSeq) return
      uploaders.value = list
      loaded.value = true
    } catch (e) {
      if (seq !== loadSeq) return
      error.value = readFailure(e, 'uploaders.error.loadFailed').message
    } finally {
      if (seq === loadSeq) isLoading.value = false
    }
  }

  /** The uploaders of one location, in the list's order. */
  function forLocation(locationId: number): ManagedUploader[] {
    return uploaders.value.filter((u) => u.locationId === locationId)
  }

  /** Issues an uploader with its first token for a location. */
  async function create(locationId: number, displayName: string): Promise<IssuedMutation> {
    try {
      const issued = await apiPost<IssuedUploader>(
        `${LOCATIONS}/${segment(locationId)}/uploaders`,
        { displayName },
      )
      upsert(issued.uploader)
      return { ok: true, uploader: issued.uploader, token: issued.token }
    } catch (e) {
      return readFailure(e, 'uploaders.error.saveFailed')
    }
  }

  async function rename(id: number, displayName: string): Promise<UploaderMutation> {
    return changeUploader(() => apiPut<ManagedUploader>(`${BASE}/${segment(id)}`, { displayName }))
  }

  /**
   * Issues a new token: the old one stops working at once, and a revoked
   * uploader is active again.
   */
  async function replaceToken(id: number): Promise<IssuedMutation> {
    try {
      const issued = await apiPost<IssuedUploader>(`${BASE}/${segment(id)}/replace`, {})
      upsert(issued.uploader)
      return { ok: true, uploader: issued.uploader, token: issued.token }
    } catch (e) {
      return readFailure(e, 'uploaders.error.saveFailed')
    }
  }

  /** Refuses the token from now on. The server treats a second revoke as a no-op. */
  async function revoke(id: number): Promise<UploaderMutation> {
    return changeUploader(() => apiPost<ManagedUploader>(`${BASE}/${segment(id)}/revoke`, {}))
  }

  async function changeUploader(call: () => Promise<ManagedUploader>): Promise<UploaderMutation> {
    try {
      const changed = await call()
      upsert(changed)
      return { ok: true, uploader: changed }
    } catch (e) {
      return readFailure(e, 'uploaders.error.saveFailed')
    }
  }

  /** Replaces the row of an uploader the list holds, or adds it; the list stays in the server's order. */
  function upsert(changed: ManagedUploader): void {
    const rest = uploaders.value.filter((u) => u.id !== changed.id)
    uploaders.value = sortedForList([...rest, changed])
  }

  function reset(): void {
    loadSeq++
    uploaders.value = []
    isLoading.value = false
    error.value = null
    loaded.value = false
  }

  return {
    uploaders,
    isLoading,
    error,
    loaded,
    load,
    forLocation,
    create,
    rename,
    replaceToken,
    revoke,
    reset,
  }
})

/** A path segment, kept safe the way the other administration stores do. */
function segment(part: number | string): string {
  return encodeURIComponent(String(part))
}

/**
 * The server lists by location name, then display name. The store keeps
 * that order when a name changes or a row is added.
 */
function sortedForList(list: ManagedUploader[]): ManagedUploader[] {
  // A row without a name sorts first rather than throwing: this runs right after a
  // token was issued, and nothing about keeping the list in order may cost the person
  // the one chance to see that token.
  const byName = (a: string | null | undefined, b: string | null | undefined) =>
    (a ?? '').localeCompare(b ?? '', 'de', { sensitivity: 'base' })
  return [...list].sort((a, b) => byName(a.locationName, b.locationName) || byName(a.displayName, b.displayName))
}

/** What a failed call tells the person; see {@link readMutationFailure}. */
function readFailure(e: unknown, fallbackKey: string): MutationFailure {
  return readMutationFailure(e, {
    fallback: fallbackKey,
    network: 'uploaders.error.network',
    unknown: 'uploaders.error.unknown',
  })
}
