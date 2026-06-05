/**
 * Phase E.6 Data Export — Phase 3 (filters) wire types.
 *
 * Mirrors the records published by
 * {@code DatasetsApiController.DatasetFilterDto/FilterTestResult/CreateDatasetRequest}.
 *
 * Until Phase 1 + Phase 2 land the create-dataset surface upstream,
 * Phase 3 publishes these here so the SPA can be developed against
 * the real {@code :test-filter} backend without depending on the
 * openapi-typescript generator running against an in-progress merge
 * graph. Phase 2's wizard PR will pivot {@link CreateDatasetRequest}
 * to consume the generated record once both phases meet on
 * {@code lc-develop}.
 */

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
