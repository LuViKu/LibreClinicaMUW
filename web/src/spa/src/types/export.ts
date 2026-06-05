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
 * Phase 2 wire shape — full create-dataset payload. Published here
 * so Phase 3's {@code filters} field anchors the shared record
 * without circular type imports; Phase 2's wizard PR will extend
 * with the metadata-confirmation surface (label, units format, ...).
 */
export interface CreateDatasetRequest {
  name: string
  description: string
  selectedItemOids: string[]
  filters: DatasetFilterDto[]
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
