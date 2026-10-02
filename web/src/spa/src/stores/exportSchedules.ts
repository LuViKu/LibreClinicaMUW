import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiDelete, apiGet, apiPatch, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  CreateScheduleRequest,
  ExportScheduleDto,
  UpdateScheduleRequest,
} from '@/types/export'

/**
 * R1-export — the recurring exports of a dataset ({@code export_schedule}).
 *
 * Backs the schedules panel under a row of {@code DatasetListView}:
 *   - {@code GET    /pages/api/v1/datasets/{id}/schedules} — the dataset's
 *     schedules, paused ones included
 *   - {@code POST   /pages/api/v1/datasets/{id}/schedules} — add one
 *   - {@code PATCH  /pages/api/v1/schedules/{id}} — edit, pause, resume
 *   - {@code DELETE /pages/api/v1/schedules/{id}} — delete (soft)
 *
 * The server reschedules before it answers and returns the schedule as it
 * now stands, so the list is updated in place from the answer rather than
 * fetched again.
 */
export const useExportSchedulesStore = defineStore('exportSchedules', () => {
  /** dataset id → its schedules. */
  const byDataset = ref<Map<number, ExportScheduleDto[]>>(new Map())
  const loading = ref<Set<number>>(new Set())
  const error = ref<string | null>(null)

  function setList(datasetId: number, list: ExportScheduleDto[]): void {
    const next = new Map(byDataset.value)
    next.set(datasetId, list)
    byDataset.value = next
  }

  function replace(saved: ExportScheduleDto): void {
    const list = byDataset.value.get(saved.datasetId) ?? []
    setList(saved.datasetId, list.map((s) => (s.id === saved.id ? saved : s)))
  }

  async function load(datasetId: number): Promise<void> {
    if (!datasetId) return
    const pending = new Set(loading.value)
    pending.add(datasetId)
    loading.value = pending
    error.value = null
    try {
      setList(
        datasetId,
        await apiGet<ExportScheduleDto[]>(`/pages/api/v1/datasets/${datasetId}/schedules`),
      )
    } catch (e) {
      error.value = explain(e, 'Zeitpläne konnten nicht geladen werden')
    } finally {
      const next = new Set(loading.value)
      next.delete(datasetId)
      loading.value = next
    }
  }

  async function create(
    datasetId: number,
    body: CreateScheduleRequest,
  ): Promise<ExportScheduleDto | null> {
    error.value = null
    try {
      const created = await apiPost<ExportScheduleDto>(
        `/pages/api/v1/datasets/${datasetId}/schedules`,
        body,
      )
      setList(datasetId, [created, ...(byDataset.value.get(datasetId) ?? [])])
      return created
    } catch (e) {
      error.value = explain(e, 'Zeitplan konnte nicht angelegt werden')
      return null
    }
  }

  /** A field left out of {@code patch} keeps its value on the server. */
  async function update(
    schedule: ExportScheduleDto,
    patch: UpdateScheduleRequest,
  ): Promise<ExportScheduleDto | null> {
    error.value = null
    try {
      const saved = await apiPatch<ExportScheduleDto>(
        `/pages/api/v1/schedules/${schedule.id}`,
        patch,
      )
      replace(saved)
      return saved
    } catch (e) {
      error.value = explain(e, 'Zeitplan konnte nicht gespeichert werden')
      return null
    }
  }

  async function remove(schedule: ExportScheduleDto): Promise<boolean> {
    error.value = null
    try {
      await apiDelete<void>(`/pages/api/v1/schedules/${schedule.id}`)
      setList(
        schedule.datasetId,
        (byDataset.value.get(schedule.datasetId) ?? []).filter((s) => s.id !== schedule.id),
      )
      return true
    } catch (e) {
      error.value = explain(e, 'Zeitplan konnte nicht gelöscht werden')
      return false
    }
  }

  function reset(): void {
    byDataset.value = new Map()
    loading.value = new Set()
    error.value = null
  }

  return { byDataset, loading, error, load, create, update, remove, reset }
})

function explain(e: unknown, fallback: string): string {
  if (e instanceof ApiNetworkError) {
    return 'Backend nicht erreichbar — bitte erneut versuchen.'
  }
  if (e instanceof ApiError) {
    const body = e.body as { message?: string } | null
    return body?.message ?? `${fallback} (HTTP ${e.status}).`
  }
  if (e instanceof Error) return e.message
  return fallback
}
