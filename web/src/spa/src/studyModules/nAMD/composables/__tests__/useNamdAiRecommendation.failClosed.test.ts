/**
 * 2026-10 — fail-closed behaviour of the nAMD recommendation engine.
 *
 * null = unknown (never recorded, or its fetch failed). The engine used to read
 * it as zero / "no disease", so a visit with no biomarkers, no BCVA or no flags
 * could be recommended EXTEND. These cases pin the opposite: unknown inputs
 * yield an explicit insufficient-data result and KEEP/EXTEND are unreachable.
 */
import { computed } from 'vue'
import { describe, it, expect } from 'vitest'
import { isPlaceholderModel, useNamdAiRecommendation } from '../useNamdAiRecommendation'
import type { NamdVisit } from '../../types'

function visit(overrides: Partial<NamdVisit> = {}): NamdVisit {
  return {
    id: 'v',
    label: 'V',
    week: 0,
    date: '2026-01-01',
    acquisitionDate: null,
    visitDate: null,
    dateMismatch: false,
    irf: 0,
    srf: 0,
    ped: 0,
    fluidByRegion: {
      c1: { irf: 0, srf: 0, ped: 0 },
      c3: { irf: 0, srf: 0, ped: 0 },
      c6: { irf: 0, srf: 0, ped: 0 },
    },
    crt: 280,
    bcva: 75,
    bcvaRaw: null,
    inj: '',
    interval: 8,
    retinalJobId: null,
    eventCrfId: null,
    studyEventId: null,
    hemorrhage: false,
    bcvaAttributableToNamd: false,
    ...overrides,
  }
}

function run(cur: NamdVisit, prev: NamdVisit | null) {
  return useNamdAiRecommendation({ current: computed(() => cur), prev: computed(() => prev) }).value
}

describe('unknown data never yields KEEP or EXTEND', () => {
  it('null fluidByRegion on both visits → insufficient data, NOT EXTEND', () => {
    const rec = run(visit({ fluidByRegion: null }), visit({ fluidByRegion: null }))
    expect(rec?.rec).toBeNull()
    expect(rec?.reason).toBe('INSUFFICIENT_DATA')
    expect(rec?.missing).toContain('current.fluidByRegion')
    expect(rec?.missing).toContain('previous.fluidByRegion')
    expect(rec?.triggersFired).toEqual([])
    expect(rec?.intervalWeeks).toBeNull()
  })

  it('null biomarkers (irf/srf/ped) on the current visit → insufficient data', () => {
    const rec = run(visit({ irf: null, srf: null, ped: null }), visit())
    expect(rec?.rec).toBeNull()
    expect(rec?.missing).toEqual(expect.arrayContaining(['current.irf', 'current.srf', 'current.ped']))
  })

  it('null BCVA on the current visit → insufficient data', () => {
    const rec = run(visit({ bcva: null }), visit())
    expect(rec?.rec).toBeNull()
    expect(rec?.missing).toEqual(['current.bcva'])
  })

  it('null BCVA on the previous visit → insufficient data', () => {
    const rec = run(visit(), visit({ bcva: null }))
    expect(rec?.rec).toBeNull()
    expect(rec?.missing).toEqual(['previous.bcva'])
  })

  it('unrecorded hemorrhage flag → insufficient data (was read as "no haemorrhage")', () => {
    const rec = run(visit({ hemorrhage: null }), visit())
    expect(rec?.rec).toBeNull()
    expect(rec?.missing).toEqual(['current.hemorrhage'])
  })

  it('unrecorded BCVA-attribution flag → insufficient data', () => {
    const rec = run(visit({ bcvaAttributableToNamd: null }), visit())
    expect(rec?.rec).toBeNull()
    expect(rec?.missing).toEqual(['current.bcvaAttributableToNamd'])
  })

  it('flags RECORDED as false are real answers: a dry visit still reaches EXTEND', () => {
    const rec = run(visit({ hemorrhage: false, bcvaAttributableToNamd: false }), visit())
    expect(rec?.rec).toBe('EXTEND')
    expect(rec?.reason).toBeNull()
    expect(rec?.missing).toEqual([])
  })

  it('KEEP is unreachable from unknown inputs (known data alone would be KEEP)', () => {
    const rec = run(visit({ irf: 300, bcva: null }), visit({ irf: 800 }))
    expect(rec?.rec).toBeNull()
    expect(rec?.reason).toBe('INSUFFICIENT_DATA')
  })

  it('lists every unknown input, not just the first', () => {
    const rec = run(
      visit({ irf: null, fluidByRegion: null, hemorrhage: null }),
      visit({ bcva: null }),
    )
    expect(rec?.missing).toEqual(expect.arrayContaining([
      'current.irf', 'current.fluidByRegion', 'current.hemorrhage', 'previous.bcva',
    ]))
  })

  it('carries which fetches failed, so the UI can say "failed" rather than "not recorded"', () => {
    const rec = run(
      visit({ hemorrhage: null, bcvaAttributableToNamd: null, fetchFailures: ['clinicalFlags'] }),
      visit(),
    )
    expect(rec?.rec).toBeNull()
    expect(rec?.fetchFailures).toEqual(['clinicalFlags'])
  })

  it('an unknown reference visit (when supplied) blocks KEEP/EXTEND', () => {
    const rec = useNamdAiRecommendation({
      current: computed(() => visit()),
      prev: computed(() => visit()),
      reference: computed(() => visit({ fluidByRegion: null })),
    }).value
    expect(rec?.rec).toBeNull()
    expect(rec?.missing).toEqual(['reference.fluidByRegion'])
  })
})

