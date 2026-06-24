/**
 * nAMD workspace — visit-data composable.
 *
 * Assembles the typed {@link NamdWorkspaceData} shape consumed by every
 * tab. The design originally relied on static
 * {@code PATIENT / VISITS / CURRENT / PREV / AI} constants — this hook
 * is the real-data replacement, derived from the existing endpoints:
 *
 *   1. {@code GET /pages/api/v1/study-subjects/{id}/retinal-jobs}
 *      ({@link listSubjectJobs}) — one summary row per inference job
 *      across every event-CRF the subject owns. The fluid task's
 *      {@code primaryMetric} carries the total-fluid mm³ surfaced by
 *      Wave 2's {@code FluidMetric}.
 *   2. For each fluid job, {@link getJob} expands the per-biomarker
 *      breakdown ({@code outputPayload.biomarkers.{irf,srf,ped}_mm3}).
 *
 * Mock fallback: when {@code mock=true} the hook returns the design's
 * 8-visit treat-and-extend example so the workspace renders end-to-end
 * even when the live demo study has no real retinal jobs yet.
 *
 * <p>Fields the existing endpoints don't surface (BCVA letters, CRT in
 * µm, injection agent, interval-to-next) come back as zero / empty
 * string / null. The Report tab + Overview tolerate null gracefully; a
 * future eCRF-bound fetch can fill them in without changing the
 * consumer shape.
 */

import { computed, ref, shallowRef, watch, type ComputedRef, type Ref } from 'vue'
import { listSubjectJobs, getJob, type RetinalJobSummary, type RetinalJobDetail, type FluidPayload } from '@/api/retinal'
import type { Laterality, NamdAiRecommendation, NamdPatient, NamdVisit, NamdWorkspaceData } from '../types'
import { useNamdAiRecommendation } from './useNamdAiRecommendation'

export interface UseNamdVisitDataArgs {
  /** OID / id of the study-subject — reactive (route query). */
  studySubjectOid: ComputedRef<string | null>
  /**
   * 2026-06-24 user-feedback round — site-scoped subject label (e.g.
   * "EIAMD150"). When present the workspace banner + breadcrumbs use
   * this for display instead of the numeric study_subject_id. Falls
   * back to the oid when blank — preserves the legacy behaviour for
   * direct deep-links that omit the label.
   */
  studySubjectLabel?: ComputedRef<string | null>
  /** When true, return the static design fixture instead of hitting the API. */
  mock: ComputedRef<boolean>
}

export interface UseNamdVisitDataResult {
  data: Ref<NamdWorkspaceData | null>
  loading: Ref<boolean>
  error: Ref<string | null>
  refresh: () => Promise<void>
  /**
   * 2026-06-24 user-feedback round — eyes the subject has at least
   * one completed fluid job for. Empty if no jobs exist; one entry
   * for monocular follow-up; both ('OD' and 'OS') when both eyes
   * are enrolled. Drives the eye-switcher pill row on the nAMD
   * patient banner.
   */
  availableEyes: Ref<Laterality[]>
  /**
   * Currently active eye. The patient banner highlights the matching
   * pill; the rest of the workspace (trend chart, viewer, compare,
   * report) keys off the {@link data} ref which the composable
   * rebuilds whenever this ref changes.
   */
  selectedEye: Ref<Laterality>
  /** Switch the active eye. No-op when called with the current value. */
  setEye: (eye: Laterality) => void
}

/** mm³ → nL (1 mm³ = 1 µL = 1000 nL — but the design's "nL" scale fits
 *  reasonable fluid volumes between 0 and 100 when 1 mm³ ≈ 100 nL is
 *  used as the display scaling. Match the design's range so the activity
 *  threshold of 20 still reads sensibly). */
function mm3ToNl(v: number | null | undefined): number {
  if (v == null) return 0
  return Math.round(v * 100)
}

const MS_PER_WEEK = 7 * 24 * 60 * 60 * 1000

/** Parse an ISO date / instant string to ms. Returns null on parse fail. */
function parseDateMs(iso: string | null | undefined): number | null {
  if (!iso) return null
  const ms = Date.parse(iso)
  return Number.isFinite(ms) ? ms : null
}

function isFluidPayload(p: unknown): p is FluidPayload {
  if (!p || typeof p !== 'object') return false
  const b = (p as { biomarkers?: unknown }).biomarkers
  return !!b && typeof (b as { irf_mm3?: unknown }).irf_mm3 === 'number'
}

