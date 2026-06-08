import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type {
  MeasurementSeries,
  Modality,
  PatientDetail,
  PatientListItem,
  PatientsListResponse,
} from '@/types/patient'

/**
 * Phase E.6 — Patient Overview store (single human, many studies).
 *
 * Unlike the Subject Matrix (which is scoped to the active study and
 * keyed by `study_subject.label`), this store joins across studies and
 * keys by the underlying `subject.subject_id` so a patient enrolled
 * three times shows up once with three enrolment chips.
 *
 * The list endpoint is server-paginated — total/page/pageSize ride
 * along with the rows so the view can render a "{a}–{b} von {total}"
 * footer without computing it locally.
 *
 * Measurement series are cached per `(subjectId, modalityCode, eye)`
 * triple because the modal lets the operator toggle modalities on/off
 * and re-toggling shouldn't re-fetch. The cache lives for the lifetime
 * of the store (cleared on study switch via `reset()`).
 */
export const usePatientsOverviewStore = defineStore('patientsOverview', () => {
  // --- list state ---
  const list = ref<PatientListItem[]>([])
  const totalCount = ref(0)
  const page = ref(0)
  const pageSize = ref(50)
  const search = ref('')
  const isLoadingList = ref(false)
  const listError = ref<string | null>(null)

  // --- detail state ---
  const detail = ref<PatientDetail | null>(null)
  const isLoadingDetail = ref(false)
  const detailError = ref<string | null>(null)

  // --- series state (keyed by subjectId|modalityCode|eye) ---
  const seriesByKey = ref<Map<string, MeasurementSeries>>(new Map())
  const isLoadingSeriesByKey = ref<Map<string, boolean>>(new Map())

  // --- modality dictionary (loaded once per store lifetime) ---
  const modalities = ref<Modality[]>([])
  const isLoadingModalities = ref(false)
  const modalitiesError = ref<string | null>(null)

  function seriesKey(subjectId: number, modalityCode: string, eye: 'OD' | 'OS'): string {
    return `${subjectId}|${modalityCode}|${eye}`
  }

  async function loadList(p: number, ps: number, q: string): Promise<void> {
    isLoadingList.value = true
    listError.value = null
    try {
      const params = new URLSearchParams()
      params.set('page', String(p))
      params.set('pageSize', String(ps))
      if (q) params.set('search', q)
      const response = await apiGet<PatientsListResponse>(
        `/pages/api/v1/patients?${params.toString()}`,
      )
      list.value = response.patients
      totalCount.value = response.totalCount
      page.value = response.page
      pageSize.value = response.pageSize
      search.value = q
    } catch (e) {
      list.value = []
      totalCount.value = 0
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      if (e instanceof ApiNetworkError) {
        listError.value = 'Backend nicht erreichbar — bitte später erneut versuchen.'
        return
      }
      if (e instanceof ApiError) {
        listError.value = e.message
        return
      }
      listError.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden der Patienten.'
    } finally {
      isLoadingList.value = false
    }
  }

  async function loadDetail(subjectId: number): Promise<void> {
    isLoadingDetail.value = true
    detailError.value = null
    try {
      detail.value = await apiGet<PatientDetail>(`/pages/api/v1/patients/${subjectId}`)
    } catch (e) {
      detail.value = null
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      if (e instanceof ApiNetworkError) {
        detailError.value = 'Backend nicht erreichbar — bitte später erneut versuchen.'
        return
      }
      if (e instanceof ApiError) {
        detailError.value = e.message
        return
      }
      detailError.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden des Patienten.'
    } finally {
      isLoadingDetail.value = false
    }
  }

  /**
   * Fetch a per-modality, per-eye measurement series. Idempotent: cache
   * hit short-circuits the network call. The view can call this for
   * every (modality × eye) combination after the modality list loads
   * without worrying about duplicate requests.
   */
  async function loadSeries(
    subjectId: number,
    modalityCode: string,
    eye: 'OD' | 'OS',
  ): Promise<MeasurementSeries | null> {
    const key = seriesKey(subjectId, modalityCode, eye)
    if (seriesByKey.value.has(key)) return seriesByKey.value.get(key) ?? null
    if (isLoadingSeriesByKey.value.get(key)) return null
    isLoadingSeriesByKey.value.set(key, true)
    // Re-assign to trigger reactivity (Pinia tracks Map ops via assignment).
    isLoadingSeriesByKey.value = new Map(isLoadingSeriesByKey.value)
    try {
      const params = new URLSearchParams()
      params.set('modalityCode', modalityCode)
      params.set('eye', eye)
      const series = await apiGet<MeasurementSeries>(
        `/pages/api/v1/patients/${subjectId}/measurements?${params.toString()}`,
      )
      seriesByKey.value.set(key, series)
      seriesByKey.value = new Map(seriesByKey.value)
      return series
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      // Don't surface per-series errors at store level — the modal
      // renders an empty state per chart when the cache miss returns
      // null. Silent failure is preferred over interrupting the rest
      // of the modal's content.
      return null
    } finally {
      isLoadingSeriesByKey.value.delete(key)
      isLoadingSeriesByKey.value = new Map(isLoadingSeriesByKey.value)
    }
  }

  /**
   * Load the modality dictionary once. The sibling worktree's
   * `useModalitiesStore` is the long-term owner; we fall back to a
   * direct fetch here because cross-worktree imports can't be assumed
   * to resolve at build time.
   */
  async function loadModalities(): Promise<void> {
    if (modalities.value.length > 0) return
    isLoadingModalities.value = true
    modalitiesError.value = null
    try {
      modalities.value = await apiGet<Modality[]>('/pages/api/v1/modalities')
    } catch (e) {
      modalities.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      if (e instanceof ApiNetworkError) {
        modalitiesError.value = 'Backend nicht erreichbar — bitte später erneut versuchen.'
        return
      }
      if (e instanceof ApiError) {
        modalitiesError.value = e.message
        return
      }
      modalitiesError.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden der Modalitäten.'
    } finally {
      isLoadingModalities.value = false
    }
  }

  /**
   * Clear detail-only state so the modal can re-mount cleanly when the
   * operator opens a different patient. List state is preserved on
   * purpose — the list survives the modal open/close round trip.
   */
  function resetDetail(): void {
    detail.value = null
    isLoadingDetail.value = false
    detailError.value = null
  }

  /**
   * Wipe every piece of patient-overview state. Wired into the auth
   * store's `resetStudyScopedStores` for parity with the other
   * study-scoped stores; even though the patients dataset spans
   * studies, the read scope is the user's role binding which can
   * change with the active study.
   */
  function reset(): void {
    list.value = []
    totalCount.value = 0
    page.value = 0
    pageSize.value = 50
    search.value = ''
    isLoadingList.value = false
    listError.value = null
    detail.value = null
    isLoadingDetail.value = false
    detailError.value = null
    seriesByKey.value = new Map()
    isLoadingSeriesByKey.value = new Map()
    modalities.value = []
    isLoadingModalities.value = false
    modalitiesError.value = null
  }

  return {
    // state
    list,
    totalCount,
    page,
    pageSize,
    search,
    isLoadingList,
    listError,
    detail,
    isLoadingDetail,
    detailError,
    seriesByKey,
    isLoadingSeriesByKey,
    modalities,
    isLoadingModalities,
    modalitiesError,
    // helpers
    seriesKey,
    // actions
    loadList,
    loadDetail,
    loadSeries,
    loadModalities,
    resetDetail,
    reset,
  }
})
