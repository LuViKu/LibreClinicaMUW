/**
 * 2026-06-30 — Rule-engine trigger matrix for the nAMD
 * treat-and-extend recommendation. One test per protocol trigger plus
 * precedence cases.
 */
import { computed } from 'vue'
import { describe, it, expect } from 'vitest'
import { useNamdAiRecommendation } from '../useNamdAiRecommendation'
import type { NamdVisit } from '../../types'

function visit(overrides: Partial<NamdVisit> = {}): NamdVisit {
  return {
    id: overrides.id ?? 'v',
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
    hemorrhage: false,
    bcvaAttributableToNamd: false,
    ...overrides,
  }
}

function run(cur: NamdVisit, prev: NamdVisit | null) {
  const current = computed(() => cur)
  const prevRef = computed(() => prev)
  return useNamdAiRecommendation({ current, prev: prevRef }).value
}

describe('useNamdAiRecommendation — first-visit fall-through', () => {
  it('returns null on first visit (no prev)', () => {
    expect(run(visit(), null)).toBeNull()
  })
})

describe('useNamdAiRecommendation — SHORTEN triggers', () => {
  it('DE_NOVO_IRF when prev=0 → cur>0', () => {
    const rec = run(visit({ irf: 30 }), visit({ irf: 0 }))
    expect(rec?.rec).toBe('SHORTEN')
    expect(rec?.triggersFired.map((t) => t.key)).toContain('DE_NOVO_IRF')
  })
  it('IRF_INCREASE above threshold', () => {
    const rec = run(visit({ irf: 60 }), visit({ irf: 30 }))
    expect(rec?.rec).toBe('SHORTEN')
    expect(rec?.triggersFired.map((t) => t.key)).toContain('IRF_INCREASE')
  })
  it('IRF_DECREASE_INSUFFICIENT when drop <50%', () => {
    const rec = run(visit({ irf: 70 }), visit({ irf: 100 }))
    expect(rec?.rec).toBe('SHORTEN')
    expect(rec?.triggersFired.map((t) => t.key)).toContain('IRF_DECREASE_INSUFFICIENT')
  })
  it('DE_NOVO_CENTRAL_SRF when prev central=0 → cur>0', () => {
    const rec = run(
      visit({ srf: 5, fluidByRegion: { c1: { irf: 0, srf: 5, ped: 0 }, c3: { irf: 0, srf: 5, ped: 0 }, c6: { irf: 0, srf: 5, ped: 0 } } }),
      visit(),
    )
    expect(rec?.rec).toBe('SHORTEN')
    expect(rec?.triggersFired.map((t) => t.key)).toContain('DE_NOVO_CENTRAL_SRF')
  })
  it('CENTRAL_SRF_INCREASE above strict threshold', () => {
    const cur = visit({ fluidByRegion: { c1: { irf: 0, srf: 30, ped: 0 }, c3: { irf: 0, srf: 30, ped: 0 }, c6: { irf: 0, srf: 30, ped: 0 } } })
    const prev = visit({ fluidByRegion: { c1: { irf: 0, srf: 10, ped: 0 }, c3: { irf: 0, srf: 10, ped: 0 }, c6: { irf: 0, srf: 10, ped: 0 } } })
    const rec = run(cur, prev)
    expect(rec?.rec).toBe('SHORTEN')
    expect(rec?.triggersFired.map((t) => t.key)).toContain('CENTRAL_SRF_INCREASE')
  })
  it('SRF_RING_1_3_INCREASE when (c3-c1) jumps ≥10 nL', () => {
    const cur = visit({ fluidByRegion: { c1: { irf: 0, srf: 5, ped: 0 }, c3: { irf: 0, srf: 25, ped: 0 }, c6: { irf: 0, srf: 25, ped: 0 } } })
    const prev = visit({ fluidByRegion: { c1: { irf: 0, srf: 5, ped: 0 }, c3: { irf: 0, srf: 10, ped: 0 }, c6: { irf: 0, srf: 10, ped: 0 } } })
    const rec = run(cur, prev)
    expect(rec?.rec).toBe('SHORTEN')
    expect(rec?.triggersFired.map((t) => t.key)).toContain('SRF_RING_1_3_INCREASE')
  })
  it('NEW_HEMORRHAGE flag fires', () => {
    const rec = run(visit({ hemorrhage: true }), visit())
    expect(rec?.rec).toBe('SHORTEN')
    expect(rec?.triggersFired.map((t) => t.key)).toContain('NEW_HEMORRHAGE')
  })
  it('BCVA_LOSS_5_LETTERS fires only when attribution flag is true', () => {
    const rec = run(visit({ bcva: 70, bcvaAttributableToNamd: true }), visit({ bcva: 75 }))
    expect(rec?.rec).toBe('SHORTEN')
    expect(rec?.triggersFired.map((t) => t.key)).toContain('BCVA_LOSS_5_LETTERS')
  })
  it('BCVA drop without attribution does NOT trigger SHORTEN', () => {
    const rec = run(visit({ bcva: 70 }), visit({ bcva: 75 }))
    expect(rec?.rec).not.toBe('SHORTEN')
  })
})

describe('useNamdAiRecommendation — KEEP triggers', () => {
  it('RESIDUAL_IRF_HALVED when drop ≥50% but still present', () => {
    const rec = run(visit({ irf: 30 }), visit({ irf: 80 }))
    expect(rec?.rec).toBe('KEEP')
    expect(rec?.triggersFired.map((t) => t.key)).toContain('RESIDUAL_IRF_HALVED')
  })
  it('RESIDUAL_IRF_STABLE when small upward drift (no decrease + no SHORTEN trigger)', () => {
    // cur slightly higher than prev — under IRF_INCREASE_NL=20 so no SHORTEN
    // fires; cur > 0 + prev > 0 so RESIDUAL_IRF_STABLE qualifies.
    const rec = run(visit({ irf: 32 }), visit({ irf: 30 }))
    expect(rec?.rec).toBe('KEEP')
    expect(rec?.triggersFired.map((t) => t.key)).toContain('RESIDUAL_IRF_STABLE')
  })
})

describe('useNamdAiRecommendation — EXTEND eligibility', () => {
  it('all-clear visit → EXTEND with all 4 eligibility triggers fired', () => {
    const rec = run(visit(), visit())
    expect(rec?.rec).toBe('EXTEND')
    const keys = rec?.triggersFired.map((t) => t.key) ?? []
    expect(keys).toContain('IRF_ABSENT')
    expect(keys).toContain('CENTRAL_SRF_ABSENT')
    expect(keys).toContain('NO_HEMORRHAGE_OR_BCVA_LOSS')
    expect(keys).toContain('SRF_ISOLATED_1_3_STABLE')
  })
})

describe('useNamdAiRecommendation — precedence', () => {
  it('SHORTEN beats EXTEND eligibility (hemorrhage on otherwise-dry visit)', () => {
    const rec = run(visit({ hemorrhage: true }), visit())
    expect(rec?.rec).toBe('SHORTEN')
    const keys = rec?.triggersFired.map((t) => t.key) ?? []
    expect(keys).toContain('NEW_HEMORRHAGE')
    expect(keys).toContain('IRF_ABSENT')
  })
  it('KEEP suppresses EXTEND when residual IRF still present', () => {
    expect(run(visit({ irf: 30 }), visit({ irf: 80 }))?.rec).toBe('KEEP')
  })
})
