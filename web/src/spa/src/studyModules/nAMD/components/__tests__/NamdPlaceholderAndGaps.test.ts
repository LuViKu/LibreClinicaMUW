/**
 * 2026-10 — (1) placeholder-model volumes must not look real anywhere: a
 * non-dismissable banner, a visible "Platzhalter" mark on seg cards and report
 * values, and the warning in the printed report; (2) unknown volumes are gaps
 * in the fluid trend chart, never a flat zero that reads as "dry".
 */
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import NamdPlaceholderBanner from '../NamdPlaceholderBanner.vue'
import NamdSegCards from '../NamdSegCards.vue'
import NamdFluidTrendChart from '../NamdFluidTrendChart.vue'
import NamdActivityPill from '../NamdActivityPill.vue'
import NamdReportTab from '../../views/NamdReportTab.vue'
import de from '../../locales/de.json'
import en from '../../locales/en.json'
import type { NamdVisit, NamdWorkspaceData } from '../../types'

function i18n(locale: 'de' | 'en' = 'de') {
  return createI18n({
    legacy: false,
    locale,
    messages: {
      de: { studyModules: { namd: (de as any).studyModules.namd } },
      en: { studyModules: { namd: (en as any).studyModules.namd } },
    },
    missingWarn: false,
    fallbackWarn: false,
  })
}

const RING = { irf: 10, srf: 10, ped: 10 }
function visit(over: Partial<NamdVisit> = {}): NamdVisit {
  return {
    id: 'v1', label: 'V01', week: 0, date: '2026-01-01',
    acquisitionDate: null, visitDate: null, dateMismatch: false,
    irf: 10, srf: 10, ped: 10,
    fluidByRegion: { c1: RING, c3: RING, c6: RING },
    crt: 300, bcva: 70, bcvaRaw: null, inj: '', interval: 8,
    retinalJobId: null, eventCrfId: null, studyEventId: null,
    hemorrhage: false, bcvaAttributableToNamd: false,
    modelVersion: 'retinsight-v1.2',
    ...over,
  }
}
const PLACEHOLDER = { modelVersion: 'placeholder-v1' }

describe('NamdPlaceholderBanner', () => {
  it('is shown, with the affected visits, when any visit is from a placeholder model', () => {
    const w = mount(NamdPlaceholderBanner, {
      global: { plugins: [i18n()] },
      props: { visits: [visit({ label: 'V01' }), visit({ id: 'v2', label: 'V02', ...PLACEHOLDER })] },
    })
    const b = w.find('[data-testid="namd-placeholder-banner"]')
    expect(b.exists()).toBe(true)
    expect(b.attributes('role')).toBe('alert')
    expect(b.text()).toContain('Platzhalter')
    expect(b.text()).toContain('V02')
    expect(b.text()).not.toContain('V01')
  })

  it('is NOT dismissable: no button or close control inside', () => {
    const w = mount(NamdPlaceholderBanner, {
      global: { plugins: [i18n()] },
      props: { visits: [visit(PLACEHOLDER)] },
    })
    expect(w.find('button').exists()).toBe(false)
    expect(w.find('[data-testid="namd-placeholder-banner"] [role="button"]').exists()).toBe(false)
  })

  it('says "placeholder" in English', () => {
    const w = mount(NamdPlaceholderBanner, {
      global: { plugins: [i18n('en')] },
      props: { visits: [visit(PLACEHOLDER)] },
    })
    expect(w.text()).toContain('Placeholder result — not a real segmentation')
  })

  it('renders nothing for real-model visits', () => {
    const w = mount(NamdPlaceholderBanner, { global: { plugins: [i18n()] }, props: { visits: [visit()] } })
    expect(w.find('[data-testid="namd-placeholder-banner"]').exists()).toBe(false)
  })
})

describe('NamdSegCards — placeholder marking', () => {
  it('marks every card of a placeholder visit', () => {
    const w = mount(NamdSegCards, {
      global: { plugins: [i18n()] },
      props: { current: visit(PLACEHOLDER), prev: null },
    })
    const marks = w.findAll('[data-testid="namd-seg-placeholder"]')
    expect(marks).toHaveLength(3)
    expect(marks[0]!.text()).toContain('Platzhalter')
  })

  it('does not mark real-model cards', () => {
    const w = mount(NamdSegCards, {
      global: { plugins: [i18n()] },
      props: { current: visit(), prev: null },
    })
    expect(w.find('[data-testid="namd-seg-placeholder"]').exists()).toBe(false)
  })
})