/**
 * 2026-06-23 user-feedback round — maximum tolerated drift (days)
 * between the planned visit date and the .e2e acquisition date
 * before the visit is flagged as mismatched. Two days handles the
 * common case where the visit is scheduled for a Friday but the
 * scan is taken on the following Monday morning without
 * re-scheduling.
 */
export const DATE_MISMATCH_DAYS = 2
const MS_PER_DAY = 24 * 60 * 60 * 1000

function isDateMismatch(
  visitIso: string | null | undefined,
  acquiredIso: string | null | undefined,
): boolean {
  if (!visitIso || !acquiredIso) return false
  const v = parseDateMs(visitIso)
  const a = parseDateMs(acquiredIso)
  if (v == null || a == null) return false
  return Math.abs(v - a) / MS_PER_DAY > DATE_MISMATCH_DAYS
}

/** Default mock fixture — mirrors the design's 8-visit T&E example. */
function buildMockData(): NamdWorkspaceData {
  const patient: NamdPatient = {
    id: 'S-0042',
    eye: 'OD',
    diagnosis: 'Exsudative AMD',
    age: 78,
    study: 'MUW-AMD-T&E',
    regimen: 'Treat-and-Extend · Aflibercept',
  }
  const visits: NamdVisit[] = [
    { id: 'v01', label: 'V01', week: 0, date: '2025-09-01', irf: 38, srf: 22, ped: 16, crt: 412, bcva: 62, inj: 'Aflibercept', interval: 4, retinalJobId: null, acquisitionDate: null, visitDate: null, dateMismatch: false },
    { id: 'v02', label: 'V02', week: 4, date: '2025-09-29', irf: 26, srf: 14, ped: 14, crt: 372, bcva: 66, inj: 'Aflibercept', interval: 4, retinalJobId: null, acquisitionDate: null, visitDate: null, dateMismatch: false },
    { id: 'v03', label: 'V03', week: 8, date: '2025-10-27', irf: 18, srf: 8, ped: 12, crt: 336, bcva: 70, inj: 'Aflibercept', interval: 6, retinalJobId: null, acquisitionDate: null, visitDate: null, dateMismatch: false },
    { id: 'v04', label: 'V04', week: 14, date: '2025-12-08', irf: 12, srf: 4, ped: 10, crt: 314, bcva: 72, inj: 'Aflibercept', interval: 8, retinalJobId: null, acquisitionDate: null, visitDate: null, dateMismatch: false },
    { id: 'v05', label: 'V05', week: 22, date: '2026-02-02', irf: 10, srf: 2, ped: 9, crt: 302, bcva: 74, inj: 'Aflibercept', interval: 10, retinalJobId: null, acquisitionDate: null, visitDate: null, dateMismatch: false },
    { id: 'v06', label: 'V06', week: 32, date: '2026-04-13', irf: 8, srf: 1, ped: 9, crt: 296, bcva: 75, inj: '', interval: 12, retinalJobId: null, acquisitionDate: null, visitDate: null, dateMismatch: false },
    { id: 'v07', label: 'V07', week: 44, date: '2026-07-06', irf: 14, srf: 7, ped: 11, crt: 322, bcva: 73, inj: 'Aflibercept', interval: 8, retinalJobId: null, acquisitionDate: null, visitDate: null, dateMismatch: false },
    { id: 'v08', label: 'V08', week: 52, date: '2026-08-31', irf: 22, srf: 9, ped: 12, crt: 348, bcva: 71, inj: '', interval: null, retinalJobId: null, acquisitionDate: null, visitDate: null, dateMismatch: false },
  ]
  const current = visits[visits.length - 1]!
  const prev = visits[visits.length - 2]!
  // Derive AI hint at build time — the live composable uses the reactive
  // hook, but this static fixture exposes a frozen recommendation so the
  // Report tab can render without re-deriving on every read.
  const ai: NamdAiRecommendation = {
    rec: 'SHORTEN',
    intervalWeeks: 6,
    rationale: 'Reaktivierung mit Anstieg der Gesamtflüssigkeit — Intervall verkürzen.',
  }
  return { patient, visits, current, prev, ai, nSlices: 49 }
}

