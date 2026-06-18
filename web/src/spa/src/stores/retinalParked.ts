/**
 * 2026-06-19 — cross-study parked-jobs admin store.
 *
 * Backs {@code RetinalParkedAdminView.vue}. The store owns:
 *   - the full list of {@code status='parked'} retinal jobs the
 *     sysadmin can see (cross-study by definition)
 *   - the bind action that flips a parked row into {@code queued}
 *     (or {@code remote_pending} when the GPU sidecar is configured)
 *
 * <p>Errors:
 *   - 401 / 403 → re-thrown so the router guard / global error toast
 *     can react (loss-of-session, role downgraded mid-page, etc.)
 *   - 404 / 409 → surfaced as a structured {@link ApiError} so the
 *     view can show inline "no longer parked" / "subject vanished"
 *     copy without losing the HTTP signal
 *   - network failures → {@link ApiNetworkError} surfaced verbatim
 *
 * <p>The bind action removes the row from the local list optimistically
 * on success; on 409 (raced by another operator) it just re-loads the
 * list so the view stays consistent.
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

import { ApiError, ApiNetworkError } from '@/api/client'
import {
  bindParkedJob,
  listParkedJobs,
  type ParkedJobAdminRow,
} from '@/api/retinal'

export const useRetinalParkedStore = defineStore('retinalParked', () => {
  const list = ref<ParkedJobAdminRow[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  /** Per-row in-flight flag — used by the view to disable a row's
   *  Zuordnen button while the PATCH is in flight. */
  const bindingJobId = ref<number | null>(null)

  async function load(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      list.value = await listParkedJobs()
    } catch (e) {
      list.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      error.value = humanError(e, 'load')
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Bind one parked job to an event_crf. The view supplies the
   * {@code eventCrfId} after the operator has walked PatientSearchModal
   * → VisitPickerModal.
   *
   * <p>On success: remove the bound row from the local list
   * optimistically. On 409 (another operator bound it first): re-load
   * the list so the SPA stays consistent without surfacing a hard
   * error.
   *
   * @returns {@code true} on success, {@code false} on a recoverable
   *          409 (caller may show a soft "wurde von jemand anderem
   *          zugeordnet" toast).
   */
  async function bind(jobId: number, eventCrfId: number): Promise<boolean> {
    bindingJobId.value = jobId
    error.value = null
    try {
      await bindParkedJob(jobId, { eventCrfId })
      list.value = list.value.filter((r) => r.jobId !== jobId)
      return true
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      if (e instanceof ApiError && e.status === 409) {
        await load()
        return false
      }
      error.value = humanError(e, 'bind')
      throw e
    } finally {
      bindingJobId.value = null
    }
  }

  function humanError(e: unknown, op: 'load' | 'bind'): string {
    if (e instanceof ApiError) {
      const body = e.body as { message?: string } | null
      if (body?.message) return body.message
    }
    if (e instanceof ApiNetworkError && e.message) return e.message
    if (e instanceof Error && e.message) return e.message
    return op === 'load'
      ? 'Geparkte Scans konnten nicht geladen werden.'
      : 'Zuordnung fehlgeschlagen.'
  }

  return { list, isLoading, error, bindingJobId, load, bind }
})
