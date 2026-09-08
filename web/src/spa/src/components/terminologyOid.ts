/**
 * #26 Slice 3 — persist a repeating-table text column's terminology
 * autocomplete binding by encoding the code system in the item OID.
 *
 * <p>The OID round-trips through save → reload → live entry for free (it's
 * the item's identity — no backend column, adapter, or schema change needed),
 * mirroring the existing ophthalmology OID-suffix widget heuristics
 * ({@code *_BCVA_LETTERS}, {@code *_IOP}, {@code *_DONE}) that
 * {@code CrfItemWidget} already keys on. At data entry
 * {@code RepeatingGroupSection} detects the marker and renders
 * {@code TerminologyAutocomplete} against the resolved system.
 *
 * <p>Scope: only the SYSTEM is persisted this way. The property→field fill
 * map is NOT encodable in an OID and stays preview-only (a persisted table's
 * autocomplete assists lookup at entry but doesn't auto-fill sibling cells —
 * that's a further follow-up). Column OIDs are SPA-generated (the operator
 * never types them), so appending a marker is safe.
 */

/** system → OID suffix. Add a row here to persist a new catalogue binding. */
const SUFFIX_BY_SYSTEM: Record<string, string> = {
  medication: '_TXMED',
  icd10gm: '_TXICD',
}

const SYSTEM_BY_CODE: Record<string, string> = {
  MED: 'medication',
  ICD: 'icd10gm',
}

/** Append the terminology marker for {@code system}; unknown systems pass through. */
export function markTerminologyOid(oid: string, system: string | undefined | null): string {
  if (!system) return oid
  const suffix = SUFFIX_BY_SYSTEM[system]
  return suffix ? oid + suffix : oid
}

/** Resolve the terminology system encoded in an OID, or null when unmarked. */
export function terminologySystemFromOid(oid: string | undefined | null): string | null {
  if (!oid) return null
  const m = /_TX(MED|ICD)$/.exec(oid)
  return m ? (SYSTEM_BY_CODE[m[1]!] ?? null) : null
}
