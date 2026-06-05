/**
 * Phase E.6 — Data Export wire types.
 *
 * Wire shape of the {@code /pages/api/v1/datasets} surface. The SPA
 * surfaces the legacy ExportDataset stack as a saved-dataset list +
 * per-dataset format picker + per-dataset file table (Phase 1 MVP)
 * and a wizard-driven filter authoring surface (Phase 3).
 *
 * The MVP intentionally re-uses the legacy `dataset` /
 * `archived_dataset_file` / `export_format` tables — no schema
 * changes — and refers to a dataset by its numeric primary key.
 * The `oid` field on {@link DatasetDto} is the same id rendered as a
 * string so the SPA can keep its "everything has an OID" convention
 * for future evolution (the legacy schema has no OID column on
 * dataset rows).
 *
 * Phase 3 (filters) overlays a per-item value-predicate vocabulary
 * mirroring the backend's
 * {@code DatasetsApiController.DatasetFilterDto/FilterTestResult/CreateDatasetRequest}
 * records. Until openapi-typescript can regenerate against the merged
 * backend, the Phase 3 wire shapes are hand-mirrored here.
 */

/* ------------------------------------------------------------------ */
/*  Phase 1 — saved-dataset list + export trigger                     */
/* ------------------------------------------------------------------ */

/** One saved dataset row in the SPA's table. */
export interface DatasetDto {
  /** Dataset id as a string — stable handle for SPA. */
  oid: string
  /** Dataset id as the numeric primary key. */
  id: number
  name: string
  description: string | null
  /** Username of the operator who saved the dataset, or null if removed. */
  ownerName: string | null
  /** ISO-8601 UTC. */
  dateCreated: string | null
  /** ISO-8601 UTC, or null when the dataset has never been exported. */
  lastRunAt: string | null
  /** Number of {@code archived_dataset_file} rows currently on disk. */
  fileCount: number
  /* ------------------------------------------------------------------ */
  /* Phase 2 wizard hydration — populated only by the single-fetch +    */
  /* create/update echo paths; left empty on the list endpoint to       */
  /* avoid N+1 SQL on the saved-datasets table view.                    */
  /* ------------------------------------------------------------------ */
  studyId?: number
  /** Legacy Status enum name — "available" / "removed" / etc. */
  status?: string
  eventDefinitionOids?: string[]
  crfVersionIds?: number[]
  itemIds?: number[]
  includeFlags?: InclusionFlags
  numRuns?: number
  /** True when {@code numRuns > 0}. Wizard disables structural edits. */
  hasRun?: boolean
}

/** One generated export file row in the SPA's per-dataset sub-table. */
export interface ArchivedFileDto {
  /** archived_dataset_file.id */
  id: number
  /** Zip / xls filename. */
  name: string
  /** Pretty label — "ODM", "CSV", "Excel", "TXT", "PDF", "Other". */
  formatName: string
  /** Raw byte size on disk. */
  sizeBytes: number
  /** ISO-8601 UTC. */
  generatedAt: string | null
  /** Relative URL the SPA hits to stream the file. */
  downloadUrl: string
}

/**
 * Export formats the SPA's format picker offers. Maps 1:1 to the
 * backend's {@code DatasetsApiController.ExportFormatKey} enum.
 */
export type ExportFormat = 'odm' | 'csv' | 'tsv' | 'excel' | 'sas' | 'spss'

/** Wire shape of {@code POST /datasets/{id}/export}. */
export interface ExportTriggerRequest {
  format: ExportFormat
}

/** Wire shape of the export-trigger response. */
export interface ExportTriggerResponse {
  archivedDatasetFileId: number
  downloadUrl: string
}

/* ------------------------------------------------------------------ */
/*  Phase 3 — filters (:test-filter preview)                          */
/* ------------------------------------------------------------------ */

/**
 * Per-item value predicate. Mirrors the backend's
 * {@code DatasetFilterDto} — {@code value} is the scalar (for the
 * comparison operators), {@code values} is the list (for
 * {@code in}) or the two-tuple (for {@code between}).
 */
export interface DatasetFilterDto {
  /** OID of the item the predicate sits on. */
  itemOid: string
  /** Operator the predicate uses. See {@link FilterOperator}. */
  operator: FilterOperator
  /** Scalar value; only meaningful for the comparison operators. */
  value?: string | null
  /**
   * List of values; populated for {@code in} (one or more) and
   * {@code between} (exactly two — low, high).
   */
  values?: string[]
}

/**
 * Operators surfaced by the Phase 3 wire.
 *
 * Order matches the backend's {@code KNOWN_OPS}; the SPA's operator
 * selector mirrors it so the UI + the persisted SQL stay in lock-
 * step.
 */
export type FilterOperator =
  | '='
  | '!='
  | '<'
  | '<='
  | '>'
  | '>='
  | 'in'
  | 'between'
  | 'is-null'
  | 'not-null'

