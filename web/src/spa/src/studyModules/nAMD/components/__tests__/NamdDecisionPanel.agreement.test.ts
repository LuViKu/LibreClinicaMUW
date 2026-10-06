/**
 * 2026-10 — NamdDecisionPanel: agreedWithAi must compare the ACTION (and the
 * drug, when the AI names one) as well as the interval, and the default
 * decision date must be the clinician's LOCAL calendar date, not UTC.
 */
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, it, expect, afterEach, vi } from 'vitest'
vi.mock('@/api/client', () => ({ apiPost: vi.fn(async () => undefined) }))

import NamdDecisionPanel from '../NamdDecisionPanel.vue'
import { localIsoDate } from '../../localDate'
import type { NamdAiRecommendation } from '../../types'

const i18n = createI18n({
  legacy: false,
  locale: 'de',
  messages: { de: {}, en: {} },
  missingWarn: false,
  fallbackWarn: false,
})

function makeRec(rec: 'SHORTEN' | 'KEEP' | 'EXTEND', weeks: number): NamdAiRecommendation {
  return {
    rec,
    reason: null,
    missing: [],
    placeholderModel: false,
    fetchFailures: [],
    intervalWeeks: weeks,
    rationale: null,
    triggersFired: [],
  }
}

function mountPanel(aiRec: NamdAiRecommendation | null) {
  return mount(NamdDecisionPanel, {
    global: { plugins: [i18n] },
    props: { eventCrfId: 9001, subjectArm: 'study', aiRec },
  })
}

async function decide(
  aiRec: NamdAiRecommendation,
  choice: { action: 'TREAT' | 'OBSERVE'; drug?: string; interval: number },
) {
  const w = mountPanel(aiRec)
  await w.find(`[data-testid="namd-decision-action-${choice.action}"]`).trigger('click')
  if (choice.drug) await w.find(`[data-testid="namd-decision-drug-${choice.drug}"]`).trigger('click')
  await w.find(`[data-testid="namd-decision-interval-${choice.interval}"]`).trigger('click')
  return w
}
const rationaleShown = (w: ReturnType<typeof mountPanel>) =>
  w.find('[data-testid="namd-decision-rationale-block"]').exists()

describe('agreedWithAi compares action, drug and interval', () => {
  it('SHORTEN + OBSERVE at the same interval is a DISAGREEMENT (rationale required)', async () => {
    // The interval-only comparison used to call this agreement.
    expect(rationaleShown(await decide(makeRec('SHORTEN', 8), { action: 'OBSERVE', interval: 8 }))).toBe(true)
  })

  it('KEEP + OBSERVE at the same interval is a disagreement too', async () => {
    expect(rationaleShown(await decide(makeRec('KEEP', 8), { action: 'OBSERVE', interval: 8 }))).toBe(true)
  })

  it('SHORTEN + TREAT at the same interval is an agreement', async () => {
    const w = await decide(makeRec('SHORTEN', 8), { action: 'TREAT', drug: 'AFLIBERCEPT', interval: 8 })
    expect(rationaleShown(w)).toBe(false)
  })

  it('EXTEND accepts OBSERVE at the proposed interval', async () => {
    expect(rationaleShown(await decide(makeRec('EXTEND', 12), { action: 'OBSERVE', interval: 12 }))).toBe(false)
  })

  it('when the AI names a drug, a different drug is a disagreement', async () => {
    const rec = { ...makeRec('SHORTEN', 8), drug: 'FARICIMAB' }
    expect(rationaleShown(await decide(rec, { action: 'TREAT', drug: 'AFLIBERCEPT', interval: 8 }))).toBe(true)
  })

  it('when the AI names a drug, the same drug agrees', async () => {
    const rec = { ...makeRec('SHORTEN', 8), drug: 'FARICIMAB' }
    expect(rationaleShown(await decide(rec, { action: 'TREAT', drug: 'FARICIMAB', interval: 8 }))).toBe(false)
  })

  it('emits agreedWithAi=false for a differing action', async () => {
    const w = await decide(makeRec('SHORTEN', 8), { action: 'OBSERVE', interval: 8 })
    await w.find('[data-testid="namd-decision-rationale-CLINICAL_JUDGMENT"] input').setValue('CLINICAL_JUDGMENT')
    await w.find('[data-testid="namd-decision-confirm"]').trigger('click')
    await vi.waitFor(() => expect(w.emitted('saved')).toBeTruthy())
    expect((w.emitted('saved')![0]![0] as { agreedWithAi: boolean | null }).agreedWithAi).toBe(false)
  })

  it('emits agreedWithAi=true when action, drug and interval all match', async () => {
    const w = await decide(makeRec('SHORTEN', 8), { action: 'TREAT', drug: 'AFLIBERCEPT', interval: 8 })
    await w.find('[data-testid="namd-decision-confirm"]').trigger('click')
    await vi.waitFor(() => expect(w.emitted('saved')).toBeTruthy())
    expect((w.emitted('saved')![0]![0] as { agreedWithAi: boolean | null }).agreedWithAi).toBe(true)
  })

  it('an insufficient-data result is "no AI rec": nothing to agree with, decision still recordable', async () => {
    const insufficient: NamdAiRecommendation = {
      rec: null,
      reason: 'INSUFFICIENT_DATA',
      missing: ['current.bcva'],
      placeholderModel: false,
      fetchFailures: [],
      intervalWeeks: null,
      rationale: null,
      triggersFired: [],
    }
    const w = await decide(insufficient, { action: 'TREAT', drug: 'AFLIBERCEPT', interval: 8 })
    expect(rationaleShown(w)).toBe(false)
    expect((w.find('[data-testid="namd-decision-confirm"]').element as HTMLButtonElement).disabled).toBe(false)
    await w.find('[data-testid="namd-decision-confirm"]').trigger('click')
    await vi.waitFor(() => expect(w.emitted('saved')).toBeTruthy())
    expect((w.emitted('saved')![0]![0] as { agreedWithAi: boolean | null }).agreedWithAi).toBeNull()
  })
})

describe('decisionDate defaults to the LOCAL calendar date, not UTC', () => {
  const originalTz = process.env.TZ
  afterEach(() => {
    vi.useRealTimers()
    if (originalTz === undefined) delete process.env.TZ
    else process.env.TZ = originalTz
  })

  const dateInput = (w: ReturnType<typeof mountPanel>) =>
    (w.find('[data-testid="namd-decision-date"]').element as HTMLInputElement).value

  it('east of UTC: 23:30 UTC on 5 Oct is already 6 Oct in Auckland', () => {
    process.env.TZ = 'Pacific/Auckland'
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-05T23:30:00Z'))
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-10-05') // what the old code produced
    expect(dateInput(mountPanel(null))).toBe('2026-10-06')
  })

  it('west of UTC: 20:30 on 5 Oct in Los Angeles is already 6 Oct in UTC', () => {
    process.env.TZ = 'America/Los_Angeles'
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-06T03:30:00Z'))
    expect(dateInput(mountPanel(null))).toBe('2026-10-05')
  })

  it('localIsoDate formats the local calendar date with zero padding', () => {
    expect(localIsoDate(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05')
  })
})
