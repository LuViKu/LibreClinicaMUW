import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet } from '@/api/client'
import { readMutationFailure } from '@/lib/mutationFailure'
import type { LocationOption } from '@/types/location'

const PATH = '/pages/api/v1/locations'

/**
 * The locations (Standorte) the caller may place a study or a site at
 * (DR-040 phase 4), for the Standort selects of the site, create-study and
 * edit-study forms.
 *
 * `GET /pages/api/v1/locations` answers the available locations in the
 * caller's scope (every available one for a system administrator), ordered
 * by name, and never 403: someone with no scope gets an empty list. A page
 * calls {@link load} once when it opens; a second call while the first is
 * on its way shares it. Not study-scoped, so the study switch need not
 * reset it; a page that opens later asks again.
 */
export const useLocationOptionsStore = defineStore('locationOptions', () => {
  const options = ref<LocationOption[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  /** True once an answer arrived, so an empty list means "none in your scope". */
  const loaded = ref(false)
  let inFlight: Promise<void> | null = null

  function load(): Promise<void> {
    if (inFlight) return inFlight
    isLoading.value = true
    error.value = null
    inFlight = (async () => {
      try {
        options.value = await apiGet<LocationOption[]>(PATH)
        loaded.value = true
      } catch (e) {
        options.value = []
        loaded.value = false
        error.value = readMutationFailure(e, {
          fallback: 'locationSelect.loadFailed',
          network: 'locationSelect.network',
          unknown: 'locationSelect.unknown',
        }).message
      } finally {
        isLoading.value = false
        inFlight = null
      }
    })()
    return inFlight
  }

  function reset() {
    options.value = []
    isLoading.value = false
    error.value = null
    loaded.value = false
  }

  return { options, isLoading, error, loaded, load, reset }
})
