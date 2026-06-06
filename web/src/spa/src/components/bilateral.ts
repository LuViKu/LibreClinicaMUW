/**
 * Phase E.6 ophth-bilateral — bilateral item grouping helper.
 *
 * Pure detection logic, isolated from the Vue component so it can be
 * unit-tested without a wrapper mount.
 *
 * **Convention (clinician-facing).** OD = oculus dexter = patient's
 * right eye, which the clinician sees on their LEFT because they sit
 * face-to-face with the patient. OS = oculus sinister = patient's left
 * eye → renders on the clinician's RIGHT. OU = oculus uterque = both
 * eyes (a single bilateral measurement, not differentiated).
 *
 * Detection rule. An item participates in a bilateral row iff its OID
 * matches `^(OD|OS|OU)_(.+)$`. Items are grouped by the suffix in
 * declaration order: the first OD/OS/OU item bearing a given suffix
 * opens a row; subsequent items with the same suffix join it. OU items
 * collapse into a single-cell "Both eyes" row (so a study can drop in
 * a bilateral measurement without forcing a paired layout).
 *
 * Items that don't match the prefix fall through as a single row,
 * preserving the existing one-column layout for non-ophthalmology CRFs.
 */

import type { CrfItem } from '@/types/crf'

const EYE_PREFIX_RE = /^(OD|OS|OU)_(.+)$/

/**
 * A row in the rendered section. Each row consumes one or more items
 * from the original {@code section.items} list.
 */
export type BilateralRow =
  | { kind: 'single'; item: CrfItem }
  | {
      kind: 'bilateral'
      /** Stable key — the shared OID suffix, e.g. {@code "BCVA_LETTERS"}. */
      key: string
      /** Human-readable row label, e.g. "BCVA letters". */
      label: string
      /** Right eye (renders on the LEFT of the table). */
      od: CrfItem | null
      /** Left eye (renders on the RIGHT of the table). */
      os: CrfItem | null
    }
  | {
      kind: 'both-eyes'
      /** Stable key — the shared OID suffix. */
      key: string
      label: string
      item: CrfItem
    }
  | {
      /**
       * Compound bilateral row — multiple sub-fields rendered inline on
       * each side of the OD/OS split. Used for measurement quartets
       * that clinicians enter on one line of a paper form (refraction:
       * Sphere · Torus · Angle · Visus is the canonical example).
       *
       * <p>Each sub-field still resolves to two independent
       * {@code CrfItem} rows in the data model (e.g. {@code
       * OD_REFRACTION_SPHERE} / {@code OS_REFRACTION_SPHERE}); the
       * compound row is purely a presentation grouping.
       */
      kind: 'compound-bilateral'
      /** Stable key — the compound prefix, e.g. {@code "REFRACTION"}. */
      key: string
      /** Human-readable row label, e.g. "Refraction". */
      label: string
      /** Sub-fields in declaration order. Each has an OD + OS slot. */
      subFields: Array<{
        /** The shared sub-suffix, e.g. {@code "SPHERE"}, {@code "TORUS"}. */
        subKey: string
        /** Compact label rendered above each sub-input (e.g. "Sph", "Tor"). */
        compactLabel: string
        /** Right eye sub-item — renders LEFT. null if no OD pair shipped. */
        od: CrfItem | null
        /** Left eye sub-item — renders RIGHT. null if no OS pair shipped. */
        os: CrfItem | null
      }>
    }

/**
 * Returns the {@code OD}/{@code OS}/{@code OU} prefix and shared
 * suffix for an item whose OID participates in the bilateral
 * convention, or {@code null} otherwise.
 */
export function parseEyePrefix(oid: string): { eye: 'OD' | 'OS' | 'OU'; suffix: string } | null {
  const m = EYE_PREFIX_RE.exec(oid)
  if (!m) return null
  return { eye: m[1] as 'OD' | 'OS' | 'OU', suffix: m[2] }
}

/**
 * Strip a leading or trailing eye marker from an item label so the
 * derived row label reads naturally. We're permissive about
 * separators: "OD — BCVA letters", "BCVA letters (OD)", "OD BCVA",
 * etc. all reduce to "BCVA letters" / "BCVA". Case-insensitive.
 */
export function stripEyeMarker(label: string): string {
  return label
    // Leading "OD ", "OD: ", "OD — ", "OD - ", "OD_"
    .replace(/^\s*(OD|OS|OU)\s*[—\-:_]?\s+/i, '')
    // Trailing " (OD)", " — OD", " - OS", " OS"
    .replace(/\s*[—\-:(]?\s*(OD|OS|OU)\s*\)?\s*$/i, '')
    .trim()
}

/**
 * Derive a row label that reads cleanly when both eyes share the same
 * underlying measurement. Strategy: prefer a cleaned form of the
 * item's own label; if both sides share the cleaned form, use it; if
 * they diverge (or the cleaning leaves an empty string), fall back to
 * the OID suffix with underscores expanded to spaces.
 */
export function deriveRowLabel(
  suffix: string,
  od: CrfItem | null,
  os: CrfItem | null,
): string {
  const odClean = od ? stripEyeMarker(od.label) : ''
  const osClean = os ? stripEyeMarker(os.label) : ''

  if (odClean && osClean) {
    if (odClean.localeCompare(osClean, undefined, { sensitivity: 'accent' }) === 0) {
      return odClean
    }
    // Labels diverge — fall through to suffix-based label so a typo on
    // one side doesn't surface a misleading "this row is BCVA letters".
  } else if (odClean) {
    return odClean
  } else if (osClean) {
    return osClean
  }

  return suffix.replace(/_/g, ' ').trim()
}