/**
 * Logical groups the SPA filters operators by when rendering the
 * operator selector for a given item dataType. The classifications
 * mirror the backend's {@code NUMERIC_ONLY_OPS} + {@code UNARY_OPS}
 * sets.
 */
export const NUMERIC_ONLY_OPERATORS: ReadonlyArray<FilterOperator> = [
  '<', '<=', '>', '>=', 'between',
]

export const UNARY_OPERATORS: ReadonlyArray<FilterOperator> = [
  'is-null', 'not-null',
]

export const LIST_OPERATORS: ReadonlyArray<FilterOperator> = ['in']

/**
 * Result of {@code POST /datasets/{id}:test-filter}. The wizard's
 * inline preview renders "matchingSubjects of totalSubjects ·
 * matchingCrfs of totalCrfs".
 */
export interface FilterTestResult {
  matchingSubjects: number
  matchingCrfs: number
  totalSubjects: number
  totalCrfs: number
}

/**
 * Minimal item shape FilterBuilder needs — Phase 2's tree picker
 * populates this from the selected-items list. Only the fields the
 * builder reads are typed; Phase 2's full {@code ItemDto} extends
 * this without breaking the builder.
 */
export interface FilterItemDto {
  oid: string
  /** Human-friendly label (left-item-text or item name). */
  label: string
  /**
   * Storage type — used to filter the operator selector to the
   * type-appropriate subset (numeric/date ordering ops only on
   * {@code INTEGER}/{@code REAL}/{@code DATE}/{@code PDATE} items).
   *
   * Matches the backend's {@code ItemDataType.getName()} values
   * ({@code integer}, {@code floating}, {@code date},
   * {@code partial_date}, {@code character_string},
   * {@code Boolean}, ...).
   */
  dataType: string
}

/**
 * Phase 2 + Phase 3 — the full create-dataset payload sent to
 * {@code POST /api/v1/studies/{oid}/datasets} +
 * {@code PUT /api/v1/datasets/{id}}.
 *
 * <ul>
 *   <li>{@code name} / {@code description} — dataset metadata.</li>
 *   <li>{@code eventDefinitionOids} / {@code crfVersionIds} /
 *       {@code itemIds} / {@code includeFlags} — Phase 2 wizard
 *       payload (required by the create-dataset wizard).</li>
 *   <li>{@code selectedItemOids} / {@code filters} — Phase 3 filter
 *       carry-over (optional; reserved for Phase 4 async-export).</li>
 * </ul>
 */
export interface CreateDatasetRequest {
  name: string
  description: string
  eventDefinitionOids: string[]
  crfVersionIds: number[]
  itemIds: number[]
  includeFlags: InclusionFlags
  /** Phase 3 — items the filter builder may reference. Optional. */
  selectedItemOids?: string[]
  /** Phase 3 — filter predicate list. Optional. */
  filters?: DatasetFilterDto[]
}

/* ------------------------------------------------------------------ */
/*  Phase 2 — wizard shapes (event tree, inclusion flags, draft)      */
/* ------------------------------------------------------------------ */

/** Returned by {@code GET /api/v1/studies/{oid}/event-tree}. */
export interface EventTreeNode {
  eventOid: string
  eventName: string
  eventOrdinal: number
  repeating: boolean
  crfs: EventTreeCrfNode[]
}

export interface EventTreeCrfNode {
  crfOid: string
  crfName: string
  versions: EventTreeVersionNode[]
}

export interface EventTreeVersionNode {
  versionId: number
  versionOid: string
  versionName: string
  items: EventTreeItemNode[]
}

export interface EventTreeItemNode {
  itemId: number
  oid: string
  name: string
  dataType: string
}

/**
 * Phase 2 — inclusion flags, grouped semantically into 5 sections.
 *
 * The legacy DatasetBean carries 35 boolean columns. The MUW
 * deployment exercises 18 of them; the remaining 17 are either
 * deprecated or duplicated by other columns and were never used by
 * the OpenClinica 3.x CreateDataset JSP either. See
 * {@code DatasetsApiController.applyFlags} for the controller-side
 * mapping. Adding a flag here:
 *   1. add the key to {@link InclusionFlags},
 *   2. add it to {@link FLAG_DEFAULTS},
 *   3. add it to the appropriate FLAG_SECTION group,
 *   4. teach the controller's applyFlags + toDto round-trip.
 */
export interface InclusionFlags {
  // Subject metadata
  dob: boolean
  gender: boolean
  subjectStatus: boolean
  uniqueIdentifier: boolean
  secondaryId: boolean
  ageAtEvent: boolean
  groupInformation: boolean
  // Event metadata
  eventLocation: boolean
  eventStartDate: boolean
  eventEndDate: boolean
  eventStartTime: boolean
  eventEndTime: boolean
  eventStatus: boolean
  // CRF / audit
  crfStatus: boolean
  crfVersion: boolean
  interviewerName: boolean
  interviewerDate: boolean
  completionDate: boolean
  // Discrepancy notes
  discNotes: boolean
}