/** Reshape a single fluid {@link RetinalJobDetail} into a {@link NamdVisit}. */
function fluidJobToVisit(
  summary: RetinalJobSummary,
  detail: RetinalJobDetail | null,
  fallbackLabel: string,
): NamdVisit {
  const payload = detail?.outputPayload
  const biomarkers = isFluidPayload(payload) ? payload.biomarkers : null
  return {
    id: String(summary.jobId),
    label: fallbackLabel,
    // Week / BCVA / injection / interval require eCRF-bound lookups
    // not yet wired — surface zero / empty so the tabs render gracefully.
    week: 0,
    // 2026-06-23 — prefer visit_date (clinically meaningful) and fall
    // back to completed_at when the backend doesn't supply it. Was:
    // always completed_at, so a batch of historical scans uploaded
    // today all read as "today" across the workspace.
    // 2026-06-23 user-feedback round — date priority:
    //   1. acquisitionDate — pulled by retinal-preprocess from the
    //      .e2e header; the device's native scan-time stamp.
    //   2. visitDate — study_event.date_start, the scheduled visit
    //      date (may match the acquisition for prospective uploads).
    //   3. completedAt — upload-pipeline timestamp (the day the
    //      operator clicked Hochladen). Last resort.
    date: summary.acquisitionDate ?? summary.visitDate ?? summary.completedAt ?? '',
    acquisitionDate: summary.acquisitionDate ?? null,
    visitDate: summary.visitDate ?? null,
    dateMismatch: isDateMismatch(summary.visitDate, summary.acquisitionDate),
    irf: mm3ToNl(biomarkers?.irf_mm3),
    srf: mm3ToNl(biomarkers?.srf_mm3),
    ped: mm3ToNl(biomarkers?.ped_mm3),
    crt: 0,
    bcva: 0,
    inj: '',
    interval: null,
    retinalJobId: summary.jobId,
  }
}

