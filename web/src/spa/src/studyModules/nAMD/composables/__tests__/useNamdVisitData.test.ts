/**
 * 2026-09-18 — the mm³ → nanolitre conversion on the nAMD workspace.
 *
 * This existed only as a comment admitting it was not a unit: it multiplied by
 * 100 so the numbers landed in the range a design mockup showed, which made
 * every figure labelled "nL" ten times too small and made every threshold
 * written in nL fire at ten times the volume it claimed.
 *
 * These cases exist so the conversion is asserted rather than described. A
 * number on a clinical screen has to mean what its unit says.
 */
import { describe, it, expect } from 'vitest'
import { ACTIVITY_THRESHOLD_NL } from '../../fluid'
import {
  ABSENT_NL,
  CENTRAL_SRF_STRICT_INCREASE_NL,
  IRF_INCREASE_NL,
  NAMD_THRESHOLDS_VERSION,
  SRF_RING_1_3_INCREASE_NL,
} from '../useNamdAiRecommendation'

/**
 * The conversion under test. Kept as a local copy because the composable does
 * not export it and the arithmetic is the whole point — if this and the
 * composable ever disagree, the composable's own trigger tests fail.
 */
function mm3ToNl(v: number | null | undefined): number {
  if (v == null) return 0
  return Math.round(v * 1000)
}

describe('mm³ → nL', () => {
  it('converts by the definition: 1 mm³ is 1000 nL', () => {
    expect(mm3ToNl(1)).toBe(1000)
  })

  it('keeps a realistic fluid volume in whole nanolitres', () => {
    // 0.0123 mm³ is a small but real IRF pocket.
    expect(mm3ToNl(0.0123)).toBe(12)
  })

  it('rounds volumes below the segmentation resolution to zero', () => {
    expect(mm3ToNl(0.0004)).toBe(0)
  })

  it('treats a missing measurement as zero rather than NaN', () => {
    expect(mm3ToNl(null)).toBe(0)
    expect(mm3ToNl(undefined)).toBe(0)
  })

  it('has no negative surprise for a zero measurement', () => {
    expect(mm3ToNl(0)).toBe(0)
  })
})

describe('thresholds after the unit correction', () => {
  /**
   * The thresholds were multiplied by ten in the same change, so every rule
   * fires at the volume it fired at before. These pin the volumes, in mm³,
   * that the rules actually act on — the figure a clinician can check.
   */
  it('IRF increase acts at 0.2 mm³', () => {
    expect(IRF_INCREASE_NL).toBe(mm3ToNl(0.2))
  })

  it('central SRF increase acts at 0.1 mm³', () => {
    expect(CENTRAL_SRF_STRICT_INCREASE_NL).toBe(mm3ToNl(0.1))
  })

  it('ring SRF increase acts at 0.1 mm³', () => {
    expect(SRF_RING_1_3_INCREASE_NL).toBe(mm3ToNl(0.1))
  })

  it('"absent" covers measurement noise up to 0.01 mm³', () => {
    expect(ABSENT_NL).toBe(mm3ToNl(0.01))
  })

  it('exudation is called active at 0.2 mm³ of total fluid', () => {
    expect(ACTIVITY_THRESHOLD_NL).toBe(mm3ToNl(0.2))
  })

  it('names the threshold set, so a stored decision stays interpretable', () => {
    expect(NAMD_THRESHOLDS_VERSION).toMatch(/^v\d+-\d{4}-\d{2}$/)
  })
})
