/**
 * Phase E.6 polish-runtime — conditional-display ("show-when") evaluator.
 *
 * <p>Pure logic isolated from the runtime/preview stores so both can
 * import the same source of truth. Mirrors the backend's mixed wire
 * format ({@code CrfEntryDto.CrfItemDto#showWhen}):
 *
 * <ul>
 *   <li><b>JSON</b>: {@code {"sourceItemOid":"...","comparator":"==","literal":"..."}}.
 *       Emitted by SPA-authored items.</li>
 *   <li><b>OpenClinica legacy</b>: {@code "item_OID eq value"}.
 *       Emitted by spreadsheet-uploaded items. The {@code item_} prefix
 *       is optional — the source can be addressed by the bare OID or
 *       with the {@code item_} prefix the legacy wizard stamped.</li>
 * </ul>
 *
 * <p>{@code null} / {@code undefined} / blank means "always show".
 *
 * <p>Robustness rules per spec:
 * <ul>
 *   <li>Missing source value → rule resolves {@code false} for
 *       equality comparators (the item stays hidden until the source
 *       is filled), and {@code true} for inequality / range
 *       comparators that can't be meaningfully checked without a
 *       baseline.</li>
 *   <li>Dangling source item (OID not in the schema) → warn once +
 *       treat the rule as always-true (don't hide).</li>
 *   <li>Unparseable rule string → fail open (always-true).</li>
 * </ul>
 *
 * <p>Exported helpers are deliberately small + pure so the test
 * suite can hit them without hydrating a Pinia store.
 */

import type { CrfItem, CrfSchema, CrfValues } from '@/types/crf'

export type ShowWhenComparator = '==' | '!=' | '>' | '<' | '>=' | '<='

export interface ParsedShowWhen {
  sourceItemOid: string
  comparator: ShowWhenComparator
  literal: string
}

/**
 * Parse a show-when rule string. Returns {@code null} when the input
 * is blank or unparseable — callers treat that as "always show".
 *
 * <p>Accepts both the SPA JSON shape and the legacy
 * {@code "item_OID eq value"} string. Legacy comparators ({@code eq},
 * {@code ne}, {@code gt}, {@code lt}, {@code gte}, {@code lte})
 * normalise into the canonical {@link ShowWhenComparator} tokens.
 */
export function parseShowWhen(raw: string | null | undefined): ParsedShowWhen | null {
  if (raw == null) return null
  const trimmed = String(raw).trim()
  if (trimmed.length === 0) return null

  // JSON branch — the SPA's structured wire shape.
  if (trimmed.startsWith('{')) {
    try {
      const parsed = JSON.parse(trimmed) as Partial<ParsedShowWhen>
      const sourceItemOid = typeof parsed.sourceItemOid === 'string' ? parsed.sourceItemOid.trim() : ''
      if (sourceItemOid.length === 0) return null
      const cmp = normaliseComparator(parsed.comparator ?? '==')
      if (cmp == null) return null
      const literal = parsed.literal == null ? '' : String(parsed.literal)
      return { sourceItemOid, comparator: cmp, literal }
    } catch {
      return null
    }
  }

  // Legacy OpenClinica branch: "item_OID eq value", "item_OID ne value", etc.
  // The value may contain spaces, so split on the first run of
  // whitespace around the comparator.
  const legacyMatch = trimmed.match(/^\s*(\S+)\s+(eq|ne|gt|lt|gte?|lte?)\s+(.*)$/i)
  if (legacyMatch) {
    const rawSource = legacyMatch[1]
    const cmpToken = legacyMatch[2].toLowerCase()
    const literal = legacyMatch[3].trim()
    const sourceItemOid = stripItemPrefix(rawSource)
    if (sourceItemOid.length === 0) return null
    const cmp = legacyToCanonical(cmpToken)
    if (cmp == null) return null
    return { sourceItemOid, comparator: cmp, literal }
  }
  return null
}

function stripItemPrefix(raw: string): string {
  if (raw == null) return ''
  const t = raw.trim()
  if (t.toLowerCase().startsWith('item_')) {
    return t.slice('item_'.length)
  }
  return t
}

function legacyToCanonical(tok: string): ShowWhenComparator | null {
  switch (tok) {
    case 'eq': return '=='
    case 'ne': return '!='
    case 'gt': return '>'
    case 'lt': return '<'
    case 'gte':
    case 'ge': return '>='
    case 'lte':
    case 'le': return '<='
    default: return null
  }
}

function normaliseComparator(raw: string): ShowWhenComparator | null {
  const t = String(raw).trim()
  switch (t) {
    case '==':
    case '=':
    case 'eq': return '=='
    case '!=':
    case '<>':
    case 'ne': return '!='
    case '>':
    case 'gt': return '>'
    case '<':
    case 'lt': return '<'
    case '>=':
    case 'gte':
    case 'ge': return '>='
    case '<=':
    case 'lte':
    case 'le': return '<='
    default: return null
  }
}

/**
 * Build a one-shot lookup so the evaluator doesn't walk the section
 * list on every call. Items inside repeating groups are included via
 * their oid — show-when across rows isn't supported in v1 (the rule
 * always refers to a top-level item), so the lookup is flat.
 */
