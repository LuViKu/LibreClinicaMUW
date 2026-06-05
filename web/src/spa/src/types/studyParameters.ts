/**
 * Phase E.6 study-params — wire types for the
 * {@code /pages/api/v1/studies/{oid}/parameters} endpoint pair.
 *
 * Mirrors the backend {@code StudyParametersDto} (19 fields) and
 * {@code UpdateStudyParametersRequest} (18 fields, every field
 * nullable). Values are persisted as strings in
 * {@code study_parameter_value.value} ({@code varchar(50)}) and we
 * pass them through verbatim so the SPA settings panel can render
 * them via the i18n enum keys without a typed-union proliferation.
 *
 * <h3>Enum vocabularies (mirrors controller allow-lists)</h3>
 *
 * <ul>
 *   <li>{@code subjectIdGeneration}: {@code "manual" | "auto non-editable" | "auto editable"}</li>
 *   <li>{@code collectDob}: {@code "1" | "2" | "3"} (full DOB / year-only / none)</li>
 *   <li>{@code *Required}: {@code "required" | "optional" | "not_used"}</li>
 *   <li>{@code *Default}: {@code "blank" | "pre-populated"}</li>
 *   <li>{@code *Editable / *PrefixSuffix / personIdShownOnCRF /
 *       genderRequired / discrepancyManagement / secondaryLabelViewable /
 *       adminForcedReasonForChange}: {@code "true" | "false"}</li>
 *   <li>{@code participantPortal}, {@code randomization}: {@code "enabled" | "disabled"}</li>
 * </ul>
 *
 * <h3>Field-visibility consumers</h3>
 *
 * The downstream {@code AddSubjectView} / {@code CrfEntryView} clusters
 * read this store to drive form layout — e.g. {@code collectDob==='3'}
 * hides the DOB picker, {@code discrepancyManagement==='false'} hides
 * the DN affordance, and {@code interviewDateDefault==='pre-populated'}
 * seeds the CRF header. Those rewires land in their respective
 * clusters (deferred for this slice — see playbook §3.2 sequencing).
 */
export interface StudyParameters {
  studyOid: string
  subjectIdGeneration: string
  subjectIdPrefixSuffix: string
  subjectPersonIdRequired: string
  personIdShownOnCRF: string
  collectDob: string
  genderRequired: string
  eventLocationRequired: string
  discrepancyManagement: string
  interviewerNameRequired: string
  interviewerNameDefault: string
  interviewerNameEditable: string
  interviewDateRequired: string
  interviewDateDefault: string
  interviewDateEditable: string
  secondaryLabelViewable: string
  adminForcedReasonForChange: string
  participantPortal: string
  randomization: string
}

/**
 * Partial-patch shape for PUT. Every field nullable; null = leave
 * untouched on the server. Empty strings are rejected with a 400
 * — pass {@code null} to no-op a handle.
 */
export type UpdateStudyParametersInput = Partial<
  Omit<StudyParameters, 'studyOid'>
>

/** Allow-list constants — exported so views can build dropdowns. */
export const ALLOWED = {
  subjectIdGeneration: ['manual', 'auto non-editable', 'auto editable'] as const,
  subjectIdPrefixSuffix: ['true', 'false'] as const,
  requiredOptionalNotUsed: ['required', 'optional', 'not_used'] as const,
  bool: ['true', 'false'] as const,
  collectDob: ['1', '2', '3'] as const,
  blankPrepopulated: ['blank', 'pre-populated'] as const,
  enabledDisabled: ['enabled', 'disabled'] as const,
}
