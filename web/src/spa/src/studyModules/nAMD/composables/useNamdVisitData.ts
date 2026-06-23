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

import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import { listSubjectJobs, getJob, type RetinalJobSummary, type RetinalJobDetail, type FluidPayload } from '@/api/retinal'
import type { Laterality, NamdAiRecommendation, NamdPatient, NamdVisit, NamdWorkspaceData } from '../types'
import { useNamdAiRecommendation } from './useNamdAiRecommendation'

export interface UseNamdVisitDataArgs {
  /** OID / id of the study-subject — reactive (route query). */
  studySubjectOid: ComputedRef<string | null>
  /** When true, return the static design fixture instead of hitting the API. */
  mock: ComputedRef<boolean>
}

export interface UseNamdVisitDataResult {
  data: Ref<NamdWorkspaceData | null>
  loading: Ref<boolean>
  error: Ref<string | null>
  refresh: () => Promise<void>
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

  async function refresh(): Promise<void> {
    error.value = null
    if (args.mock.value) {
      data.value = buildMockData()
      return
    }
    const oid = args.studySubjectOid.value
    if (!oid) {
      data.value = null
      return
    }
    loading.value = true
    try {
      // The endpoint takes the numeric studySubjectId, but the workspace
      // is opened from the SubjectDetailView which passes the OID; for
      // v1 we coerce — production wiring will route through a typed
      // {@code /api/v1/subjects/{oid}} resolver. When the oid isn't a
      // numeric string, surface an empty workspace so the view can
      // render its empty state. 2026-06-21 round 7 — previously we
      // fell back to buildMockData() so the workspace stayed
      // "usable"; that surfaced fixture patient S-0042 on real
      // operator screens whenever a non-numeric subject id was
      // passed. The empty state is the correct signal.
      const numericId = Number.parseInt(oid, 10)
      if (Number.isNaN(numericId)) {
        data.value = null
        return
      }
      const summaries = await listSubjectJobs(numericId)
      // 2026-06-23 — accept both the DB-native 'done' and the
      // historical/typed 'succeeded' label so jobs returned straight
      // off retinal_inference_job.status surface here. The TS type
      // declares 'succeeded' but the backend ships the raw column
      // value ('done').
      const TERMINAL_OK = new Set(['done', 'succeeded'])
      const fluidDone = summaries.filter(
        (s) => s.task === 'fluid' && TERMINAL_OK.has(s.status),
      )
      // 2026-06-23 — workspace is single-eye. Pick whichever
      // laterality has the most done jobs (ties → OD by ophthalmology
      // convention). Previously every eye's jobs streamed into the
      // same visits[] array, so the comparison + trend chart silently
      // mixed OD and OS values while the header still claimed one
      // eye. The eye-switcher control is a follow-up; for now we
      // emit a single coherent timeline per workspace load.
      const odCount = fluidDone.filter((s) => s.laterality === 'OD').length
      const osCount = fluidDone.filter((s) => s.laterality === 'OS').length
      const laterality: Laterality = osCount > odCount ? 'OS' : 'OD'
      // Sort by VISIT date (clinical chronology) rather than upload
      // completion time, so a back-fill of historical scans plots in
      // the correct visit order regardless of upload sequencing.
      const fluidSummaries = fluidDone
        .filter((s) => s.laterality === laterality)
        .sort((a, b) => {
          // 2026-06-23 — same date priority as the visit object below.
          const ka = (a.acquisitionDate ?? a.visitDate ?? a.completedAt ?? '')
          const kb = (b.acquisitionDate ?? b.visitDate ?? b.completedAt ?? '')
          return ka.localeCompare(kb)
        })
      const details = await Promise.all(
        fluidSummaries.map(async (s) => {
          try {
            return await getJob(s.jobId)
          } catch {
            return null
          }
        }),
      )
      const rawVisits: NamdVisit[] = fluidSummaries.map((s, idx) =>
        fluidJobToVisit(s, details[idx] ?? null, `V${String(idx + 1).padStart(2, '0')}`),
      )
      // 2026-06-23 user-feedback round — derive week-since-baseline
      // from visit dates so the trend chart's X axis can space visits
      // properly. Without this every visit lands at week=0, collapsing
      // every polygon + dot + label onto the same X coordinate and the
      // chart looks empty save for the current-visit coral label
      // painted on top. Falls back to the visit index when no parseable
      // date exists (preserves chronological ordering at least).
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
        id: oid,
        eye: laterality,
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
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load workspace data'
      data.value = null
    } finally {
      loading.value = false
    }
  }

  watch(
    [args.studySubjectOid, args.mock],
    () => {
      void refresh()
    },
    { immediate: true },
  )

  // Reactive AI derivation — keeps the workspace's recommendation
  // up-to-date if the data ref is later swapped (e.g. via {@code refresh()}).
  const currentRef = computed(() => data.value?.current ?? null)
  const prevRef = computed(() => data.value?.prev ?? null)
  const aiRef = useNamdAiRecommendation({ current: currentRef, prev: prevRef })
  watch(aiRef, (ai) => {
    if (data.value) {
      data.value = { ...data.value, ai }
    }
  })

  return { data, loading, error, refresh }
}
