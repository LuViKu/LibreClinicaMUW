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
  /**
   * Numeric `study_subject_id` (DB primary key). Required for the
   * layer-correction sibling-job lookup (which calls
   * `/study-subjects/{id}/retinal-jobs`). Null in the mock fixture.
   */
  studySubjectId: number | null
  /** Eye under workspace — OD or OS (ophthalmology convention). */
  eye: Laterality
  /** Diagnosis line, e.g. "exsudative AMD". */
  diagnosis: string
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
  /**
   * 2026-06-30 — event_crf_id that backs this visit's NAMD_VISIT
   * CRF row. Sourced from the retinal-job detail; null on the demo
   * fixture + on parked jobs (no event binding). Drives the
   * {@link NamdDecisionPanel}'s POST target.
   */
  eventCrfId: number | null
  /**
   * 2026-07-06 — legacy `study_event.study_event_id` that anchors
   * this visit. Independent of {@link eventCrfId} — a visit can
   * exist without any CRF row (fresh scheduled visit). The clinical-
   * flags write endpoint keys off this so it can create the NAMD_VISIT
   * event_crf on demand when a physician records the first flag.
   */
  studyEventId: number | null
  /**
   * 2026-06-30 — per-eye clinical-flag observations recorded by the
   * physician at this visit. Both default to false when no CRF row
   * was authored. The rule engine reads the eye matching
   * {@link NamdPatient.eye}.
   */
  hemorrhage: boolean
  /**
   * 2026-06-30 — true when a BCVA drop is clinically attributable
   * to nAMD activity (vs cataract, dry eye, etc). The rule engine
   * fires {@code BCVA_LOSS_5_LETTERS} only when this flag is true
   * AND the BCVA delta vs the prior visit is ≤ −5 letters.
   */
  bcvaAttributableToNamd: boolean
}

/** Trigger keys the rule engine emits — stable wire identifiers. */
export type NamdTriggerKey =
  // SHORTEN triggers (8)
  | 'DE_NOVO_IRF'
  | 'IRF_INCREASE'
  | 'IRF_DECREASE_INSUFFICIENT'
  | 'DE_NOVO_CENTRAL_SRF'
  | 'CENTRAL_SRF_INCREASE'
  | 'SRF_RING_1_3_INCREASE'
  | 'NEW_HEMORRHAGE'
  | 'BCVA_LOSS_5_LETTERS'
  // KEEP triggers (4)
  | 'RESIDUAL_IRF_HALVED'
  | 'RESIDUAL_IRF_STABLE'
  | 'CENTRAL_SRF_IMPROVING'
  | 'ACTIVITY_IMPROVING'
  // EXTEND-eligibility conditions (4)
  | 'IRF_ABSENT'
  | 'CENTRAL_SRF_ABSENT'
  | 'NO_HEMORRHAGE_OR_BCVA_LOSS'
  | 'SRF_ISOLATED_1_3_STABLE'

/** A single fired trigger. The UI renders one row per hit. */
export interface NamdTriggerHit {
  key: NamdTriggerKey
  /** Verdict bucket — drives the chip colour in NamdRecommendationCard. */
  bucket: 'SHORTEN' | 'KEEP' | 'EXTEND'
  /** Measured value that caused the trigger (e.g. delta in nL). Null for boolean-only triggers. */
  value: number | null
  /** Threshold the value crossed. Null for boolean-only triggers. */
  threshold: number | null
}

export interface NamdAiRecommendation {
  /**
   * Recommendation classification. SHORTEN / KEEP / EXTEND maps to the
   * three interval-adjustment buckets in the treat-and-extend protocol.
   * The doctor's chosen TREAT/OBSERVE action stays a separate concern
   * captured by the decision panel.
   */
  rec: 'SHORTEN' | 'KEEP' | 'EXTEND'
  /** Suggested next interval (weeks). */
  intervalWeeks: number
  /** One-line rationale string, German — derived from the top-priority trigger. */
  rationale: string
  /**
   * 2026-06-30 — every fired trigger, in priority order
   * (SHORTEN first, then KEEP, then EXTEND-eligibility). The UI
   * shows ALL fired triggers so the rec is explainable. The
   * top-priority trigger drives {@link rec} + {@link rationale}.
   */
  triggersFired: NamdTriggerHit[]
}

/**
 * 2026-06-30 — two-arm RCT cohort identifier. The backend
 * {@code RetinalResultsApiController.resolveSubjectArm} reads the
 * subject's {@code subject_group_map} membership in a group_class of
 * type Arm with a group name matching {@code "AI_SHOWN"} or
 * {@code "AI_HIDDEN"}. {@code 'study'} = AI panels visible; {@code 'control'}
 * = AI panels hidden + AI recommendation suppressed; {@code null} =
 * subject isn't in either arm (defensive — workspace falls back to
 * the control-arm presentation).
 */
export type NamdSubjectArm = 'study' | 'control' | null

export interface NamdWorkspaceData {
  patient: NamdPatient
  visits: NamdVisit[]
  current: NamdVisit | null
  prev: NamdVisit | null
  ai: NamdAiRecommendation | null
  /** Total OCT slices for the current visit — drives the BscanViewer slider. */
  nSlices: number | null
  /**
   * 2026-06-30 — subject's cohort assignment. The workspace uses this
   * to gate every AI panel (seg cards, trend chart, recommendation card,
   * delta bar, CST chip, report fluid columns) on the study arm.
   */
  subjectArm: NamdSubjectArm
}
