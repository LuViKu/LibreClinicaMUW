/**
 * The recommendation's rationale line, in both languages.
 *
 * It used to be a hard-coded German sentence returned straight from the rule
 * engine, so an English-speaking reader of the workspace got one German line in
 * the middle of an English screen — on the card that says why a treatment
 * interval should change.
 *
 * The engine now returns a key and its parameters. That only helps if every key
 * it can produce actually exists in both bundles, which is what these assert:
 * a missing key renders as the key itself, which looks like a bug report rather
 * than a clinical statement.
 */
import { describe, it, expect } from 'vitest'
import { computed } from 'vue'
import { createI18n } from 'vue-i18n'
import { useNamdAiRecommendation } from '../useNamdAiRecommendation'
import de from '../../locales/de.json'
import en from '../../locales/en.json'
import type { NamdVisit } from '../../types'

const i18n = createI18n({ legacy: false, locale: 'de', fallbackLocale: 'de', messages: { de, en } })

function visit(over: Partial<NamdVisit> = {}): NamdVisit {
  return {
    id: 'v', label: 'V', week: 0, date: '2026-01-01',
    acquisitionDate: null, visitDate: null, dateMismatch: false,
    irf: 0, srf: 0, ped: 0,
    fluidByRegion: {
      c1: { irf: 0, srf: 0, ped: 0 },
      c3: { irf: 0, srf: 0, ped: 0 },
      c6: { irf: 0, srf: 0, ped: 0 },
    },
    crt: 280, bcva: 75, bcvaRaw: null, inj: '', interval: 8,
    retinalJobId: null, eventCrfId: null, studyEventId: null,
    hemorrhage: false, bcvaAttributableToNamd: false,
    ...over,
  }
}

function rationaleOf(cur: NamdVisit, prev: NamdVisit | null) {
  return useNamdAiRecommendation({
    current: computed(() => cur),
    prev: computed(() => prev),
  }).value?.rationale ?? null
}

/** One visit pair per rationale the engine can produce. */
const CASES: Array<[string, NamdVisit, NamdVisit]> = [
  ['DE_NOVO_IRF', visit({ irf: 300 }), visit({ irf: 0 })],
  ['IRF_INCREASE', visit({ irf: 600 }), visit({ irf: 300 })],
  ['IRF_DECREASE_INSUFFICIENT', visit({ irf: 700 }), visit({ irf: 1000 })],
  ['NEW_HEMORRHAGE', visit({ hemorrhage: true }), visit()],
  ['BCVA_LOSS_5_LETTERS', visit({ bcva: 70, bcvaAttributableToNamd: true }), visit({ bcva: 75 })],
  ['RESIDUAL_IRF_HALVED', visit({ irf: 300 }), visit({ irf: 800 })],
  ['RESIDUAL_IRF_STABLE', visit({ irf: 320 }), visit({ irf: 300 })],
  ['DRY_STABLE', visit(), visit()],
]

describe('recommendation rationale', () => {
  it('is a translation key, not a finished sentence', () => {
    const r = rationaleOf(visit({ irf: 300 }), visit({ irf: 0 }))
    expect(r).toBeTruthy()
    expect(r!.key).toMatch(/^studyModules\.namd\.recommendation\.rationale\./)
  })

  it.each(CASES)('resolves %s in both bundles', (_name, cur, prev) => {
    const r = rationaleOf(cur, prev)
    expect(r).toBeTruthy()
    for (const locale of ['de', 'en'] as const) {
      i18n.global.locale.value = locale
      const text = i18n.global.t(r!.key, r!.params)
      expect(text, `${locale} is missing ${r!.key}`).not.toBe(r!.key)
      expect(text.length).toBeGreaterThan(0)
      // An unsubstituted placeholder means the sentence promises a number it
      // never prints.
      expect(text).not.toMatch(/\{\w+\}/)
    }
  })

  it('substitutes the measured value and the threshold', () => {
    i18n.global.locale.value = 'en'
    const r = rationaleOf(visit({ irf: 600 }), visit({ irf: 300 }))
    const text = i18n.global.t(r!.key, r!.params)
    expect(text).toContain('300')
    expect(text).toContain('200')
  })
})
