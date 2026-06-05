import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import SectionBadge from '@/components/SectionBadge.vue'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

function mountBadge(props: {
  requiredCount: number
  filledCount: number
  errorCount: number
  openQueries: number
}) {
  return mount(SectionBadge, {
    props,
    global: { plugins: [i18n] },
  })
}

describe('SectionBadge', () => {
  it('shows filled/required as plain pill when partial', () => {
    const w = mountBadge({ requiredCount: 4, filledCount: 1, errorCount: 0, openQueries: 0 })
    expect(w.text()).toContain('1/4')
    // No errors → no rose dot, no query badge.
    expect(w.html()).not.toContain('text-rose-700')
    expect(w.html()).not.toContain('amber')
  })

  it('flips the fill chip green when every required item is filled + no errors', () => {
    const w = mountBadge({ requiredCount: 3, filledCount: 3, errorCount: 0, openQueries: 0 })
    expect(w.text()).toContain('3/3')
    expect(w.html()).toContain('text-emerald-700')
    expect(w.html()).toContain('bg-emerald-50')
  })

  it('keeps the slate-tinted fill chip when filled = required but errors remain', () => {
    // Edge case: every required item was typed in, but at least one
    // is currently invalid — the chip stays slate so the operator
    // doesn't think the section is done.
    const w = mountBadge({ requiredCount: 2, filledCount: 2, errorCount: 1, openQueries: 0 })
    expect(w.html()).not.toContain('text-emerald-700')
    expect(w.html()).toContain('text-rose-700')
  })

  it('renders the amber query badge with count when openQueries > 0', () => {
    const w = mountBadge({ requiredCount: 2, filledCount: 2, errorCount: 0, openQueries: 3 })
    expect(w.text()).toContain('?3')
    expect(w.html()).toContain('bg-amber-100')
  })

  it('omits the fill chip entirely when requiredCount = 0', () => {
    // Section with only optional items — no fill pill rendered.
    const w = mountBadge({ requiredCount: 0, filledCount: 0, errorCount: 0, openQueries: 0 })
    expect(w.text()).not.toContain('0/0')
  })

  it('shows all three badges together when each signal is active', () => {
    const w = mountBadge({ requiredCount: 4, filledCount: 2, errorCount: 1, openQueries: 2 })
    expect(w.text()).toContain('2/4')
    expect(w.html()).toContain('text-rose-700')
    expect(w.text()).toContain('?2')
  })

  it('exposes the filled/required aria-label for assistive tech', () => {
    const w = mountBadge({ requiredCount: 4, filledCount: 2, errorCount: 0, openQueries: 0 })
    const aria = w.attributes('aria-label')
    expect(aria).toContain('2')
    expect(aria).toContain('4')
  })
})
