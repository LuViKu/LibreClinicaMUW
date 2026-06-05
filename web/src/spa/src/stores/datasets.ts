import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  ArchivedFileDto,
  CreateDatasetRequest,
  DatasetDto,
  DatasetFilterDto,
  ExportFormat,
  ExportTriggerResponse,
  FilterTestResult,
} from '@/types/export'

/**
 * Phase E.6 — Data Export store.
 *
 * <h2>Phase 1 — saved-dataset list + export trigger</h2>
 *
 * Backs {@code DatasetListView}. Hydrates from:
 *   - {@code GET /pages/api/v1/studies/{oid}/datasets}                                — saved datasets
 *   - {@code GET /pages/api/v1/studies/{oid}/datasets/{id}/files}                     — per-dataset files (lazy)
 *   - {@code POST /pages/api/v1/datasets/{id}/export}                                 — trigger run
 *   - {@code POST /pages/api/v1/studies/{oid}/datasets:quick-odm}                     — one-click ODM
 *
 * <p>Mock policy: same as the rest of Phase E.6 — no mock fallback.
 * Backend unreachable → {@code error} is set + the view renders the
 * empty / failure state.
 *
 * <p>Per-study reset: wired into {@code useAuthStore.pickStudy} so
 * switching the active study clears every dataset row + the
 * per-dataset file cache + every Phase-3 wizard draft. Without this
 * the operator would still see study-A datasets after binding study B.
 *
 * <h2>Phase 3 — filters (create-dataset wizard)</h2>
 *
 * Holds the create-dataset wizard's draft + drives the live filter
 * preview against {@code POST /pages/api/v1/datasets/{id}:test-filter}.
 * The {@link testFilter} call debounces against rapid filter edits —
 * the wizard's inline preview pane re-runs the count probe 300 ms after
 * the last keystroke. Concurrent edits cancel the previous in-flight
 * request via an {@link AbortController}.
 */