/**
 * Compound-prefix registry. When an OID suffix starts with one of these
 * prefixes (e.g. {@code REFRACTION_SPHERE}, {@code REFRACTION_TORUS}),
 * the four-or-more sub-items collapse into a single
 * {@code 'compound-bilateral'} row that renders all sub-inputs inline
 * per eye — matching the way clinicians write the measurement on paper
 * (e.g. refraction: Sphere · Torus · Angle · Visus on one line).
 *
 * <p>The compact label is what the renderer puts above each sub-input
 * to save horizontal space; the main row label is derived from the
 * prefix itself.
 */
const COMPOUND_PREFIX_REGISTRY: Record<
  string,
  { mainLabel: string; compactBySubKey: Record<string, string> }
> = {
  REFRACTION: {
    mainLabel: 'Refraction',
    compactBySubKey: {
      SPHERE: 'Sph',
      TORUS: 'Tor',
      CYLINDER: 'Tor', // legacy data with cylinder suffix maps to the same compact slot
      ANGLE: 'Ang',
      AXIS: 'Ang',
      VISUS: 'Vis',
    },
  },
}

function parseCompoundSuffix(suffix: string): { prefix: string; subKey: string } | null {
  for (const prefix of Object.keys(COMPOUND_PREFIX_REGISTRY)) {
    if (suffix.startsWith(`${prefix}_`)) {
      return { prefix, subKey: suffix.slice(prefix.length + 1) }
    }
  }
  return null
}

/**
 * Group a section's items into bilateral rows. The output order
 * follows the first item of each row in the input — so an authoring
 * tool that interleaves OD/OS items still produces a stable row
 * sequence.
 */
export function groupBilateralItems(items: CrfItem[]): BilateralRow[] {
  const rows: BilateralRow[] = []
  const indexBySuffix = new Map<string, number>()
  const compoundRowIndexByPrefix = new Map<string, number>()

  for (const item of items) {
    const parsed = parseEyePrefix(item.oid)
    if (!parsed) {
      rows.push({ kind: 'single', item })
      continue
    }

    const { eye, suffix } = parsed
    const compound = parseCompoundSuffix(suffix)

    // ----- Compound-bilateral path (refraction et al.) -----
    if (compound && eye !== 'OU') {
      const { prefix, subKey } = compound
      let rowIdx = compoundRowIndexByPrefix.get(prefix)
      if (rowIdx === undefined) {
        const registry = COMPOUND_PREFIX_REGISTRY[prefix]!
        rows.push({
          kind: 'compound-bilateral',
          key: prefix,
          label: registry.mainLabel,
          subFields: [],
        })
        rowIdx = rows.length - 1
        compoundRowIndexByPrefix.set(prefix, rowIdx)
      }
      const row = rows[rowIdx] as Extract<BilateralRow, { kind: 'compound-bilateral' }>
      const compactLabel = COMPOUND_PREFIX_REGISTRY[prefix]!.compactBySubKey[subKey] ?? subKey
      let sub = row.subFields.find((s) => s.subKey === subKey)
      if (!sub) {
        sub = { subKey, compactLabel, od: null, os: null }
        row.subFields.push(sub)
      }
      if (eye === 'OD' && !sub.od) sub.od = item
      else if (eye === 'OS' && !sub.os) sub.os = item
      else rows.push({ kind: 'single', item }) // duplicate — surface so data isn't silently dropped
      continue
    }

    // ----- Regular bilateral path (BCVA, IOP, OCT-done, etc.) -----
    const existingIdx = indexBySuffix.get(suffix)

    if (existingIdx === undefined) {
      // First time we see this suffix — open a row scaffold based on
      // the type of the current item.
      if (eye === 'OU') {
        rows.push({
          kind: 'both-eyes',
          key: suffix,
          label: deriveRowLabel(suffix, null, null) || stripEyeMarker(item.label) || suffix,
          item,
        })
      } else {
        const od: CrfItem | null = eye === 'OD' ? item : null
        const os: CrfItem | null = eye === 'OS' ? item : null
        rows.push({
          kind: 'bilateral',
          key: suffix,
          label: deriveRowLabel(suffix, od, os),
          od,
          os,
        })
      }
      indexBySuffix.set(suffix, rows.length - 1)
      continue
    }

    // We already have a row for this suffix — fill in the missing
    // side if the new item completes a pair.
    const row = rows[existingIdx]

    if (row.kind === 'bilateral') {
      if (eye === 'OD' && !row.od) {
        row.od = item
        row.label = deriveRowLabel(suffix, row.od, row.os)
      } else if (eye === 'OS' && !row.os) {
        row.os = item
        row.label = deriveRowLabel(suffix, row.od, row.os)
      } else {
        // Either a duplicate (OD twice) or an OU joining an OD/OS pair.
        // Surface duplicates as their own row so we don't silently drop
        // data — the operator will see two visually identical entries
        // and can pick the right one. The label gets a disambiguating
        // suffix-of-the-oid tail so they're not byte-identical.
        rows.push({ kind: 'single', item })
      }
    } else if (row.kind === 'both-eyes') {
      // OU is already taking the slot — OD/OS arriving late means the
      // CRF mixes paired + unpaired forms for the same measurement.
      // Render as their own row to keep the data visible.
      rows.push({ kind: 'single', item })
    } else {
      rows.push({ kind: 'single', item })
    }
  }

  return rows
}
