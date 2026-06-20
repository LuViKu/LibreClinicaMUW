/**
 * nAMD workspace — shared data shape.
 *
 * The static {@code PATIENT / VISITS / CURRENT / PREV / AI} constants from
 * namd-data.jsx, normalised into TS types so the composable
 * {@code useNamdVisitData} can return a typed reactive shape. The Vue
 * components consume these types directly.
 *
 * Where fields can't be derived from existing endpoints, the composable
 * surfaces {@code null} or '-' — DO NOT add new backend endpoints to fill
 * them. The Report tab + Overview tab tolerate nulls gracefully (the
 * design already handles "no prior visit" for the first eCRF).
 */

export type Laterality = 'OD' | 'OS'

export interface NamdPatient {
  /** Site-scoped subject label, e.g. "S-0042". */
  id: string
  /** Eye under workspace — OD or OS (ophthalmology convention). */
  eye: Laterality
  /** Diagnosis line, e.g. "exsudative AMD". */
  diagnosis: string
  /** Age in years. */
  age: number | null
  /** Study identifier (label / OID), e.g. "MUW-AMD-T&E". */
  study: string
  /** Regimen description, e.g. "Treat-and-Extend · Aflibercept". */
  regimen: string
}

export interface NamdVisit {
  /** Stable identifier — event-CRF id, study-event id, or label. */
  id: string
  /** Short label, e.g. "V08". */
  label: string
  /** Week since baseline. */
  week: number
  /** ISO date string. */
  date: string
  /** Intraretinal fluid volume (nL). */
  irf: number
  /** Subretinal fluid volume (nL). */
  srf: number
  /** Pigment-epithelial detachment volume (nL). */
  ped: number
  /** Central retinal thickness (µm). */
  crt: number
  /** Best-corrected visual acuity (ETDRS letters). */
  bcva: number
  /** Injection agent, or empty string when no injection administered. */
  inj: string
  /** Interval to next visit in weeks, when scheduled. */
  interval: number | null
  /** Retinal-inference job id for this visit (drives the viewer wrapper). */
  retinalJobId: number | null
}

export interface NamdAiRecommendation {
  /** Recommendation classification — drives the decision panel pill. */
  rec: 'TREAT' | 'EXTEND' | 'SHORTEN' | 'OBSERVE'
  /** Suggested next interval (weeks). */
  intervalWeeks: number
  /** One-line rationale string, German. */
  rationale: string
}

export interface NamdWorkspaceData {
  patient: NamdPatient
  visits: NamdVisit[]
  current: NamdVisit | null
  prev: NamdVisit | null
  ai: NamdAiRecommendation | null
  /** Total OCT slices for the current visit — drives the BscanViewer slider. */
  nSlices: number | null
}
