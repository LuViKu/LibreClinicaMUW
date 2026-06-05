import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  CreateDatasetRequest,
  DatasetFilterDto,
  FilterTestResult,
} from '@/types/export'

/**
 * Phase E.6 Data Export — Phase 3 (filters) Pinia store.
 *
 * Holds the create-dataset wizard's draft + drives the live filter
 * preview against {@code POST /pages/api/v1/datasets/{id}:test-filter}.
 *
 * <h2>Draft model</h2>
 *
 * The wizard authors a {@link CreateDatasetRequest} progressively;
 * Phase 3 owns the {@code filters} segment (Phase 2 lands the other
 * fields). The draft is reactive — components bind to {@code draft}
 * via {@code v-model:filters} on the FilterBuilder.
 *
 * <h2>Preview behaviour</h2>
 *
 * {@link testFilter} debounces against rapid filter edits — the
 * wizard's inline preview pane re-runs the count probe 300 ms after
 * the last keystroke. Concurrent edits cancel the previous in-flight
 * request via an {@link AbortController} so the displayed counts
 * always reflect the latest filter list (no out-of-order overwrite).
 *
 * <h2>Persistence (Phase 2)</h2>
 *
 * The {@code POST /pages/api/v1/datasets} call (create) and
 * {@code PUT /pages/api/v1/datasets/{id}} (update) land with Phase 2's
 * wizard PR; Phase 3 ships the read-side only.
 */
export const useDatasetsStore = defineStore('datasets', () => {
  const draft = ref<CreateDatasetRequest>({
    name: '',
    description: '',
    selectedItemOids: [],
    filters: [],
  })

  const preview = ref<FilterTestResult | null>(null)
  const isLoadingPreview = ref(false)
  const previewError = ref<string | null>(null)

  // Debounce + cancellation state for `testFilter` — the wizard calls
  // it on every filter-row mutation so we collapse rapid edits into a
  // single backend probe.
  let debounceHandle: ReturnType<typeof setTimeout> | null = null
  let inFlightController: AbortController | null = null
  // Resolvers for promises that were superseded by a later call —
  // we honour each caller's promise by resolving with `null` instead
  // of letting them hang forever.
  let pendingResolvers: Array<(value: FilterTestResult | null) => void> = []

  /**
   * Cancel any pending debounce + in-flight request. Called when the
   * wizard closes or when the operator switches study/context.
   */
  function reset(): void {
    if (debounceHandle !== null) {
      clearTimeout(debounceHandle)
      debounceHandle = null
    }
    if (inFlightController !== null) {
      inFlightController.abort()
      inFlightController = null
    }
    for (const r of pendingResolvers) r(null)
    pendingResolvers = []
    draft.value = {
      name: '',
      description: '',
      selectedItemOids: [],
      filters: [],
    }
    preview.value = null
    isLoadingPreview.value = false
    previewError.value = null
  }

  /**
   * Debounced preview probe.
   *
   * @param datasetId     dataset_id to scope against. Pass {@code '0'}
   *                       for the new-dataset case (the backend treats
   *                       it as the active-study scope).
   * @param filters       The current filter list. Passed through
   *                       verbatim to the backend.
   * @param debounceMs    Override the default 300 ms debounce. Tests
   *                       pass 0 to skip the timer.
   * @returns A promise that resolves to the latest {@link FilterTestResult},
   *          or {@code null} if the call was cancelled / aborted.
   */
  function testFilter(
    datasetId: string,
    filters: DatasetFilterDto[],
    debounceMs = 300,
  ): Promise<FilterTestResult | null> {
    if (debounceHandle !== null) {
      clearTimeout(debounceHandle)
      debounceHandle = null
    }
    if (inFlightController !== null) {
      inFlightController.abort()
      inFlightController = null
    }
    // Resolve every prior caller's promise with null so they unblock —
    // they were superseded by this newer call.
    for (const r of pendingResolvers) r(null)
    pendingResolvers = []

    return new Promise<FilterTestResult | null>((resolve) => {
      pendingResolvers.push(resolve)
      const runProbe = async () => {
        debounceHandle = null
        const myResolvers = pendingResolvers
        pendingResolvers = []
        const controller = new AbortController()
        inFlightController = controller
        isLoadingPreview.value = true
        previewError.value = null
        try {
          const result = await apiPost<FilterTestResult>(
            `/pages/api/v1/datasets/${encodeURIComponent(datasetId)}:test-filter`,
            { filters },
            { signal: controller.signal },
          )
          if (controller.signal.aborted) {
            for (const r of myResolvers) r(null)
            return
          }
          preview.value = result
          for (const r of myResolvers) r(result)
        } catch (err) {
          if (controller.signal.aborted) {
            for (const r of myResolvers) r(null)
            return
          }
          if (err instanceof ApiError) {
            previewError.value = err.message
          } else if (err instanceof ApiNetworkError) {
            previewError.value = 'Backend nicht erreichbar'
          } else {
            previewError.value = (err as Error).message ?? 'Unbekannter Fehler'
          }
          for (const r of myResolvers) r(null)
        } finally {
          isLoadingPreview.value = false
          if (inFlightController === controller) {
            inFlightController = null
          }
        }
      }
      if (debounceMs <= 0) {
        void runProbe()
      } else {
        debounceHandle = setTimeout(() => { void runProbe() }, debounceMs)
      }
    })
  }

  return {
    draft,
    preview,
    isLoadingPreview,
    previewError,
    testFilter,
    reset,
  }
})
