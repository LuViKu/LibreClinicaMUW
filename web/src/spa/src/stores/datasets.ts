import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  ArchivedFileDto,
  DatasetDto,
  ExportFormat,
  ExportTriggerResponse,
} from '@/types/export'

/**
 * Phase E.6 — Data Export MVP store.
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
 * per-dataset file cache. Without this the operator would still see
 * study-A datasets after binding study B.
 */
export const useDatasetsStore = defineStore('datasets', () => {
  const rows = ref<DatasetDto[]>([])
  /** dataset.id → ArchivedFileDto[] (lazy-loaded). */
  const filesByDataset = ref<Map<number, ArchivedFileDto[]>>(new Map())
  const isLoading = ref(false)
  const isLoadingFiles = ref<Set<number>>(new Set())
  const isExporting = ref<Set<number>>(new Set())
  const isQuickOdm = ref(false)
  const error = ref<string | null>(null)

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
   * Clear every piece of per-study state. Called by
   * {@link useAuthStore.pickStudy} before re-bootstrapping so the
   * operator never sees study-A datasets after switching to study B.
   */
  function reset(): void {
    rows.value = []
    filesByDataset.value = new Map()
    isLoading.value = false
    isLoadingFiles.value = new Set()
    isExporting.value = new Set()
    isQuickOdm.value = false
    error.value = null
  }

  return {
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
    reset,
  }
})
