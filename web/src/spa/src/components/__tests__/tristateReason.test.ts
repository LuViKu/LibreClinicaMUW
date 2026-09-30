/**
 * TRISTATE_REASON pairing (2026-09-30).
 *
 * The inline reason textarea of a tri-state parent writes to a SEPARATE
 * sibling item. Before this pairing existed the widget emitted the text and
 * nothing listened, so two things went wrong at once: the typed reason was
 * dropped, and the sibling rendered as a second reason field of its own.
 */

import { describe, expect, it } from 'vitest'

import {
  findReasonSiblingOid,
  isTristateParent,
  mapTristateReasonSiblings,
} from '../tristateReason'
import type { CrfItem } from '@/types/crf'

/** Minimal item; the helpers only read oid / dataType / options / showWhen. */
function item(partial: Partial<CrfItem> & { oid: string }): CrfItem {
  return { dataType: 'text', options: null, showWhen: null, ...partial } as unknown as CrfItem
}

const TRISTATE_OPTIONS = [{ code: 'JA' }, { code: 'NEIN' }, { code: 'UNBEKANNT' }]

/** The shape imagingAcquisitionPreset emits, per eye. */
const acquiredOd = item({
  oid: 'OD_SPECTRALIS_ACQUIRED',
  dataType: 'select-one',
  options: TRISTATE_OPTIONS,
})
const reasonOd = item({
  oid: 'OD_SPECTRALIS_REASON',
  showWhen: JSON.stringify({
    sourceItemOid: 'OD_SPECTRALIS_ACQUIRED',
    comparator: '==',
    literal: 'NEIN',
  }),
})

describe('isTristateParent', () => {
  it('recognises the _TRISTATE suffix', () => {
    expect(isTristateParent(item({ oid: 'OD_FOO_TRISTATE' }), null)).toBe(true)
  })

  it('recognises a select-one with three options including unbekannt', () => {
    expect(isTristateParent(acquiredOd, null)).toBe(true)
  })

  it('accepts the English and canonical spellings too', () => {
    for (const third of ['unknown', '2']) {
      const it3 = item({
        oid: 'OD_X_ACQUIRED',
        dataType: 'select-one',
        options: [{ code: 'JA' }, { code: 'NEIN' }, { code: third }],
      })
      expect(isTristateParent(it3, null)).toBe(true)
    }
  })

  it('is not a tri-state when a catalog entry is bound', () => {
    // A catalogued item takes its widget from the catalog, and no catalog
    // widget maps to tri-state — so the heuristic must not override it.
    expect(isTristateParent(acquiredOd, { widget: 'yesno' })).toBe(false)
  })

  it('is not a tri-state for a two-option yes/no', () => {
    const yesno = item({
      oid: 'OD_X_DONE',
      dataType: 'select-one',
      options: [{ code: 'JA' }, { code: 'NEIN' }],
    })
    expect(isTristateParent(yesno, null)).toBe(false)
  })
})

describe('findReasonSiblingOid', () => {
  it('finds the sibling whose show-when names the parent', () => {
    expect(findReasonSiblingOid(acquiredOd, [acquiredOd, reasonOd])).toBe('OD_SPECTRALIS_REASON')
  })

  it('accepts the legacy OpenClinica rule string', () => {
    const legacy = item({ oid: 'OD_LEG_REASON', showWhen: 'OD_LEG_ACQUIRED eq NEIN' })
    const parent = item({ oid: 'OD_LEG_ACQUIRED' })
    expect(findReasonSiblingOid(parent, [parent, legacy])).toBe('OD_LEG_REASON')
  })

  it('returns null when the parent has no reason sibling', () => {
    expect(findReasonSiblingOid(acquiredOd, [acquiredOd])).toBeNull()
  })

  it('does not pair an item with itself', () => {
    const selfRef = item({
      oid: 'OD_SELF',
      showWhen: JSON.stringify({ sourceItemOid: 'OD_SELF', comparator: '==', literal: 'NEIN' }),
    })
    expect(findReasonSiblingOid(selfRef, [selfRef])).toBeNull()
  })

  it('does not pair a sibling that points at a different parent', () => {
    const otherParent = item({ oid: 'OS_SPECTRALIS_ACQUIRED' })
    expect(findReasonSiblingOid(otherParent, [otherParent, reasonOd])).toBeNull()
  })
})

describe('mapTristateReasonSiblings', () => {
  it('pairs each eye independently and marks both reasons consumed', () => {
    const acquiredOs = item({
      oid: 'OS_SPECTRALIS_ACQUIRED',
      dataType: 'select-one',
      options: TRISTATE_OPTIONS,
    })
    const reasonOs = item({
      oid: 'OS_SPECTRALIS_REASON',
      showWhen: JSON.stringify({
        sourceItemOid: 'OS_SPECTRALIS_ACQUIRED',
        comparator: '==',
        literal: 'NEIN',
      }),
    })
    const { reasonOidByParentOid, consumedReasonOids } = mapTristateReasonSiblings([
      acquiredOd,
      reasonOd,
      acquiredOs,
      reasonOs,
    ])
    expect(reasonOidByParentOid.get('OD_SPECTRALIS_ACQUIRED')).toBe('OD_SPECTRALIS_REASON')
    expect(reasonOidByParentOid.get('OS_SPECTRALIS_ACQUIRED')).toBe('OS_SPECTRALIS_REASON')
    // Both reason items are rendered inline by their parent, so neither may
    // appear as a row of its own — that duplicate row is the reported bug.
    expect(consumedReasonOids).toEqual(new Set(['OD_SPECTRALIS_REASON', 'OS_SPECTRALIS_REASON']))
  })

  it('leaves a conditional item that belongs to a non-tri-state parent alone', () => {
    // A plain yes/no with a conditional follow-up must keep rendering its
    // follow-up as a normal row; only tri-state parents render one inline.
    const done = item({
      oid: 'OD_X_DONE',
      dataType: 'select-one',
      options: [{ code: 'JA' }, { code: 'NEIN' }],
    })
    const followUp = item({
      oid: 'OD_X_DETAIL',
      showWhen: JSON.stringify({ sourceItemOid: 'OD_X_DONE', comparator: '==', literal: 'NEIN' }),
    })
    const { reasonOidByParentOid, consumedReasonOids } = mapTristateReasonSiblings([done, followUp])
    expect(reasonOidByParentOid.size).toBe(0)
    expect(consumedReasonOids.size).toBe(0)
  })

  it('consumes nothing when a tri-state has no reason sibling', () => {
    const { consumedReasonOids } = mapTristateReasonSiblings([acquiredOd])
    expect(consumedReasonOids.size).toBe(0)
  })
})
