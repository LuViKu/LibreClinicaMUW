/**
 * 2026-10 — NamdRecommendationCard renders the insufficient-data state: it says
 * so, lists every missing input, warns about a placeholder model, and never
 * dresses up as a KEEP/EXTEND recommendation. German + English both resolve.
 */
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import NamdRecommendationCard from '../NamdRecommendationCard.vue'
import de from '../../locales/de.json'
import en from '../../locales/en.json'
import type { NamdAiRecommendation } from '../../types'

function mountCard(rec: NamdAiRecommendation | null, locale: 'de' | 'en' = 'de') {
  const i18n = createI18n({
    legacy: false,
    locale,
    messages: {
      de: { studyModules: { namd: (de as any).studyModules.namd } },
      en: { studyModules: { namd: (en as any).studyModules.namd } },
    },
    missingWarn: false,
    fallbackWarn: false,
  })
  return mount(NamdRecommendationCard, { global: { plugins: [i18n] }, props: { rec } })
}

const insufficient = (over: Partial<NamdAiRecommendation> = {}): NamdAiRecommendation => ({
  rec: null,
  reason: 'INSUFFICIENT_DATA',
  missing: ['current.bcva', 'current.hemorrhage'],
  placeholderModel: false,
  fetchFailures: [],
  intervalWeeks: null,
  rationale: null,
  triggersFired: [],
  ...over,
})

describe('NamdRecommendationCard — insufficient data', () => {
  it('shows the insufficient-data state and every missing input, not a recommendation', () => {
    const w = mountCard(insufficient())
    expect(w.find('[data-testid="namd-recommendation-insufficient"]').exists()).toBe(true)
    expect(w.find('[data-testid="namd-recommendation-card"]').exists()).toBe(false)
    expect(w.find('[data-testid="namd-missing-current.bcva"]').text()).toContain('BCVA')
    expect(w.find('[data-testid="namd-missing-current.hemorrhage"]').text()).toContain('Blutung')
    expect(w.text()).toContain('Keine Empfehlung')
    expect(w.text()).toContain('manuell')
    expect(w.text()).not.toContain('Intervall verlängern')
  })

  it('resolves in English too', () => {
    const w = mountCard(insufficient(), 'en')
    expect(w.text()).toContain('No recommendation')
    expect(w.find('[data-testid="namd-missing-current.hemorrhage"]').text()).toContain('Haemorrhage')
  })

  it('names the failed fetch when a source failed rather than was merely unrecorded', () => {
    const w = mountCard(insufficient({ fetchFailures: ['clinicalFlags'] }))
    expect(w.find('[data-testid="namd-fetch-failures"]').text()).toContain('Klinische Befunde')
  })

  it('placeholder model: shows the explicit warning and no missing-input list', () => {
    const w = mountCard(insufficient({ missing: [], placeholderModel: true }))
    const warn = w.find('[data-testid="namd-placeholder-warning"]')
    expect(warn.exists()).toBe(true)
    expect(warn.text()).toContain('Platzhalter')
    expect(w.find('[data-testid="namd-missing-inputs"]').exists()).toBe(false)
    expect(mountCard(insufficient({ missing: [], placeholderModel: true }), 'en')
      .find('[data-testid="namd-placeholder-warning"]').text())
      .toContain('placeholder model — not a real segmentation')
  })

  it('a real recommendation still renders the normal card', () => {
    const w = mountCard({
      rec: 'KEEP', reason: null, missing: [], placeholderModel: false, fetchFailures: [],
      intervalWeeks: 8, rationale: null, triggersFired: [],
    })
    expect(w.find('[data-testid="namd-recommendation-card"]').exists()).toBe(true)
    expect(w.find('[data-testid="namd-recommendation-insufficient"]').exists()).toBe(false)
  })
})