describe('a SHORTEN established from known data survives unrelated gaps', () => {
  // Documented choice: SHORTEN is the safe direction, and a known worsening is
  // not cancelled by an unrelated gap. Each trigger needs only its own inputs.
  it('known hemorrhage=true still fires SHORTEN with BCVA unknown', () => {
    const rec = run(visit({ hemorrhage: true, bcva: null }), visit())
    expect(rec?.rec).toBe('SHORTEN')
    expect(rec?.triggersFired.map((t) => t.key)).toEqual(['NEW_HEMORRHAGE'])
    expect(rec?.missing).toContain('current.bcva')
  })

  it('a known IRF increase still fires SHORTEN with the region breakdown unknown', () => {
    const rec = run(visit({ irf: 600, fluidByRegion: null }), visit({ irf: 300, fluidByRegion: null }))
    expect(rec?.rec).toBe('SHORTEN')
    expect(rec?.triggersFired.map((t) => t.key)).toContain('IRF_INCREASE')
  })

  it('with nothing positively established, the same gap gives no recommendation', () => {
    const rec = run(visit({ irf: 300, fluidByRegion: null }), visit({ irf: 300, fluidByRegion: null }))
    expect(rec?.rec).toBeNull()
  })

  it('an unattributed BCVA drop with an unrecorded attribution flag is not a SHORTEN', () => {
    const rec = run(visit({ bcva: 60, bcvaAttributableToNamd: null }), visit({ bcva: 75 }))
    expect(rec?.rec).toBeNull()
  })
})

describe('placeholder model', () => {
  it('detects the sidecar placeholder adapter version', () => {
    expect(isPlaceholderModel('placeholder-v1')).toBe(true)
    expect(isPlaceholderModel(' Placeholder-v2')).toBe(true)
    expect(isPlaceholderModel('retinsight-v1.2')).toBe(false)
    expect(isPlaceholderModel(null)).toBe(false)
    expect(isPlaceholderModel(undefined)).toBe(false)
  })

  it('current visit from "placeholder-v1" → no recommendation, placeholder flag set', () => {
    const rec = run(visit({ modelVersion: 'placeholder-v1' }), visit())
    expect(rec?.rec).toBeNull()
    expect(rec?.reason).toBe('INSUFFICIENT_DATA')
    expect(rec?.placeholderModel).toBe(true)
  })

  it('previous visit from a placeholder model also blocks it', () => {
    expect(run(visit(), visit({ modelVersion: 'Placeholder-v2' }))?.placeholderModel).toBe(true)
  })

  it('even a would-be SHORTEN is withheld: placeholder volumes are fake', () => {
    const rec = run(visit({ irf: 900, modelVersion: 'placeholder-v1' }), visit({ irf: 0 }))
    expect(rec?.rec).toBeNull()
    expect(rec?.triggersFired).toEqual([])
  })

  it('a real model version is unaffected', () => {
    const rec = run(visit({ modelVersion: 'retinsight-v1.2' }), visit({ modelVersion: 'retinsight-v1.2' }))
    expect(rec?.rec).toBe('EXTEND')
    expect(rec?.placeholderModel).toBe(false)
  })
})
