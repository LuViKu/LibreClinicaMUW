/**
 * 2026-06-24 user-feedback round — public BCVA-entry portal store.
 *
 * <p>Holds the per-study visit list + per-visit form state. The
 * portal opens at {@code /app/bcva-entry/<studyOid>}; the store is
 * the single source of truth for the operator's entry session.
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

import {
  listVisits as apiListVisits,
  commit as apiCommit,
  BcvaPortalError,
  type BcvaPortalVisit,
  type BcvaPortalStudyHeader,
  type BcvaCommitRequest,
} from '@/api/bcvaPortal'
import { parseBcvaInput, formatBcva } from '@/lib/bcvaConversion'

/** Per-eye visit form state. The decimal + partial pair comes from
 *  parsing the operator's raw input via {@link parseBcvaInput}; refraction
 *  fields stay as literal strings so the operator can paste an
 *  autorefractometer printout verbatim (`-1.25` or `-1,25` both
 *  acceptable). */
export interface EyeForm {
  /** The operator's raw BCVA input (`1,0p-2`, `0,8+2`, `0,5` …). */
  bcvaRaw: string
  /** Parsed BCVA — null when bcvaRaw is empty or unparseable. */
  decimal: number | null
  partial: number | null
  bcvaError: string | null
  /** Refraction inputs — literal text, validated server-side. */
  sphere: string
  cylinder: string
  axis: string
}

export type CommitState = 'ready' | 'committing' | 'committed' | 'error'

/** Per-visit form state. */
export interface VisitForm {
  visit: BcvaPortalVisit
  od: EyeForm
  os: EyeForm
  commitState: CommitState
  commitError: string | null
  lastCommitAt: number | null
  lastCommitId: number | null
}

const EMPTY_EYE = (): EyeForm => ({
  bcvaRaw: '',
  decimal: null,
  partial: null,
  bcvaError: null,
  sphere: '',
  cylinder: '',
  axis: '',
})

const todayIso = (): string => new Date().toISOString().slice(0, 10)

const ENTERED_BY_KEY = 'lc-muw.bcvaPortal.enteredBy'

