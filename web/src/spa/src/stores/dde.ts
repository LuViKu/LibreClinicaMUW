import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  DdeCommitResponse,
  DdeConflicts,
  DdeConflictItem,
  DdePassResponse,
  DdeReconcileRequest,
  DdeResolveResponse,
} from '@/types/dde'

/**
 * Phase E.6 dde — blind double-data-entry store.
 *
 * Three flows:
 *   1. {@link loadPass} — call before rendering CRF Entry. Returns
 *      pass=1|2|reconcile|done so the view can choose its variant
 *      (or redirect to DdeReconcileView when pass=reconcile).
 *   2. {@link commitPass2} — delegate target for {@code crfEntry.save()}
 *      when the entry's {@code dde.pass === '2'}. Diffs server-side
 *      against pass-1 and either marks complete or pins for reconcile.
 *   3. {@link loadConflicts} + {@link resolve} — DM-side reconcile
 *      workflow. {@code resolve} walks one item at a time; when the
 *      backend's {@code nextItem} is empty the workflow is complete
 *      and the EventCRF has flipped to {@code dde-complete}.
 *
 * Error model matches the rest of the SPA: 401/403 propagate so the
 * router-level auth guard can react; everything else lands in
 * {@code error.value} with a German-first user-facing string.
 */
export const useDdeStore = defineStore('dde', () => {
  const pass = ref<DdePassResponse | null>(null)
  const conflicts = ref<DdeConflicts | null>(null)
  const isLoading = ref(false)
  const isCommitting = ref(false)
  const isResolving = ref(false)
  const error = ref<string | null>(null)

  /** Convenience selector — true while at least one conflict is unresolved. */
  const hasOpenConflicts = computed<boolean>(() =>
    (conflicts.value?.items ?? []).some((i: DdeConflictItem) => !i.resolved),
  )

  /** Load the pass marker for an EventCRF. */
  async function loadPass(eventCrfOid: string): Promise<DdePassResponse | null> {
    isLoading.value = true
    error.value = null
    pass.value = null
    try {
      pass.value = await apiGet<DdePassResponse>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(eventCrfOid)}/dde-pass`,
      )
      return pass.value
    } catch (e) {
      handle(e, 'Laden der DDE-Pass-Information fehlgeschlagen')
      return null
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Commit the blind pass-2 values for an EventCRF. Returns the
   * backend response so the caller (typically {@code crfEntry.save})
   * can refresh its local state. {@code mismatchCount === 0} flips
   * the EventCRF to {@code dde-complete}; >0 spawns FAILEDVAL notes
   * and pins the EventCRF in {@code dde-conflicts}.
   */
  async function commitPass2(
    eventCrfOid: string,
    values: Record<string, unknown>,
  ): Promise<DdeCommitResponse | null> {
    isCommitting.value = true
    error.value = null
    try {
      const resp = await apiPost<DdeCommitResponse>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(eventCrfOid)}/dde-commit`,
        { values },
      )
      return resp
    } catch (e) {
      handle(e, 'DDE-Pass 2 konnte nicht festgeschrieben werden')
      return null
    } finally {
      isCommitting.value = false
    }
  }

  /** Load the side-by-side conflict list for the reconcile view. */
  async function loadConflicts(eventCrfOid: string): Promise<DdeConflicts | null> {
    isLoading.value = true
    error.value = null
    conflicts.value = null
    try {
      conflicts.value = await apiGet<DdeConflicts>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(eventCrfOid)}/dde-conflicts`,
      )
      return conflicts.value
    } catch (e) {
      handle(e, 'DDE-Konflikte konnten nicht geladen werden')
      return null
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Apply a DM's resolution to one conflict item. The backend returns
   * an empty {@code nextItem} when reconciliation finished — caller
   * should redirect to the SubjectMatrix in that case.
   */
  async function resolve(
    eventCrfOid: string,
    itemOid: string,
    body: DdeReconcileRequest,
  ): Promise<DdeResolveResponse | null> {
    isResolving.value = true
    error.value = null
    try {
      const resp = await apiPost<DdeResolveResponse>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(eventCrfOid)}` +
          `/dde-conflicts/${encodeURIComponent(itemOid)}/resolve`,
        body,
      )
      // Optimistic local update — flip the matching conflict row to
      // resolved so the table re-renders without an extra GET.
      if (conflicts.value) {
        const row = conflicts.value.items.find((i) => i.itemOid === itemOid)
        if (row) {
          row.resolved = true
          row.winner = body.winner
        }
      }
      return resp
    } catch (e) {
      handle(e, 'DDE-Konflikt konnte nicht aufgelöst werden')
      return null
    } finally {
      isResolving.value = false
    }
  }

  function handle(e: unknown, fallback: string): void {
    if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
      throw e
    }
    if (e instanceof ApiNetworkError) {
      error.value = 'Backend nicht erreichbar — ' + fallback + '.'
      return
    }
    if (e instanceof ApiError) {
      const body = e.body as { message?: string } | null
      error.value = body?.message ?? `${fallback} (HTTP ${e.status}).`
      return
    }
    error.value = e instanceof Error ? e.message : fallback + '.'
  }

  return {
    pass,
    conflicts,
    isLoading,
    isCommitting,
    isResolving,
    error,
    hasOpenConflicts,
    loadPass,
    commitPass2,
    loadConflicts,
    resolve,
  }
})