export type InclusionFlagKey = keyof InclusionFlags

/**
 * MUW operator defaults — the create-dataset wizard pre-checks the
 * flags most clinical reviewers rely on. The operator can untoggle
 * any of them on the Inclusion-flags step.
 */
export const FLAG_DEFAULTS: InclusionFlags = {
  dob: false,
  gender: true,
  subjectStatus: true,
  uniqueIdentifier: true,
  secondaryId: false,
  ageAtEvent: true,
  groupInformation: false,

  eventLocation: false,
  eventStartDate: true,
  eventEndDate: false,
  eventStartTime: false,
  eventEndTime: false,
  eventStatus: true,

  crfStatus: true,
  crfVersion: true,
  interviewerName: false,
  interviewerDate: false,
  completionDate: false,

  discNotes: false,
}

/**
 * Semantic grouping of the inclusion flags into the five collapsible
 * sections rendered by {@code InclusionFlagsForm}. Order is the order
 * the wizard renders them in.
 */
export interface FlagSection {
  /** i18n key under {@code createDataset.flagSection.<id>.title}. */
  id: 'subject' | 'event' | 'crf' | 'interviewer' | 'notes'
  keys: InclusionFlagKey[]
}

export const FLAG_SECTIONS: FlagSection[] = [
  {
    id: 'subject',
    keys: [
      'dob',
      'gender',
      'subjectStatus',
      'uniqueIdentifier',
      'secondaryId',
      'ageAtEvent',
      'groupInformation',
    ],
  },
  {
    id: 'event',
    keys: [
      'eventLocation',
      'eventStartDate',
      'eventEndDate',
      'eventStartTime',
      'eventEndTime',
      'eventStatus',
    ],
  },
  {
    id: 'crf',
    keys: ['crfStatus', 'crfVersion'],
  },
  {
    id: 'interviewer',
    keys: ['interviewerName', 'interviewerDate', 'completionDate'],
  },
  {
    id: 'notes',
    keys: ['discNotes'],
  },
]

/**
 * Wire shape of {@code POST /api/v1/studies/{oid}/datasets} +
 * {@code PUT /api/v1/datasets/{id}} — alias for {@link CreateDatasetRequest}
 * kept distinct so the wizard's draft type can re-shape later without
 * disturbing the Phase 3 filter wire.
 */
export type CreateDatasetInput = CreateDatasetRequest

/**
 * The wizard keeps its in-flight selection state in the store as a
 * single draft. Cancelling the wizard clears the draft; navigating
 * between steps preserves it.
 */
export interface CreateDatasetDraft extends CreateDatasetInput {
  /** Truthy when editing an existing dataset; undefined for create. */
  editingDatasetId?: number
  /** Wizard step index (0-based). */
  step: number
}

/** Default-empty draft seed. */
export const EMPTY_DRAFT: CreateDatasetDraft = {
  name: '',
  description: '',
  eventDefinitionOids: [],
  crfVersionIds: [],
  itemIds: [],
  includeFlags: { ...FLAG_DEFAULTS },
  step: 0,
}

/**
 * Wire shape for the 400 validation error body returned by the
 * wizard's create / update endpoints. Mirrors
 * {@code SubjectsApiController.ValidationErrorBody}.
 */
export interface DatasetValidationError {
  message: string
  errors: Array<{ field: string; message: string }>
}

/**
 * Returns true when the operator is type-appropriate for the supplied
 * item dataType. Used by FilterBuilder to filter the operator
 * selector and by the SPA-side validation that pre-flights what the
 * backend will accept.
 */
export function operatorAcceptsDataType(op: FilterOperator, dataType: string): boolean {
  if (UNARY_OPERATORS.includes(op)) return true
  if (!NUMERIC_ONLY_OPERATORS.includes(op)) return true
  // numeric/date ordering ops require a numeric/date item
  const normalized = dataType.toLowerCase()
  return (
    normalized === 'integer' ||
    normalized === 'floating' ||
    normalized === 'date' ||
    normalized === 'partial_date'
  )
}

/**
 * Returns the operator subset valid for an item of the given
 * dataType. Order is preserved from the canonical operator list so
 * the SPA selector renders consistently across item types.
 */
export function operatorsForDataType(dataType: string): FilterOperator[] {
  const all: FilterOperator[] = [
    '=', '!=', '<', '<=', '>', '>=', 'in', 'between', 'is-null', 'not-null',
  ]
  return all.filter((op) => operatorAcceptsDataType(op, dataType))
}