export const useBcvaPortalStore = defineStore('bcvaPortal', () => {
  // Connection identity — set by BcvaPortalView from the route param.
  const studyOid = ref<string>('')
  const study = ref<BcvaPortalStudyHeader | null>(null)
  const selectedDate = ref<string>(todayIso())
  // Persist the entered-by name in localStorage so a nurse working
  // a full clinic day doesn't retype it per submission.
  const enteredBy = ref<string>(typeof localStorage !== 'undefined'
    ? (localStorage.getItem(ENTERED_BY_KEY) ?? '')
    : '')

  const loading = ref<boolean>(false)
  const loadError = ref<string | null>(null)
  const visits = ref<VisitForm[]>([])

  const enteredByValid = computed(() => enteredBy.value.trim().length >= 2)

  function setEnteredBy(name: string) {
    enteredBy.value = name
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(ENTERED_BY_KEY, name)
    }
  }

  /** Re-parse a visit's raw BCVA input. Idempotent. */
  function reparseEye(form: EyeForm): void {
    const raw = form.bcvaRaw.trim()
    if (raw === '') {
      form.decimal = null
      form.partial = null
      form.bcvaError = null
      return
    }
    const parsed = parseBcvaInput(raw)
    if (parsed == null) {
      form.decimal = null
      form.partial = null
      form.bcvaError = 'invalid'
    } else {
      form.decimal = parsed.decimal
      form.partial = parsed.partial
      form.bcvaError = null
    }
  }

  function makeVisitForm(visit: BcvaPortalVisit): VisitForm {
    return {
      visit,
      od: EMPTY_EYE(),
      os: EMPTY_EYE(),
      commitState: 'ready',
      commitError: null,
      lastCommitAt: null,
      lastCommitId: null,
    }
  }

  async function loadVisits(): Promise<void> {
    if (!studyOid.value) return
    loading.value = true
    loadError.value = null
    try {
      const resp = await apiListVisits(studyOid.value, selectedDate.value)
      study.value = resp.study
      visits.value = (resp.visits ?? []).map(makeVisitForm)
    } catch (e: unknown) {
      const msg = e instanceof BcvaPortalError
        ? `${e.status}: ${e.message}`
        : (e instanceof Error ? e.message : String(e))
      loadError.value = msg
      visits.value = []
    } finally {
      loading.value = false
    }
  }

  /** Build the commit payload for a visit; omits blank fields. */
  function buildCommitPayload(form: VisitForm): BcvaCommitRequest {
    const values: Record<string, number | string | null> = {}
    const enroll = (oid: string, v: string | number | null) => {
      if (v == null) return
      if (typeof v === 'string' && v.trim() === '') return
      values[oid] = v
    }
    if (form.od.decimal != null) {
      enroll('OD_BCVA_DECIMAL', form.od.decimal)
      if (form.od.partial != null && form.od.partial !== 0) {
        enroll('OD_BCVA_PARTIAL', form.od.partial)
      }
    }
    if (form.os.decimal != null) {
      enroll('OS_BCVA_DECIMAL', form.os.decimal)
      if (form.os.partial != null && form.os.partial !== 0) {
        enroll('OS_BCVA_PARTIAL', form.os.partial)
      }
    }
    enroll('OD_BCVA_REFRACTION_SPHERE', form.od.sphere.replace(',', '.'))
    enroll('OD_BCVA_REFRACTION_CYLINDER', form.od.cylinder.replace(',', '.'))
    enroll('OD_BCVA_REFRACTION_AXIS', form.od.axis)
    enroll('OS_BCVA_REFRACTION_SPHERE', form.os.sphere.replace(',', '.'))
    enroll('OS_BCVA_REFRACTION_CYLINDER', form.os.cylinder.replace(',', '.'))
    enroll('OS_BCVA_REFRACTION_AXIS', form.os.axis)
    return {
      studyEventId: form.visit.studyEventId,
      enteredBy: enteredBy.value.trim(),
      values,
    }
  }

  async function commitVisit(visitIdx: number): Promise<void> {
    const form = visits.value[visitIdx]
    if (!form) return
    // Block when BCVA input is unparseable on either eye.
    if (form.od.bcvaError || form.os.bcvaError) {
      form.commitError = 'invalid'
      form.commitState = 'error'
      return
    }
    // Require at least one BCVA value — refraction-only submissions
    // are blocked at the SPA edge for clinical clarity.
    if (form.od.decimal == null && form.os.decimal == null) {
      form.commitError = 'noBcva'
      form.commitState = 'error'
      return
    }
    form.commitState = 'committing'
    form.commitError = null
    try {
      const payload = buildCommitPayload(form)
      const resp = await apiCommit(payload)
      form.commitState = 'committed'
      form.lastCommitAt = Date.now()
      form.lastCommitId = resp.auditId
      form.visit.eventCrfId = resp.eventCrfId
      form.visit.bcvaAlreadyEntered = true
    } catch (e: unknown) {
      form.commitState = 'error'
      form.commitError = e instanceof BcvaPortalError
        ? `${e.status}: ${e.message}`
        : (e instanceof Error ? e.message : String(e))
    }
  }

  /** Canonical render of a parsed BCVA pair — exposed so the view
   *  can show `1,0p-2` below the input as a confirmation. */
  function formatEye(eye: EyeForm): string {
    if (eye.decimal == null) return ''
    return formatBcva(eye.decimal, eye.partial ?? 0)
  }

  /** Reset a committed visit's form so the operator can correct a
   *  typo without reloading the whole list. */
  function reopenVisit(visitIdx: number): void {
    const form = visits.value[visitIdx]
    if (!form) return
    form.commitState = 'ready'
    form.commitError = null
  }

  return {
    // state
    studyOid,
    study,
    selectedDate,
    enteredBy,
    loading,
    loadError,
    visits,
    // derived
    enteredByValid,
    // actions
    setEnteredBy,
    loadVisits,
    commitVisit,
    reparseEye,
    formatEye,
    reopenVisit,
  }
})
