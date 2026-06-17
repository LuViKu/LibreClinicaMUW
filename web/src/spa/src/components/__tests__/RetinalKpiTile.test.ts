/**
 * Phase E.7 Wave 4 — RetinalKpiTile spec.
 *
 * Pins:
 *  - Value + unit + subtitle render verbatim.
 *  - Tone prop drives the left-border colour class.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'

import RetinalKpiTile from '../RetinalKpiTile.vue'

describe('RetinalKpiTile', () => {
  it('renders label / value / unit / subtitle', () => {
    const wrapper = mount(RetinalKpiTile, {
      props: {
        label: 'IRF',
        value: '0.123',
        unit: 'mm³',
        subtitle: 'central 1 mm',
      },
    })
    const html = wrapper.html()
    expect(html).toContain('IRF')
    expect(html).toContain('0.123')
    expect(html).toContain('mm³')
    expect(html).toContain('central 1 mm')
  })

  it('applies the IRF tone (cyan left border)', () => {
    const wrapper = mount(RetinalKpiTile, {
      props: { label: 'IRF', value: '1', unit: 'mm³', tone: 'irf' },
    })
    expect(wrapper.html()).toContain('border-l-cyan-400')
    expect(wrapper.html()).toContain('text-cyan-700')
  })

  it('applies the SRF tone (amber)', () => {
    const wrapper = mount(RetinalKpiTile, {
      props: { label: 'SRF', value: '1', unit: 'mm³', tone: 'srf' },
    })
    expect(wrapper.html()).toContain('border-l-amber-400')
  })

  it('applies the PED tone (fuchsia/magenta)', () => {
    const wrapper = mount(RetinalKpiTile, {
      props: { label: 'PED', value: '1', unit: 'mm³', tone: 'ped' },
    })
    expect(wrapper.html()).toContain('border-l-fuchsia-400')
  })

  it('applies the GA tone (pink)', () => {
    const wrapper = mount(RetinalKpiTile, {
      props: { label: 'GA', value: '1', unit: 'mm²', tone: 'ga' },
    })
    expect(wrapper.html()).toContain('border-l-pink-400')
  })

  it('applies the thickness tone (sky)', () => {
    const wrapper = mount(RetinalKpiTile, {
      props: { label: 'ONL', value: '1', unit: 'µm', tone: 'thickness' },
    })
    expect(wrapper.html()).toContain('border-l-sky-400')
  })

  it('defaults to the neutral tone (slate)', () => {
    const wrapper = mount(RetinalKpiTile, {
      props: { label: 'Total', value: '1', unit: 'mm³' },
    })
    expect(wrapper.html()).toContain('border-l-slate-300')
  })

  it('skips the subtitle row when not supplied', () => {
    const wrapper = mount(RetinalKpiTile, {
      props: { label: 'IRF', value: '1', unit: 'mm³' },
    })
    // Subtitle div has class `text-[11px]` — absent on this render.
    expect(wrapper.html()).not.toContain('text-[11px]')
  })
})
