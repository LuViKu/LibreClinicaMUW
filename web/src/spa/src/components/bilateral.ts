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
 * Detection rule. An OID participates in a bilateral row iff one of its
 * underscore-delimited tokens is exactly {@code OD}, {@code OS}, or
 * {@code OU}. The "pair key" is the OID with the eye token removed, so
 * {@code I_VA_OD_ETDRS} and {@code I_VA_OS_ETDRS} both reduce to
 * {@code I_VA_ETDRS} and join the same row. Items are grouped in
 * declaration order: the first OD/OS/OU item bearing a given pair key
 * opens a row; subsequent items with the same key join it. OU items
 * collapse into a single-cell "Both eyes" row (so a study can drop in
 * a bilateral measurement without forcing a paired layout).
 *
 * The first form (laterality as the LEADING token, e.g. {@code OD_BCVA})
 * was the original Phase E.6 convention and remains supported. The
 * second form (laterality as an INFIX token, e.g. {@code I_VA_OD_ETDRS})
 * matches the seeded Ophthalmology Visit CRF — both shapes resolve to
 * the same pair key.
 *
 * Items without an eye token fall through as a single row, preserving
 * the existing one-column layout for non-ophthalmology CRFs.
 */

import type { CrfItem } from '@/types/crf'

const EYE_TOKEN_RE = /^(OD|OS|OU)$/

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
 * Returns the {@code OD}/{@code OS}/{@code OU} marker and the pair key
 * (the OID with the eye token removed) for an item whose OID
 * participates in the bilateral convention, or {@code null} otherwise.
 *
 * <p>The {@code suffix} field is the pair key — two OIDs that produce
 * the same key + differ only in eye token join a single bilateral row.
 * The field is named "suffix" for backward compatibility with the
 * original leading-prefix convention ({@code OD_BCVA} → suffix=
 * {@code BCVA}); for an infix-token OID ({@code I_VA_OD_ETDRS}) the
 * "suffix" is the surrounding tokens joined ({@code I_VA_ETDRS}).
 *
 * <p>If an OID carries more than one eye token (degenerate authoring)
 * the FIRST occurrence is consumed.
 */
export function parseEyePrefix(oid: string): { eye: 'OD' | 'OS' | 'OU'; suffix: string } | null {
  const tokens = oid.split('_')
  // Require at least 2 tokens overall — `OD` alone, `OS` alone, etc.
  // shouldn't be treated as paired (matches the original
  // `^(OD|OS|OU)_(.+)$` regex's "must have something after the eye").
  if (tokens.length < 2) return null
  const eyeIdx = tokens.findIndex((t) => EYE_TOKEN_RE.test(t))
  if (eyeIdx === -1) return null
  const eye = tokens[eyeIdx] as 'OD' | 'OS' | 'OU'
  const remaining = [...tokens.slice(0, eyeIdx), ...tokens.slice(eyeIdx + 1)]
  // No surviving tokens after eye removal — e.g. literally {@code OD_}
  // — treat as non-paired so the empty key doesn't collide with other
  // empty keys.
  if (remaining.length === 0) return null
  return { eye, suffix: remaining.join('_') }
}

/**
 * Strip a leading or trailing eye marker from an item label so the
 * derived row label reads naturally. We're permissive about
 * separators: "OD — BCVA letters", "BCVA letters (OD)", "OD BCVA",
 * etc. all reduce to "BCVA letters" / "BCVA". Case-insensitive.
 */
export function stripEyeMarker(label: string): string {
  return label
    // Leading "Right eye (OD) — X", "Left eye (OS) - X", "Both eyes — X",
    // and the German equivalents ("Rechtes Auge (OD)", "Linkes Auge (OS)",
    // "Beide Augen"). Seeded Ophthalmology CRF labels use these forms
    // verbatim — strip them so the row label collapses to the shared
    // measurement name on both sides.
    .replace(
      /^\s*(Right\s+eye|Left\s+eye|Both\s+eyes|Rechtes\s+Auge|Linkes\s+Auge|Beide\s+Augen)\s*[(\[]?\s*(OD|OS|OU)?\s*[)\]]?\s*[—\-:]\s*/i,
      '',
    )
    .replace(
      /^\s*(Right\s+eye|Left\s+eye|Both\s+eyes|Rechtes\s+Auge|Linkes\s+Auge|Beide\s+Augen)\s*[(\[]?\s*(OD|OS|OU)?\s*[)\]]?\s+/i,
      '',
    )
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

  // Suffix fallback. Strip the conventional item-namespace prefix
  // tokens ("I_", "I_OPHTH_", "I_VA_", etc.) so the surfaced label
  // reads "BCVA LETTERS" rather than "I OPHTH BCVA LETTERS" — the
  // user-facing row label should not leak the OID's authoring scheme.
  const namespaceStrippedSuffix = suffix.replace(/^(I_)?(OPHTH_|VA_|REFRACT_|IOP_|LENS_|VISION_|TONO_)?/i, '')
  return (namespaceStrippedSuffix || suffix).replace(/_/g, ' ').trim()
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
  // Seeded Ophthalmology CRF uses the shorter REFRACT_OD_SPH /
  // REFRACT_OS_CYL convention (lc-muw-2026-06-05-ophth-visit-crf-seed.xml).
  // After parseEyePrefix strips the OD/OS token the pair key reads as
  // I_REFRACT_SPH — the compound prefix that matches is therefore
  // I_REFRACT (the leading I_ is part of the OID's "item" namespace).
  I_REFRACT: {
    mainLabel: 'Refraktion',
    compactBySubKey: {
      SPH: 'Sphäre',
      CYL: 'Zylinder',
      AXIS: 'Achse',
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
