import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CheckboxArrayInput from '../CheckboxArrayInput.vue'
import type { ResponseOption } from '@/types/crf'

const OPTIONS: ResponseOption[] = [
  { code: 'OD', label: 'Right eye' },
  { code: 'OS', label: 'Left eye' },
  { code: 'OU', label: 'Both eyes' },
]

describe('CheckboxArrayInput', () => {
  it('renders one labelled checkbox per option', () => {
    const wrapper = mount(CheckboxArrayInput, {
      props: { modelValue: [], options: OPTIONS, idPrefix: 'eye' },
    })
    const boxes = wrapper.findAll('input[type="checkbox"]')
    expect(boxes).toHaveLength(3)
    expect(wrapper.text()).toContain('Right eye')
    expect(wrapper.text()).toContain('Left eye')
    expect(wrapper.text()).toContain('Both eyes')
  })

  it('marks the boxes whose codes are in modelValue', () => {
    const wrapper = mount(CheckboxArrayInput, {
      props: { modelValue: ['OS'], options: OPTIONS, idPrefix: 'eye' },
    })
    const checks = wrapper.findAll<HTMLInputElement>('input[type="checkbox"]')
    expect(checks[0].element.checked).toBe(false)
    expect(checks[1].element.checked).toBe(true)
    expect(checks[2].element.checked).toBe(false)
  })

  it('emits update:modelValue in schema order when a box is toggled', async () => {
    const wrapper = mount(CheckboxArrayInput, {
      props: { modelValue: ['OS'], options: OPTIONS, idPrefix: 'eye' },
    })
    const boxes = wrapper.findAll<HTMLInputElement>('input[type="checkbox"]')
    // Toggle OU first (last option) — selection order should NOT reverse.
    await boxes[2].setValue(true)
    const emits = wrapper.emitted('update:modelValue')!
    expect(emits[0]).toEqual([['OS', 'OU']])
    // Toggle OD on — order becomes OD, OS, OU (schema order, not click order).
    await wrapper.setProps({ modelValue: ['OS', 'OU'] })
    await boxes[0].setValue(true)
    const emits2 = wrapper.emitted('update:modelValue')!
    expect(emits2[emits2.length - 1]).toEqual([['OD', 'OS', 'OU']])
  })
})
