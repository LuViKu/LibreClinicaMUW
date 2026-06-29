/**
 * 2026-06-26 — Resolve the layers / bm sibling job for an nAMD fluid job.
 *
 * The nAMD OCT-Viewer tab is mounted with the FLUID job's id (because
 * the fluid mask is the primary segmentation it renders). To overlay
 * ILM + BM polylines on top, AND to expose the layer-correction UI in
 * fullscreen, we need the sibling job whose task is `layers` (preferred)
 * or `bm` (fallback) for the same (subject, eye, study_event).
 *
 * <p>The lookup uses {@link listSubjectJobs} (already on the API client),
 * so the composable's HTTP cost is one summary fetch per subject — cached
 * for subsequent eye/event swaps.
 *
 * <p>Returns null on either side when:
 *   <ul>
 *     <li>The fluid job has no resolvable studyEventId / eyeLaterality.</li>
 *     <li>The subject has no done `layers` or `bm` job for that event + eye.</li>
 *   </ul>
 *
 * <p>The envelope is fetched via the shared
 * {@link useSegmentationEnvelope} composable — the module-level Map
 * cache means multiple consumers hitting the same sibling job only
 * decode the bytes once.
 */
import { computed, ref, shallowRef, watch, type Ref } from 'vue'

import { listSubjectJobs, type RetinalJobSummary } from '@/api/retinal'
import { useSegmentationEnvelope, type SegmentationEnvelope } from '@/composables/useSegmentationEnvelope'

export interface SiblingLayersJobArgs {
  /**
   * The study subject id (numeric, NOT the label OID). Required for the
   * per-subject job-list API.
   */
  studySubjectId: Ref<number | null>
  /**
   * The current job id (typically the FLUID job displayed in the nAMD
   * viewer). The composable looks this up in the subject's summary list
   * to derive `studyEventId` + `laterality`, then finds the sibling
   * layers / bm job for the same (event, laterality).
   *
   * <p>Decouples the consumer from having to know the event id itself —
   * NamdScanFrame already has `props.visit.retinalJobId` in hand.
   */
  currentJobId: Ref<number | null>
}

export interface SiblingLayersJobResult {
  siblingJobId: Ref<number | null>
  siblingTask: Ref<'layers' | 'bm' | null>
  envelope: Ref<SegmentationEnvelope | null>
  loading: Ref<boolean>
  error: Ref<string | null>
}

/** Module-level cache: studySubjectId → Promise<summaries>. */
const subjectJobsCache = new Map<number, Promise<RetinalJobSummary[]>>()

function fetchSummaries(studySubjectId: number): Promise<RetinalJobSummary[]> {
  let pending = subjectJobsCache.get(studySubjectId)
  if (!pending) {
    pending = listSubjectJobs(studySubjectId)
    subjectJobsCache.set(studySubjectId, pending)
  }
  return pending
}

/**
 * Bust the cached summaries for one subject. Consumers call this after
 * a save / rerun / status push so the next sibling lookup hits the
 * backend again.
 */
export function clearSiblingLayersJobCache(studySubjectId: number | null): void {
  if (studySubjectId == null) return
  subjectJobsCache.delete(studySubjectId)
}

export function useSiblingLayersJob(args: SiblingLayersJobArgs): SiblingLayersJobResult {
  const siblingJobId = ref<number | null>(null)
  const siblingTask = ref<'layers' | 'bm' | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function resolve(): Promise<void> {
    const ssId = args.studySubjectId.value
    const curJobId = args.currentJobId.value
    if (ssId == null || curJobId == null) {
      siblingJobId.value = null
      siblingTask.value = null
      return
    }
    loading.value = true
    error.value = null
    try {
      const summaries = await fetchSummaries(ssId)
      const cur = summaries.find((s) => s.jobId === curJobId)
      const eventId = cur?.studyEventId ?? null
      const lat = cur?.laterality ?? null
      if (eventId == null || lat == null) {
        siblingJobId.value = null
        siblingTask.value = null
        return
      }
      // Prefer 'layers' over 'bm'; require status='done'; match
      // (studyEventId, laterality).
      const candidates = summaries.filter((s) =>
        s.studyEventId === eventId
          && s.laterality === lat
          && s.status === 'done'
          && (s.task === 'layers' || s.task === 'bm'),
      )
      const layers = candidates.find((s) => s.task === 'layers')
      const bm = candidates.find((s) => s.task === 'bm')
      const pick = layers ?? bm ?? null
      siblingJobId.value = pick?.jobId ?? null
      siblingTask.value = pick ? (pick.task as 'layers' | 'bm') : null
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to resolve sibling layers job'
      siblingJobId.value = null
      siblingTask.value = null
    } finally {
      loading.value = false
    }
  }

  // Re-resolve whenever any of the inputs change.
  watch(
    [args.studySubjectId, args.currentJobId],
    () => { void resolve() },
    { immediate: true },
  )

  // Hand the sibling job id to useSegmentationEnvelope for the actual
  // bytes fetch. The composable's own watcher keeps the envelope in
  // sync with siblingJobId.
  const { envelope } = useSegmentationEnvelope(siblingJobId)

  // 2026-06-26 — shallowRef-typed re-export to keep the consumer's
  // template usage simple ({ envelope.value?.shape }).
  const envelopeOut = shallowRef<SegmentationEnvelope | null>(null)
  watch(envelope, (v) => { envelopeOut.value = v }, { immediate: true })

  return {
    siblingJobId,
    siblingTask: computed(() => siblingTask.value) as Ref<'layers' | 'bm' | null>,
    envelope: envelopeOut,
    loading,
    error,
  }
}
