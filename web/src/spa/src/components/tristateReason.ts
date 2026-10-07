/**
 * TRISTATE_REASON pairing — which items are tri-state parents, and which
 * item holds the reason text that belongs to one.
 *
 * <p>A {@code TRISTATE_REASON} authoring item renders as a Ja / Nein /
 * Unbekannt radio group with a reason textarea revealed inline when the
 * operator picks "Nein". The reason text does NOT live on that item: it is
 * persisted to a SEPARATE sibling item, authored alongside the parent and
 * wired to it by a show-when rule
 * ({@code imagingAcquisitionPreset} emits {@code <EYE>_<DEVICE>_ACQUIRED}
 * plus {@code <EYE>_<DEVICE>_REASON} whose showWhen names the former).
 *
 * <p>Both halves of that relationship are needed in two places — the widget
 * that renders the inline textarea and the entry view that persists what is
 * typed into it and must not ALSO render the sibling as a row of its own.
 * Keeping the rules here, pure and free of Pinia, is the same arrangement
 * {@link ./showWhen} uses and stops the two sides drifting apart.
 *
 * <p>Before this module existed the widget owned the detection privately,
 * emitted the reason text as an event, and nothing listened: the sibling
 * rendered as a second, separate reason field, and whatever was typed into
 * the inline one was dropped when the view was left.
 */

import { parseShowWhen } from './showWhen'
import type { CrfItem } from '@/types/crf'

/** The catalog fields this module needs; kept structural so tests need no store. */
export interface TristateCatalogEntry {
  widget?: string | null
  conditionalOnCode?: string | null
}

/**
 * Whether {@code item} is a tri-state parent, i.e. renders the Ja / Nein /
 * Unbekannt radio group with its own inline reason textarea.
 *
 * <p>Mirrors the runtime widget's taxonomy exactly:
 * <ul>
 *   <li>a bound catalog entry wins, and no catalog widget maps to tri-state,
 *       so a catalogued item is never a tri-state parent;</li>
 *   <li>otherwise the {@code *_TRISTATE} OID suffix;</li>
 *   <li>otherwise a select-one carrying exactly three options, one of which
 *       is unbekannt / unknown / 2 — the shape the imaging presets and the
 *       legacy spreadsheet uploads both produce.</li>
 * </ul>
 */
export function isTristateParent(
  item: Pick<CrfItem, 'oid' | 'dataType' | 'options'>,
  catalogEntry?: TristateCatalogEntry | null,
): boolean {
  if (catalogEntry != null) return false

  const oid = (item.oid || '').toUpperCase()
  if (oid.endsWith('_TRISTATE')) return true

  const options = item.options
  if (item.dataType === 'select-one' && options != null && options.length === 3) {
    return options.some((o) => {
      const code = String((o as { code?: unknown }).code).toLowerCase()
      return code === 'unbekannt' || code === 'unknown' || code === '2'
    })
  }
  return false
}

/**
 * The OID of the item holding the reason text for {@code parent}, or
 * {@code null} when the schema pairs none with it.
 *
 * <p>Resolved from the show-when rule on the sibling rather than from an OID
 * naming convention: the rule is what the authoring tool actually writes, it
 * survives a renamed OID, and it is already on the wire. A parent with no
 * such sibling simply has no reason field, which is a legal CRF.
 *
 * <p>When several items point at the same parent — an authoring accident —
 * the first in schema order wins, so the choice is at least stable rather
 * than dependent on iteration order.
 */
export function findReasonSiblingOid(
  parent: Pick<CrfItem, 'oid'>,
  items: readonly Pick<CrfItem, 'oid' | 'showWhen'>[],
): string | null {
  for (const candidate of items) {
    if (candidate.oid === parent.oid) continue
    const rule = parseShowWhen(candidate.showWhen)
    if (rule != null && rule.sourceItemOid === parent.oid) return candidate.oid
  }
  return null
}

/**
 * Every reason-sibling OID in {@code items}, keyed by the tri-state parent
 * that owns it.
 *
 * <p>The entry view needs both directions: the parent's OID to route typed
 * text, and the set of sibling OIDs to leave out of the rendered rows so a
 * reason is not asked for twice.
 */
export function mapTristateReasonSiblings(
  items: readonly CrfItem[],
  catalogEntryFor?: (oid: string) => TristateCatalogEntry | null | undefined,
): { reasonOidByParentOid: Map<string, string>; consumedReasonOids: Set<string> } {
  const reasonOidByParentOid = new Map<string, string>()
  const consumedReasonOids = new Set<string>()

  for (const item of items) {
    const entry = catalogEntryFor?.(item.oid) ?? null
    if (!isTristateParent(item, entry)) continue
    const reasonOid = findReasonSiblingOid(item, items)
    if (reasonOid == null) continue
    reasonOidByParentOid.set(item.oid, reasonOid)
    consumedReasonOids.add(reasonOid)
  }
  return { reasonOidByParentOid, consumedReasonOids }
}
