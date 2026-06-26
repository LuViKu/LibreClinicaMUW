/**
 * Phase E.7 Wave 4 — Retinal-job Pinia store.
 *
 * Caches three things keyed independently so the viewer can navigate
 * between jobs (or between OD/OS jobs that share an underlying volume)
 * without redundantly refetching:
 *
 *   - {@link jobs} keyed by `jobId` — the fat DTO. Refetched on any
 *     status that isn't `succeeded` / `failed` because metrics will
 *     change as the sidecar progresses; the view's polling layer
 *     drives that, the store just owns the latest snapshot.
 *   - {@link geometries} keyed by `e2eUuid` — the static geometry JSON
 *     for the underlying volume. A single volume can spawn multiple
 *     jobs (fluid + GA + ONL on the same scan), and the geometry
 *     payload is ~100 KB, so we cache by volume rather than by job.
 *   - {@link loading} keyed by `jobId` — request-in-flight flag the
 *     view uses to short-circuit duplicate clicks on the loading
 *     spinner.
 *
 * <p>Errors propagate to the view via the standard `ApiError` /
 * `ApiNetworkError` pattern from {@code api/client.ts}; we keep the
 * thrown error around per-job so the empty-state banner can surface
 * it without forcing every consumer to track the last rejection.
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  fetchGeometry,
  getJob,
  getJobBySubjectSeq,
  listEventCrfJobs,
  listSubjectJobs,
  retryRetinalJob,
  rerunRetinalJobAs,
  type GeometryJson,
  type RetinalJobDetail,
  type RetinalJobSummary,
} from '@/api/retinal'

export const useRetinalJobStore = defineStore('retinalJob', () => {
  const jobs = ref<Record<number, RetinalJobDetail>>({})
  const geometries = ref<Record<string, GeometryJson>>({})
  const loading = ref<Record<number, boolean>>({})
  const errors = ref<Record<number, string | null>>({})

  /**
   * Per-event-CRF + per-subject summary caches. The viewer's empty
   * state lists jobs by event-CRF / by subject; we cache the lists by
   * the parent id so navigating between adjacent CRFs in the same
   * subject doesn't refire the GET every time.
   */
  const eventCrfJobs = ref<Record<number, RetinalJobSummary[]>>({})
  const subjectJobs = ref<Record<number, RetinalJobSummary[]>>({})
  const eventCrfLoading = ref<Record<number, boolean>>({})
  const subjectLoading = ref<Record<number, boolean>>({})

  /**
   * Fetch + cache a single job detail. Replaces any cached snapshot
   * for the same {@code jobId} so callers see the latest sidecar
   * state on the next render. Re-throws auth errors so the router
   * guard can redirect.
   */
  async function loadJob(jobId: number, force = false): Promise<RetinalJobDetail | null> {
    if (!force && jobs.value[jobId] != null) return jobs.value[jobId]
    loading.value = { ...loading.value, [jobId]: true }
    errors.value = { ...errors.value, [jobId]: null }
    try {
      const detail = await getJob(jobId)
      jobs.value = { ...jobs.value, [jobId]: detail }
      return detail
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to load retinal job'
      errors.value = { ...errors.value, [jobId]: message }
      throw e
    } finally {
      const next = { ...loading.value }
      delete next[jobId]
      loading.value = next
    }
  }

  /**
   * 2026-06-26 — resolve a per-subject sequence number to the job detail
   * (deep-link path /subjects/{label}/jobs/{n}) and cache it by its
   * resolved jobId so the rest of the view works exactly as the by-id
   * path. Re-throws on error so the view can surface a not-found state.
   */
  async function loadJobBySubjectSeq(
    subjectLabel: string,
    seq: number,
  ): Promise<RetinalJobDetail | null> {
    const detail = await getJobBySubjectSeq(subjectLabel, seq)
    jobs.value = { ...jobs.value, [detail.jobId]: detail }
    return detail
  }

  /**
   * Fetch + cache the geometry JSON for the job's underlying volume.
   * Cache key is the {@code e2eUuid} so OD + OS jobs that share the
   * same scan only pay for the ~100 KB JSON once.
   *
   * <p>Lookup chain: pull the {@code e2eUuid} off the cached job; if
   * the job isn't cached yet, fetch it first.
   */
  async function loadGeometry(jobId: number): Promise<GeometryJson | null> {
    let job = jobs.value[jobId]
    if (job == null) {
      job = (await loadJob(jobId)) ?? undefined as unknown as RetinalJobDetail
    }
    if (job == null) return null
    const uuid = job.e2eUuid
    if (uuid == null || uuid === '') return null
    const cached = geometries.value[uuid]
    if (cached != null) return cached
    const geometry = await fetchGeometry(jobId)
    geometries.value = { ...geometries.value, [uuid]: geometry }
    return geometry
  }

  /** Fetch + cache the summary list for one event-CRF. */
  async function loadEventCrfJobs(
    eventCrfId: number,
    force = false,
  ): Promise<RetinalJobSummary[]> {
    if (!force && eventCrfJobs.value[eventCrfId] != null) {
      return eventCrfJobs.value[eventCrfId]
    }
    eventCrfLoading.value = { ...eventCrfLoading.value, [eventCrfId]: true }
    try {
      const list = await listEventCrfJobs(eventCrfId)
      eventCrfJobs.value = { ...eventCrfJobs.value, [eventCrfId]: list }
      return list
    } finally {
      const next = { ...eventCrfLoading.value }
      delete next[eventCrfId]
      eventCrfLoading.value = next
    }
  }

  /**
   * 2026-06-19 — re-dispatch a {@code failed} job. Optimistically
   * patches the cached snapshot to {@code remote_pending} so the view
   * flips its banner + reopens the SSE stream before the next round-
   * trip; the authoritative DTO arrives via the SSE-triggered
   * {@code loadJob(force=true)} once the sidecar transitions.
   */
  const retryInflight = ref<Record<number, boolean>>({})
  async function retryJob(jobId: number): Promise<void> {
    retryInflight.value = { ...retryInflight.value, [jobId]: true }
    try {
      await retryRetinalJob(jobId)
      const cached = jobs.value[jobId]
      if (cached != null) {
        jobs.value = {
          ...jobs.value,
          [jobId]: { ...cached, status: 'remote_pending', completedAt: null },
        }
      }
    } finally {
      const next = { ...retryInflight.value }
      delete next[jobId]
      retryInflight.value = next
    }
  }

  /**
   * 2026-06-22 — rerun an existing scan as a DIFFERENT task. The backend
   * inserts a new job row + dispatches; we return the new jobId so the
   * caller (RetinalMetricsView) can route to the new job's view.
   *
   * <p>On a 409 with an existingJobId in the response body we surface
   * it via the thrown error's `cause` so the caller can navigate to
   * the existing twin instead of double-creating it.
   */
  const rerunAsInflight = ref<Record<number, boolean>>({})
  async function rerunJobAs(
    sourceJobId: number,
    task: 'fluid' | 'ga' | 'onl' | 'pr' | 'layers',
  ): Promise<number> {
    rerunAsInflight.value = { ...rerunAsInflight.value, [sourceJobId]: true }
    try {
      const resp = await rerunRetinalJobAs(sourceJobId, task)
      return resp.jobId
    } finally {
      const next = { ...rerunAsInflight.value }
      delete next[sourceJobId]
      rerunAsInflight.value = next
    }
  }

  /** Fetch + cache the summary list for one study-subject. */
  async function loadSubjectJobs(
    studySubjectId: number,
    force = false,
  ): Promise<RetinalJobSummary[]> {
    if (!force && subjectJobs.value[studySubjectId] != null) {
      return subjectJobs.value[studySubjectId]
    }
    subjectLoading.value = { ...subjectLoading.value, [studySubjectId]: true }
    try {
      const list = await listSubjectJobs(studySubjectId)
      subjectJobs.value = { ...subjectJobs.value, [studySubjectId]: list }
      return list
    } finally {
      const next = { ...subjectLoading.value }
      delete next[studySubjectId]
      subjectLoading.value = next
    }
  }

  return {
    jobs,
    geometries,
    loading,
    errors,
    eventCrfJobs,
    subjectJobs,
    eventCrfLoading,
    subjectLoading,
    retryInflight,
    rerunAsInflight,
    loadJob,
    loadJobBySubjectSeq,
    loadGeometry,
    loadEventCrfJobs,
    loadSubjectJobs,
    retryJob,
    rerunJobAs,
  }
})