describe('NamdReportTab — placeholder warning travels with the printed report', () => {
  function reportData(visits: NamdVisit[]): NamdWorkspaceData {
    return {
      patient: { id: 'S-1', studySubjectId: 1, eye: 'OD', diagnosis: 'nAMD', age: null, study: '', regimen: '' },
      visits,
      current: visits[visits.length - 1]!,
      prev: visits.length > 1 ? visits[visits.length - 2]! : null,
      ai: null,
      nSlices: null,
      subjectArm: 'study',
    }
  }
  const mountReport = (visits: NamdVisit[]) =>
    mount(NamdReportTab, {
      global: {
        plugins: [i18n(), createPinia()],
        stubs: { NamdReportScan: true, NamdFluidTrendChart: true },
      },
      props: { data: reportData(visits) },
    })

  it('carries the banner inside the report article (not behind print:hidden / no-print)', () => {
    const w = mountReport([visit(), visit({ id: 'v2', label: 'V02', ...PLACEHOLDER })])
    const article = w.find('[data-testid="namd-report-tab"]')
    const banner = article.find('[data-testid="namd-placeholder-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.element.closest('.no-print')).toBeNull()
    expect(banner.element.closest('.print\\:hidden')).toBeNull()
  })

  it('marks only the placeholder visit row and the current-status values', () => {
    const w = mountReport([visit(), visit({ id: 'v2', label: 'V02', ...PLACEHOLDER })])
    expect(w.find('[data-testid="namd-report-placeholder-v2"]').text()).toContain('Platzhalter')
    expect(w.find('[data-testid="namd-report-placeholder-v1"]').exists()).toBe(false)
    expect(w.find('[data-testid="namd-report-placeholder-current"]').exists()).toBe(true)
  })

  it('shows no placeholder marks for a real model', () => {
    const w = mountReport([visit(), visit({ id: 'v2', label: 'V02' })])
    expect(w.find('[data-testid="namd-placeholder-banner"]').exists()).toBe(false)
    expect(w.find('[data-testid="namd-report-placeholder-current"]').exists()).toBe(false)
  })

  it('prints unknown volumes and CST as an em dash, not 0', () => {
    const w = mountReport([visit({ irf: null, srf: null, ped: null, crt: null })])
    const row = w.findAll('tbody tr')[0]!
    const cells = row.findAll('td').map((c) => c.text())
    expect(cells).not.toContain('0')
    expect(cells.filter((c) => c === '—').length).toBeGreaterThanOrEqual(4)
  })
})

describe('NamdFluidTrendChart — unknown fluid is a gap, not zero', () => {
  const mountChart = (visits: NamdVisit[]) =>
    mount(NamdFluidTrendChart, { global: { plugins: [i18n()] }, props: { visits } })

  const v = (i: number, over: Partial<NamdVisit> = {}) =>
    visit({ id: `v${i}`, label: `V0${i}`, week: i * 4, ...over })
  const UNKNOWN = { irf: null, srf: null, ped: null, fluidByRegion: null }

  it('draws one polygon per contiguous run of known visits and a marker at the gap', () => {
    const w = mountChart([v(0), v(1), v(2, UNKNOWN), v(3), v(4)])
    // 2 runs (V00-V01, V03-V04) × 3 fluid layers.
    expect(w.findAll('[data-testid="namd-trend-poly-IRF"]')).toHaveLength(2)
    expect(w.findAll('[data-testid="namd-trend-poly-SRF"]')).toHaveLength(2)
    expect(w.findAll('[data-testid="namd-trend-poly-PED"]')).toHaveLength(2)
    expect(w.find('[data-testid="namd-trend-gap-2"]').exists()).toBe(true)
    expect(w.find('[data-testid="namd-trend-gap-0"]').exists()).toBe(false)
  })

  it('no polygon vertex lies at the unknown visit\'s x (nothing is plotted through the gap)', () => {
    const w = mountChart([v(0), v(1), v(2, UNKNOWN), v(3), v(4)])
    const gapX = Number(w.find('[data-testid="namd-trend-gap-2"] line').attributes('x1'))
    for (const poly of w.findAll('[data-testid="namd-trend-poly-IRF"]')) {
      const xs = poly.attributes('points')!.split(' ').map((p) => Number(p.split(',')[0]))
      expect(xs.every((x) => Math.abs(x - gapX) > 1)).toBe(true)
    }
  })

  it('a timeline with no known fluid draws no polygons at all (not a flat "dry" band)', () => {
    const w = mountChart([v(0, UNKNOWN), v(1, UNKNOWN)])
    expect(w.findAll('[data-testid="namd-trend-poly-IRF"]')).toHaveLength(0)
    expect(w.find('[data-testid="namd-trend-gap-0"]').exists()).toBe(true)
    expect(w.find('[data-testid="namd-trend-gap-1"]').exists()).toBe(true)
  })

  it('a lone known visit between gaps stays visible as a small polygon', () => {
    const w = mountChart([v(0, UNKNOWN), v(1), v(2, UNKNOWN)])
    expect(w.findAll('[data-testid="namd-trend-poly-IRF"]')).toHaveLength(1)
  })

  it('a really-measured zero is still drawn (dry is not unknown)', () => {
    const zero = { irf: 0, srf: 0, ped: 0, fluidByRegion: { c1: { irf: 0, srf: 0, ped: 0 }, c3: { irf: 0, srf: 0, ped: 0 }, c6: { irf: 0, srf: 0, ped: 0 } } }
    const w = mountChart([v(0, zero), v(1, zero)])
    expect(w.findAll('[data-testid="namd-trend-poly-IRF"]')).toHaveLength(1)
    expect(w.find('[data-testid="namd-trend-gap-0"]').exists()).toBe(false)
  })

  it('does not draw CRT through a visit with unknown CRT', () => {
    const w = mountChart([v(0), v(1, { crt: null }), v(2)])
    const d = w.find('[data-testid="namd-trend-crt"]').attributes('d')!
    // M … then a second M (restart) instead of one continuous line through the gap.
    expect(d.match(/M/g)).toHaveLength(2)
  })
})

describe('NamdActivityPill', () => {
  it('unknown volume is a neutral dash, never "dry"', () => {
    const w = mount(NamdActivityPill, { global: { plugins: [i18n()] }, props: { activeFluidNl: null } })
    expect(w.find('[data-testid="namd-pill-dry"]').exists()).toBe(false)
    expect(w.text()).toBe('—')
  })
})
