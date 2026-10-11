import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPut } from '@/api/client'
import { readMutationFailure, type MutationFailure } from '@/lib/mutationFailure'
import type { AnonymousUploaders, AssignSourceLocationInput, IngestSource } from '@/types/ingestSource'

const BASE = '/pages/api/v1/admin/ingest-sources'

export type { MutationFailure }
export type IngestSourceMutation = { ok: true; source: IngestSource } | MutationFailure
export type AnonymousUploadersResult = { ok: true; anonymous: AnonymousUploaders } | MutationFailure

/**
 * Where device files come from, for a system administrator.
 *
 * Every source of every kind (uploader, DICOM sender, the Remidio account,
 * the upload page) with the location it is assigned to, or none. A file of
 * a source without a location is visible to administrators only; assigning
 * the location moves the files that wait for it into that location's inbox
 * in the same transaction, which is why the answer carries the source's new
 * state and the page can show it without a reload.
 */
export const useAdminIngestSourcesStore = defineStore('adminIngestSources', () => {
  const sources = ref<IngestSource[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const loaded = ref(false)

  /** Sources that belong to no location yet: what the set-up still owes. */
  const unassignedCount = computed(() => sources.value.filter((s) => s.locationId === null).length)

  /**
   * Files that wait at a source without a location, which only
   * administrators can see until the source is assigned. Waiting files of an
   * assigned source are the inbox's everyday work and are not counted.
   */
  const waitingCount = computed(() =>
    sources.value.filter((s) => s.locationId === null).reduce((sum, s) => sum + s.pendingItemCount, 0),
  )

  async function load(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      sources.value = await apiGet<IngestSource[]>(BASE)
      loaded.value = true
    } catch (e) {
      sources.value = []
      error.value = readFailure(e, 'adminLocations.sources.error.loadFailed').message
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Assigns a source to a location, or takes the location away with `null`.
   * The answer replaces the row, so the waiting-file count is the server's:
   * assigning moves the files that waited, and the count goes with them.
   */
  async function assignLocation(id: number, locationId: number | null): Promise<IngestSourceMutation> {
    const body: AssignSourceLocationInput = { locationId }
    try {
      const changed = await apiPut<IngestSource>(`${BASE}/${encodeURIComponent(String(id))}/location`, body)
      sources.value = sources.value.map((s) => (s.id === changed.id ? changed : s))
      return { ok: true, source: changed }
    } catch (e) {
      return readFailure(e, 'adminLocations.error.saveFailed')
    }
  }

  /**
   * The uploaders that have never used a token, for the warning before a
   * second location is created. A read of its own, kept out of the list's
   * state: it is asked when that dialog opens and answers one question.
   */
  async function fetchAnonymousUploaders(): Promise<AnonymousUploadersResult> {
    try {
      const anonymous = await apiGet<AnonymousUploaders>(`${BASE}/anonymous-uploaders`)
      return {
        ok: true,
        anonymous: {
          count: typeof anonymous?.count === 'number' ? anonymous.count : 0,
          instances: Array.isArray(anonymous?.instances) ? anonymous.instances : [],
        },
      }
    } catch (e) {
      return readFailure(e, 'adminLocations.sources.error.anonymousFailed')
    }
  }

  function reset(): void {
    sources.value = []
    isLoading.value = false
    error.value = null
    loaded.value = false
  }

  return {
    sources,
    isLoading,
    error,
    loaded,
    unassignedCount,
    waitingCount,
    load,
    assignLocation,
    fetchAnonymousUploaders,
    reset,
  }
})

/** What a failed call tells the person; see {@link readMutationFailure}. */
function readFailure(e: unknown, fallbackKey: string): MutationFailure {
  return readMutationFailure(e, {
    fallback: fallbackKey,
    network: 'adminLocations.error.network',
    unknown: 'adminLocations.error.unknown',
  })
}
