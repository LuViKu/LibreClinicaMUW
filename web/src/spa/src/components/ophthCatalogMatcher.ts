/**
 * Phase E.6 ophth-field-catalog (2026-06-11) — pure OID → catalog-entry
 * matcher.
 *
 * <p>Maps an item OID to its matching {@link OphthFieldCatalogEntry} by
 * stripping the laterality token + then comparing the OID's trailing
 * tokens to each catalog code. Pure function, no store dependency, so
 * the resolver can be unit-tested without a Pinia mount.
 *
 * <h2>Matching strategy</h2>
 *
 * <ol>
 *   <li>Tokenise the OID on {@code _}. Drop any token exactly equal to
 *       {@code OD}, {@code OS}, or {@code OU} — the laterality marker.</li>
 *   <li>Iterate catalog entries. For each entry's {@code code}, check
 *       if the OID's de-lateralised token sequence ends with the
 *       catalog code's token sequence. The first match wins.</li>
 *   <li>Compound codes like {@code REFRACTION} match the row's
 *       compound prefix (the shared OID tail that defines the row),
 *       not the individual sub-field items — the bilateral grouper
 *       already collapses sub-fields into a single compound row, and
 *       this resolver is called with the row's compound-key OID rather
 *       than the per-sub-field OID.</li>
 *   <li>Returns {@code null} when no catalog code matches. The caller
 *       (CrfItemWidget) then falls back to its existing OID heuristic
 *       so the form keeps rendering — the catalog is additive over
 *       the heuristic, not a replacement.</li>
 * </ol>
 *
 * <h2>Why suffix-token-tail matching</h2>
 *
 * <p>The OPHTH v2.0 CRF uses OIDs like {@code I_OPHTH_OD_BCVA_LETTERS}
 * — the eye token sits in the middle + the catalog code shows up as
 * the trailing tokens. We can't rely on a full string match because
 * the namespace prefix ({@code I_OPHTH}) drifts between studies. The
 * tail-token comparison is robust against namespace evolution while
 * still being deterministic.
 */

import type { OphthFieldCatalogEntry } from '@/types/ophthFieldCatalog'

const EYE_TOKEN_SET = new Set(['OD', 'OS', 'OU'])

/**
 * Tokenise an OID + strip the laterality marker. Exported for the
 * unit-test layer.
 */
export function delateralizeOid(oid: string): string[] {
  return oid
    .split('_')
    .filter((t) => t.length > 0 && !EYE_TOKEN_SET.has(t))
}

/**
 * True if {@code tokens} ends with the token sequence of {@code suffix}.
 * Comparison is case-sensitive — catalog codes are uppercase tokens
 * by convention.
 */
function endsWithTokens(tokens: string[], suffix: string[]): boolean {
  if (suffix.length === 0 || tokens.length < suffix.length) return false
  for (let i = 0; i < suffix.length; i++) {
    if (tokens[tokens.length - suffix.length + i] !== suffix[i]) return false
  }
  return true
}

/**
 * Find the catalog entry whose code matches the trailing tokens of
 * the given OID after laterality stripping. Returns {@code null} when
 * no entry matches; the caller's render path then falls back to its
 * heuristic.
 *
 * <p>When multiple entries could match (e.g. a future catalog adds
 * both {@code DONE} and {@code SPECTRALIS_OCT_DONE}), the LONGER
 * code wins — most specific binding takes precedence over generic.
 */
export function findCatalogEntryByOid(
  oid: string,
  catalog: OphthFieldCatalogEntry[],
): OphthFieldCatalogEntry | null {
  if (!oid || catalog.length === 0) return null
  const oidTokens = delateralizeOid(oid)
  if (oidTokens.length === 0) return null

  let best: OphthFieldCatalogEntry | null = null
  let bestLen = 0
  for (const entry of catalog) {
    const codeTokens = entry.code.split('_').filter((t) => t.length > 0)
    if (endsWithTokens(oidTokens, codeTokens)) {
      if (codeTokens.length > bestLen) {
        best = entry
        bestLen = codeTokens.length
      }
    }
  }
  return best
}