export const useDatasetsStore = defineStore('datasets', () => {
  /* ---- Phase 1 state ---- */

  const rows = ref<DatasetDto[]>([])
  /** dataset.id → ArchivedFileDto[] (lazy-loaded). */
  const filesByDataset = ref<Map<number, ArchivedFileDto[]>>(new Map())
  const isLoading = ref(false)
  const isLoadingFiles = ref<Set<number>>(new Set())
  const isExporting = ref<Set<number>>(new Set())
  const isQuickOdm = ref(false)
  const error = ref<string | null>(null)

  /* ---- Phase 3 wizard state ---- */

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
  let pendingResolvers: Array<(value: FilterTestResult | null) => void> = []

  /**
   * Load the saved-dataset list for the active study. Replaces the
   * current rows on success; leaves them in place on failure (so a
   * transient backend hiccup doesn't blank the table).
   */
  async function load(studyOid: string): Promise<void> {
    if (!studyOid) return
    isLoading.value = true
    error.value = null
    try {
      rows.value = await apiGet<DatasetDto[]>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/datasets`,
      )
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Datensatzliste konnte nicht geladen werden.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Fehler beim Laden der Datensätze (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden der Datensätze.'
      }
    } finally {
      isLoading.value = false
    }
  }

  /** Lazy-load the per-dataset file list. */
  async function loadFiles(studyOid: string, datasetId: number): Promise<void> {
    if (!studyOid || !datasetId) return
    const pending = new Set(isLoadingFiles.value)
    pending.add(datasetId)
    isLoadingFiles.value = pending
    try {
      const files = await apiGet<ArchivedFileDto[]>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/datasets/${datasetId}/files`,
      )
      const next = new Map(filesByDataset.value)
      next.set(datasetId, files)
      filesByDataset.value = next
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      // Cache an empty list so the row collapses to an "empty" state
      // rather than re-fetching on every expand-toggle.
      const next = new Map(filesByDataset.value)
      next.set(datasetId, [])
      filesByDataset.value = next
      if (e instanceof ApiNetworkError) {
        error.value = 'Backend nicht erreichbar — Datei-Liste konnte nicht geladen werden.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Fehler beim Laden der Dateien (HTTP ${e.status}).`
      }
    } finally {
      const next = new Set(isLoadingFiles.value)
      next.delete(datasetId)
      isLoadingFiles.value = next
    }
  }

  /**
   * Trigger an export run. On success the per-dataset file cache is
   * invalidated (next expand re-fetches) and the dataset's
   * `lastRunAt` + `fileCount` are bumped locally so the table
   * reflects the new state without a full re-load.
   */
  async function triggerExport(
    studyOid: string,
    datasetId: number,
    format: ExportFormat,
  ): Promise<ExportTriggerResponse | null> {
    if (!datasetId) return null
    const pending = new Set(isExporting.value)
    pending.add(datasetId)
    isExporting.value = pending
    error.value = null
    try {
      const response = await apiPost<ExportTriggerResponse>(
        `/pages/api/v1/datasets/${datasetId}/export`,
        { format },
      )
      // Bump the row in-place so the operator sees the new run + file
      // count without a full /datasets re-fetch.
      const row = rows.value.find((r) => r.id === datasetId)
      if (row) {
        row.lastRunAt = new Date().toISOString()
        row.fileCount = (row.fileCount ?? 0) + 1
      }
      // Invalidate the per-dataset files cache so the next expand
      // surfaces the newly-generated file.
      if (filesByDataset.value.has(datasetId)) {
        const next = new Map(filesByDataset.value)
        next.delete(datasetId)
        filesByDataset.value = next
        // Re-load eagerly so the SPA's expand-row sub-table shows the
        // new file the moment the modal closes.
        void loadFiles(studyOid, datasetId)
      }
      return response
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Export nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value = 'Backend nicht erreichbar — Export fehlgeschlagen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Export fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Export.'
      }
      return null
    } finally {
      const next = new Set(isExporting.value)
      next.delete(datasetId)
      isExporting.value = next
    }
  }

  /**
   * Trigger the one-click full-study ODM export. Reloads the dataset
   * list on success so the new ad-hoc Quick_ODM_… entry surfaces in
   * the table.
   */
  async function quickOdm(studyOid: string): Promise<ExportTriggerResponse | null> {
    if (!studyOid) return null
    isQuickOdm.value = true
    error.value = null
    try {
      const response = await apiPost<ExportTriggerResponse>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/datasets:quick-odm`,
        {},
      )
      // The endpoint creates a new dataset behind the scenes; reload
      // the list so the new row surfaces.
      void load(studyOid)
      return response
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Quick ODM nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value = 'Backend nicht erreichbar — Quick ODM fehlgeschlagen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Quick ODM fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Quick ODM.'
      }
      return null
    } finally {
      isQuickOdm.value = false
    }
  }

  /**
   * Debounced filter-preview probe.
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

  /**
   * Clear every piece of per-study state. Called by
   * {@link useAuthStore.pickStudy} before re-bootstrapping so the
   * operator never sees study-A datasets after switching to study B.
   * Also called when the create-dataset wizard closes so the next
   * launch starts from a clean draft.
   */
  function reset(): void {
    rows.value = []
    filesByDataset.value = new Map()
    isLoading.value = false
    isLoadingFiles.value = new Set()
    isExporting.value = new Set()
    isQuickOdm.value = false
    error.value = null

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

  return {
    /* Phase 1 */
    rows,
    filesByDataset,
    isLoading,
    isLoadingFiles,
    isExporting,
    isQuickOdm,
    error,
    load,
    loadFiles,
    triggerExport,
    quickOdm,
    /* Phase 3 */
    draft,
    preview,
    isLoadingPreview,
    previewError,
    testFilter,
    /* Both */
    reset,
  }
})
