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
  /**
   * ISO date string used as the visit's displayed date — already
   * resolved against the {@code acquisitionDate → visitDate →
   * completedAt} priority chain by the composable. The two raw
   * inputs stay on the visit so the report tab can flag a mismatch
   * (e.g. the planned-visit date doesn't line up with the .e2e
   * acquisition stamp).
   */
  date: string
  /**
   * 2026-06-23 user-feedback round — raw OCT acquisition date pulled
   * from the .e2e header (or null when the device left it blank /
   * the preprocess sidecar is older than the header).
   */
  acquisitionDate: string | null
  /**
   * 2026-06-23 — raw study_event.date_start (planned visit date).
   * Null only when the job has no event binding.
   */
  visitDate: string | null
  /**
   * 2026-06-23 — true when the acquisition date and the planned
   * visit date diverge by more than {@code DATE_MISMATCH_DAYS}. Used
   * by the report tab to surface a warning so the operator can
   * decide whether to re-schedule the visit or accept the
   * discrepancy.
   */
  dateMismatch: boolean
  /**
   * Intraretinal fluid volume (nL). Equals the central-6 mm value
   * (the widest ETDRS ring); identical to {@link fluidByRegion}.c6.irf
   * when {@link fluidByRegion} is present. Stays on the visit shape
   * for backwards-compat with the existing seg-cards / report / SegCards
   * read sites that don't care about the ring filter.
   */
  irf: number
  /** Subretinal fluid volume (nL). Mirrors {@link irf}'s c6 semantics. */
  srf: number
  /** Pigment-epithelial detachment volume (nL). Mirrors {@link irf}'s c6 semantics. */
  ped: number
  /**
   * 2026-06-26 user-feedback round — per-ETDRS-ring biomarker
   * breakdown driving the Flüssigkeitsverlauf chart's region filter
   * (Zentral 1 mm / 3 mm / 6 mm). Null when the source job's payload
   * predates the etdrs_mm3 emission, or for the demo dataset which
   * only carries the flat c6 values. The chart falls through to
   * {@link irf} / {@link srf} / {@link ped} when this is missing.
   */
  fluidByRegion: {
    c1: { irf: number; srf: number; ped: number }
    c3: { irf: number; srf: number; ped: number }
    c6: { irf: number; srf: number; ped: number }
  } | null
  /** Central retinal thickness (µm). */
  crt: number
  /** Best-corrected visual acuity (ETDRS letters). */
  bcva: number
  /**
   * 2026-06-24 user-feedback round — canonical raw BCVA form for
   * tooltip + audit display. For decimal-flavoured studies (post-
   * BCVA-portal) this is the `1,0p-2` / `0,8+2` form; for legacy
   * letters-flavoured studies the field stays null (the letter
   * count IS the raw form). Null also when no BCVA row exists for
   * the visit at all (the chart falls back to {@link bcva} = 0).
   */
  bcvaRaw: string | null
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