export function useNamdVisitData(args: UseNamdVisitDataArgs): UseNamdVisitDataResult {
  const data = ref<NamdWorkspaceData | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  /**
   * 2026-06-24 user-feedback round — eye-switcher support.
   *
   * <p>The workspace is single-eye at render time, but a subject may
   * have done fluid jobs for both OD and OS. {@link availableEyes}
   * holds the set of eyes the subject has jobs for; the banner
   * renders one pill per entry. {@link selectedEye} drives the
   * filter applied to {@link allFluidDone} when assembling the
   * visit timeline. {@link setEye} switches the active eye locally
   * (no HTTP round-trip — we cached the summaries + details below).
   */
  const availableEyes = ref<Laterality[]>([])
  const selectedEye = ref<Laterality>('OD')
  // Per-fetch caches: the per-eye filtering + rebuild is all local
  // so an eye switch is instantaneous (no second listSubjectJobs +
  // bulk getJob round-trip).
  const allFluidDone = shallowRef<RetinalJobSummary[]>([])
  const fluidDetailsCache = new Map<number, RetinalJobDetail | null>()

  /**
   * Build the visit timeline + patient banner from the cached
   * summaries+details, filtered to {@link selectedEye}. Called from
   * {@link refresh} (after fetching) and from {@link setEye} (after
   * the operator picks the other eye).
   */
  function rebuildData(oid: string): void {
    if (allFluidDone.value.length === 0) {
      data.value = null
      return
    }
    const fluidSummaries = allFluidDone.value
      .filter((s) => s.laterality === selectedEye.value)
      .sort((a, b) => {
        const ka = (a.acquisitionDate ?? a.visitDate ?? a.completedAt ?? '')
        const kb = (b.acquisitionDate ?? b.visitDate ?? b.completedAt ?? '')
        return ka.localeCompare(kb)
      })
    const rawVisits: NamdVisit[] = fluidSummaries.map((s, idx) =>
      fluidJobToVisit(
        s,
        fluidDetailsCache.get(s.jobId) ?? null,
        `V${String(idx + 1).padStart(2, '0')}`,
      ),
    )
    const baselineMs = parseDateMs(rawVisits[0]?.date)
    const visits: NamdVisit[] = rawVisits.map((v, idx) => {
      if (baselineMs == null) return { ...v, week: idx }
      const vMs = parseDateMs(v.date)
      if (vMs == null) return { ...v, week: idx }
      const week = Math.round((vMs - baselineMs) / MS_PER_WEEK)
      return { ...v, week: Math.max(0, week) }
    })
    const current = visits.length > 0 ? visits[visits.length - 1]! : null
    const prev = visits.length > 1 ? visits[visits.length - 2]! : null
    const patient: NamdPatient = {
      id: args.studySubjectLabel?.value || oid,
      eye: selectedEye.value,
      diagnosis: 'Exsudative AMD',
      age: null,
      study: '',
      regimen: 'Treat-and-Extend',
    }
    data.value = {
      patient,
      visits,
      current,
      prev,
      ai: null, // derived reactively below
      nSlices: null,
    }
  }

  async function refresh(): Promise<void> {
    error.value = null
    if (args.mock.value) {
      data.value = buildMockData()
      // Mock fixture is OD-only; expose that so the banner shows a
      // single (active) OD pill.
      availableEyes.value = ['OD']
      selectedEye.value = 'OD'
      return
    }
    const oid = args.studySubjectOid.value
    if (!oid) {
      data.value = null
      availableEyes.value = []
      return
    }
    loading.value = true
    try {
      const numericId = Number.parseInt(oid, 10)
      if (Number.isNaN(numericId)) {
        data.value = null
        availableEyes.value = []
        return
      }
      const summaries = await listSubjectJobs(numericId)
      // 2026-06-23 — accept both the DB-native 'done' and the
      // historical/typed 'succeeded' label so jobs returned straight
      // off retinal_inference_job.status surface here.
      const TERMINAL_OK = new Set(['done', 'succeeded'])
      const fluidDone = summaries.filter(
        (s) => s.task === 'fluid' && TERMINAL_OK.has(s.status),
      )
      // Eye discovery: which lateralities have at least one done
      // fluid job? Drives the banner's pill row.
      const eyes = new Set<Laterality>()
      for (const s of fluidDone) {
        if (s.laterality === 'OD') eyes.add('OD')
        else if (s.laterality === 'OS') eyes.add('OS')
      }
      const sortedEyes: Laterality[] = (['OD', 'OS'] as const).filter((e) => eyes.has(e))
      availableEyes.value = sortedEyes
      // Default: whichever eye has the most jobs (ties → OD per the
      // ophthalmology face-to-face convention). Only updated when the
      // current selection is no longer available — preserves the
      // operator's manual pick across re-fetches.
      if (!sortedEyes.includes(selectedEye.value) && sortedEyes.length > 0) {
        const odCount = fluidDone.filter((s) => s.laterality === 'OD').length
        const osCount = fluidDone.filter((s) => s.laterality === 'OS').length
        selectedEye.value = osCount > odCount ? 'OS' : 'OD'
      }
      // Pre-fetch every detail so the eye-switcher is local (no HTTP
      // round-trip on toggle). The bulk hit was already happening
      // for the selected eye; now it covers both.
      fluidDetailsCache.clear()
      const details = await Promise.all(
        fluidDone.map(async (s) => {
          try {
            return [s.jobId, await getJob(s.jobId)] as const
          } catch {
            return [s.jobId, null] as const
          }
        }),
      )
      for (const [jobId, detail] of details) {
        fluidDetailsCache.set(jobId, detail)
      }
      allFluidDone.value = fluidDone
      rebuildData(oid)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load workspace data'
      data.value = null
      availableEyes.value = []
    } finally {
      loading.value = false
    }
  }

  /**
   * Switch the active eye. Same patient, same subject — just re-pivot
   * the cached summaries+details and rebuild data.value.
   */
  function setEye(eye: Laterality): void {
    if (eye === selectedEye.value) return
    if (!availableEyes.value.includes(eye)) return
    selectedEye.value = eye
    const oid = args.studySubjectOid.value
    if (oid) rebuildData(oid)
  }

  // 2026-06-24 — re-fetch when the label changes too. This is mostly
  // a no-op (the API call keys off the oid), but it ensures the
  // patient.id reactive ref picks up a late-arriving subjectLabel
  // query param.
  watch(
    [args.studySubjectOid, args.mock, args.studySubjectLabel ?? (() => null)],
    () => {
      void refresh()
    },
    { immediate: true },
  )

  // Reactive AI derivation — keeps the workspace's recommendation
  // up-to-date if the data ref is later swapped (e.g. via {@code refresh()}).
  //
  // 2026-06-24 user-feedback round — previously this re-assigned
  // {@code data.value = { ...data.value, ai }} which spawned a new
  // outer object identity on every aiRef tick. Vue's reactivity
  // re-tracked the new proxy, App.vue's breadcrumb-consuming
  // template re-ran, and (combined with the per-render array-literal
  // identity from useViewBreadcrumb's source computed) tripped the
  // "Maximum recursive updates exceeded" guard. Mutate the EXISTING
  // {@code data.value.ai} field in place instead — same reactive
  // outer object, only the nested property changes, so the
  // breadcrumb computed isn't invalidated and the render loop stays
  // bounded.
  const currentRef = computed(() => data.value?.current ?? null)
  const prevRef = computed(() => data.value?.prev ?? null)
  const aiRef = useNamdAiRecommendation({ current: currentRef, prev: prevRef })
  watch(aiRef, (ai) => {
    if (data.value && data.value.ai !== ai) {
      data.value.ai = ai
    }
  })

  return { data, loading, error, refresh, availableEyes, selectedEye, setEye }
}
