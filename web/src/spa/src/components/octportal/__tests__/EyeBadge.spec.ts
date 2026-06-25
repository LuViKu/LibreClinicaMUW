/**
 * Phase E retinal-inference (Wave C) — EyeBadge spec.
 *
 * Verifies the institutional ophthalmology convention is honoured:
 *  - OD (right eye) → teal palette, letter "R"
 *  - OS (left eye)  → sky palette,  letter "L"
 * See the [[reference_ophth_laterality]] memory note for the
 * background; the badge surfaces both the medical short-code and
 * the colloquial letter so non-ophth operators read the row right.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'

import EyeBadge from '../EyeBadge.vue'

describe('EyeBadge', () => {
  it('renders OD with teal palette + R letter', () => {
    const w = mount(EyeBadge, { props: { laterality: 'OD' } })
    expect(w.text()).toContain('R')
    expect(w.text()).toContain('OD')
    const letterSpan = w.find('[data-testid="eye-badge-letter-OD"]')
    expect(letterSpan.exists()).toBe(true)
    expect(letterSpan.classes()).toContain('bg-muw-teal-50')
    expect(letterSpan.classes()).toContain('text-muw-teal-700')
  })

  it('renders OS with sky palette + L letter', () => {
    const w = mount(EyeBadge, { props: { laterality: 'OS' } })
    expect(w.text()).toContain('L')
    expect(w.text()).toContain('OS')
    const letterSpan = w.find('[data-testid="eye-badge-letter-OS"]')
    expect(letterSpan.exists()).toBe(true)
    expect(letterSpan.classes()).toContain('bg-muw-sky-50')
    expect(letterSpan.classes()).toContain('text-muw-sky-700')
  })

  it('renders an em-dash for null laterality', () => {
    const w = mount(EyeBadge, { props: { laterality: null } })
    expect(w.text()).toBe('—')
    expect(w.find('[data-testid="eye-badge-letter-OD"]').exists()).toBe(false)
    expect(w.find('[data-testid="eye-badge-letter-OS"]').exists()).toBe(false)
  })
})
