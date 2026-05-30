import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import StatusPill from '../StatusPill.vue'

describe('StatusPill', () => {
  it('renders the label from the default slot', () => {
    const wrapper = mount(StatusPill, {
      props: { variant: 'success' },
      slots: { default: 'Complete' },
    })
    expect(wrapper.text()).toBe('Complete')
  })

  it('applies the success variant classes (MUW teal)', () => {
    const wrapper = mount(StatusPill, {
      props: { variant: 'success' },
      slots: { default: 'Complete' },
    })
    const html = wrapper.html()
    expect(html).toContain('bg-muw-teal-50')
    expect(html).toContain('text-muw-teal-700')
    expect(html).toContain('bg-muw-teal-500')
  })

  it('applies the danger variant classes (rose — intentionally outside MUW palette)', () => {
    const wrapper = mount(StatusPill, {
      props: { variant: 'danger' },
      slots: { default: 'Query' },
    })
    const html = wrapper.html()
    expect(html).toContain('bg-rose-50')
    expect(html).toContain('text-rose-700')
  })

  it('hides the colour dot from assistive tech', () => {
    const wrapper = mount(StatusPill, {
      slots: { default: 'New' },
    })
    expect(wrapper.find('[aria-hidden="true"]').exists()).toBe(true)
  })

  it('shrinks padding + font + dot in compact mode', () => {
    const wrapper = mount(StatusPill, {
      props: { compact: true },
      slots: { default: 'Inv' },
    })
    const html = wrapper.html()
    expect(html).toContain('text-[10px]')
    expect(html).toContain('px-1.5')
  })

  it('defaults to the neutral variant', () => {
    const wrapper = mount(StatusPill, {
      slots: { default: 'Open' },
    })
    expect(wrapper.html()).toContain('bg-slate-100')
  })
})