export function buildItemIndex(schema: CrfSchema | null | undefined): Map<string, CrfItem> {
  const out = new Map<string, CrfItem>()
  if (!schema) return out
  for (const section of schema.sections) {
    for (const item of section.items) {
      out.set(item.oid, item)
    }
  }
  return out
}

/**
 * Decide whether {@code item}'s show-when rule resolves to "hide me"
 * given the current {@code values}.
 *
 * @param item the item whose visibility we're asking about
 * @param values the current top-level value map
 * @param itemIndex a lookup of every schema item by OID; used to
 *                  detect dangling source-item references (fail open)
 * @param warned a Set carrying source OIDs we've already warned about
 *               so we don't spam the console; pass a per-store ref so
 *               warnings persist for the lifetime of the entry
 * @returns {@code true} when the item should be hidden
 */
export function isItemHiddenByRule(
  item: CrfItem,
  values: CrfValues,
  itemIndex: Map<string, CrfItem>,
  warned?: Set<string>,
): boolean {
  const parsed = parseShowWhen(item.showWhen)
  if (parsed == null) return false
  return !evaluateShowWhen(parsed, values, itemIndex, warned)
}

/**
 * Apply a parsed rule against the live values map. Returns {@code true}
 * when the rule evaluates to "show". Pure — no store or component
 * coupling so the test suite can hit it directly.
 */
export function evaluateShowWhen(
  rule: ParsedShowWhen,
  values: CrfValues,
  itemIndex: Map<string, CrfItem>,
  warned?: Set<string>,
): boolean {
  const source = itemIndex.get(rule.sourceItemOid)
  if (!source) {
    // Dangling reference — log once + fail open per spec.
    if (warned && !warned.has(rule.sourceItemOid)) {
      warned.add(rule.sourceItemOid)
      // eslint-disable-next-line no-console
      console.warn(
        `[showWhen] source item '${rule.sourceItemOid}' not found in schema — ` +
          `treating rule as always-true to avoid hiding the dependent item.`,
      )
    }
    return true
  }

  const raw = values[rule.sourceItemOid]
  const dataType = source.dataType

  // Missing source value: equality / inequality semantics per spec.
  if (raw == null || (typeof raw === 'string' && raw.trim().length === 0)) {
    switch (rule.comparator) {
      case '==':
        // Equality vs the empty literal still matches.
        return rule.literal == null || rule.literal.length === 0
      case '!=':
        return rule.literal != null && rule.literal.length > 0
      default:
        // For ordered comparisons (> < >= <=) without a source value,
        // there's nothing to compare against — treat as "rule resolves
        // false" i.e. hide. This matches the conservative semantics:
        // a "show when BCVA > 50" item stays hidden until BCVA is set.
        return false
    }
  }

  switch (rule.comparator) {
    case '==':
      return equalsTyped(raw, rule.literal, dataType)
    case '!=':
      return !equalsTyped(raw, rule.literal, dataType)
    case '>':
    case '<':
    case '>=':
    case '<=':
      return compareNumeric(raw, rule.literal, rule.comparator)
  }
}

function equalsTyped(raw: unknown, literal: string, dataType: CrfItem['dataType']): boolean {
  switch (dataType) {
    case 'integer':
    case 'real': {
      const a = Number(raw)
      const b = Number(literal)
      if (!Number.isFinite(a) || !Number.isFinite(b)) {
        // Fall back to string equality if either side isn't numeric.
        return String(raw) === literal
      }
      return a === b
    }
    case 'boolean': {
      const truthy = (v: unknown): boolean => {
        if (typeof v === 'boolean') return v
        if (typeof v === 'string') return ['true', '1', 'yes', 'y'].includes(v.trim().toLowerCase())
        if (typeof v === 'number') return v !== 0
        return false
      }
      return truthy(raw) === truthy(literal)
    }
    case 'date':
    case 'partial-date':
      // ISO strings compare lexicographically when both are well-formed.
      return String(raw).trim() === String(literal).trim()
    case 'select-multi': {
      // Array source vs scalar literal: literal matches if it's
      // included in the selection. Equality on the whole array is
      // expressed via comma-joined literal.
      if (Array.isArray(raw)) {
        if (literal.includes(',')) {
          const want = literal.split(',').map((s) => s.trim()).sort().join(',')
          const got = raw.map((v) => String(v).trim()).sort().join(',')
          return want === got
        }
        return raw.some((v) => String(v) === literal)
      }
      return String(raw) === literal
    }
    case 'select-one':
    case 'string':
    case 'file':
    default:
      return String(raw) === literal
  }
}

function compareNumeric(raw: unknown, literal: string, cmp: ShowWhenComparator): boolean {
  const a = Number(raw)
  const b = Number(literal)
  if (!Number.isFinite(a) || !Number.isFinite(b)) return false
  switch (cmp) {
    case '>': return a > b
    case '<': return a < b
    case '>=': return a >= b
    case '<=': return a <= b
    default: return false
  }
}
