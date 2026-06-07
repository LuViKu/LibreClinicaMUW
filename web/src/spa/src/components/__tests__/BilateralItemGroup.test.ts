/**
 * Phase E.6 ophth-bilateral — BilateralItemGroup layout spec.
 *
 * The component test is the load-bearing one: it locks the
 * OD-on-the-LEFT, OS-on-the-RIGHT convention into the DOM so a
 * later CSS refactor (or a well-meaning rewrite of the grid) can't
 * silently swap eyes on the entry form. Reversing the columns would
 * map "patient's right eye" data into "OS" on the audit trail and
 * vice versa — a clinical safety issue.
 *
 * Also covers:
 *  - OU (both-eyes) row spans both eye columns
 *  - OD-only / OS-only fallback layouts surface the corresponding tell
 *  - BL items render as a styled checkbox via the widget slot
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import BilateralItemGroup from '../BilateralItemGroup.vue'
import CrfItemWidget from '../CrfItemWidget.vue'
import type { BilateralRow } from '../bilateral'
import type { CrfItem } from '@/types/crf'
import enMessages from '@/locales/en.json'
import deMessages from '@/locales/de.json'

function mkItem(oid: string, label: string, dataType: CrfItem['dataType'] = 'string'): CrfItem {
  return {
    oid,
    label,
    dataType,
    required: false,
  } as unknown as CrfItem
}

function mkI18n(locale: 'en' | 'de' = 'en') {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'en',
    messages: { en: enMessages, de: deMessages },
  })
}

describe('BilateralItemGroup — layout', () => {
  it('renders OD widget in the LEFT cell + OS widget in the RIGHT cell', () => {
    const od = mkItem('OD_BCVA_LETTERS', 'OD BCVA letters', 'integer')
    const os = mkItem('OS_BCVA_LETTERS', 'OS BCVA letters', 'integer')
    const row: BilateralRow = {
      kind: 'bilateral',
      key: 'BCVA_LETTERS',
      label: 'BCVA letters',
      od,
      os,
    }

    const wrapper = mount(BilateralItemGroup, {
      global: { plugins: [mkI18n()] },
      props: { row },
      slots: {
        widget: `<template #default="{ item, side }">
                   <span class="widget-stub" :data-side="side" :data-oid="item.oid">{{ item.oid }}</span>
                 </template>`,
      },
    })

    const cells = wrapper.findAll('[data-bilateral-cell]')
    expect(cells.length).toBe(2)
    // DOM order is left-to-right; the OD cell must come first.
    expect(cells[0].attributes('data-bilateral-cell')).toBe('OD')
    expect(cells[1].attributes('data-bilateral-cell')).toBe('OS')

    // And the OD slot is bound to the OD_… item, OS slot to OS_….
    const odWidget = cells[0].find('.widget-stub')
    const osWidget = cells[1].find('.widget-stub')
    expect(odWidget.attributes('data-oid')).toBe('OD_BCVA_LETTERS')
    expect(osWidget.attributes('data-oid')).toBe('OS_BCVA_LETTERS')
    expect(odWidget.attributes('data-side')).toBe('OD')
    expect(osWidget.attributes('data-side')).toBe('OS')
  })

  it('shows an "OD only" tell when the OS pair is missing', () => {
    const row: BilateralRow = {
      kind: 'bilateral',
      key: 'BCVA',
      label: 'BCVA',
      od: mkItem('OD_BCVA', 'OD BCVA', 'integer'),
      os: null,
    }
    const wrapper = mount(BilateralItemGroup, {
      global: { plugins: [mkI18n()] },
      props: { row },
      slots: { widget: '<span class="widget-stub"></span>' },
    })
    expect(wrapper.text()).toContain('OD only')
    // The OS cell exists but has the missing-data fallback marker, not
    // a widget — the cell is rendered so the grid layout doesn't
    // collapse.
    const cells = wrapper.findAll('[data-bilateral-cell]')
    expect(cells[1].attributes('data-bilateral-cell')).toBe('OS')
    expect(cells[1].find('.widget-stub').exists()).toBe(false)
  })

  it('shows an "OS only" tell when the OD pair is missing', () => {
    const row: BilateralRow = {
      kind: 'bilateral',
      key: 'BCVA',
      label: 'BCVA',
      od: null,
      os: mkItem('OS_BCVA', 'OS BCVA', 'integer'),
    }
    const wrapper = mount(BilateralItemGroup, {
      global: { plugins: [mkI18n()] },
      props: { row },
      slots: { widget: '<span class="widget-stub"></span>' },
    })
    expect(wrapper.text()).toContain('OS only')
    const cells = wrapper.findAll('[data-bilateral-cell]')
    expect(cells[0].attributes('data-bilateral-cell')).toBe('OD')
    expect(cells[0].find('.widget-stub').exists()).toBe(false)
  })

  /* Phase E.6 polish-runtime — per-eye hide via show-when. */
  it('collapses the OD column to the missing-data fallback when hiddenOd=true', () => {
    const row: BilateralRow = {
      kind: 'bilateral',
      key: 'BCVA',
      label: 'BCVA',
      od: mkItem('OD_BCVA', 'OD BCVA', 'integer'),
      os: mkItem('OS_BCVA', 'OS BCVA', 'integer'),
    }
    const wrapper = mount(BilateralItemGroup, {
      global: { plugins: [mkI18n()] },
      props: { row, hiddenOd: true },
      slots: { widget: '<span class="widget-stub"></span>' },
    })
    const cells = wrapper.findAll('[data-bilateral-cell]')
    expect(cells[0].attributes('data-bilateral-cell')).toBe('OD')
    // OD cell has no widget rendered — it's collapsed.
    expect(cells[0].find('.widget-stub').exists()).toBe(false)
    // OS cell still renders the widget.
    expect(cells[1].attributes('data-bilateral-cell')).toBe('OS')
    expect(cells[1].find('.widget-stub').exists()).toBe(true)
  })

  it('collapses the OS column when hiddenOs=true', () => {
    const row: BilateralRow = {
      kind: 'bilateral',
      key: 'BCVA',
      label: 'BCVA',
      od: mkItem('OD_BCVA', 'OD BCVA', 'integer'),
      os: mkItem('OS_BCVA', 'OS BCVA', 'integer'),
    }
    const wrapper = mount(BilateralItemGroup, {
      global: { plugins: [mkI18n()] },
      props: { row, hiddenOs: true },
      slots: { widget: '<span class="widget-stub"></span>' },
    })
    const cells = wrapper.findAll('[data-bilateral-cell]')
    expect(cells[0].find('.widget-stub').exists()).toBe(true)
    expect(cells[1].find('.widget-stub').exists()).toBe(false)
  })

  it('renders an OU row as a single both-eyes cell that spans both eye columns', () => {
    const item = mkItem('OU_VISION_DESCRIPTION', 'Vision — bilateral narrative', 'string')
    const row: BilateralRow = {
      kind: 'both-eyes',
      key: 'VISION_DESCRIPTION',
      label: 'Vision — bilateral narrative',
      item,
    }
    const wrapper = mount(BilateralItemGroup, {
      global: { plugins: [mkI18n()] },
      props: { row },
      slots: {
        widget: `<template #default="{ item, side }">
                   <span class="widget-stub" :data-side="side" :data-oid="item.oid"/>
                 </template>`,
      },
    })
    expect(wrapper.text()).toContain('Both eyes')
    const cells = wrapper.findAll('[data-bilateral-cell]')
    // Only one widget cell (OD/OS cells collapse into a single OU cell).
    expect(cells.length).toBe(1)
    expect(cells[0].attributes('data-bilateral-cell')).toBe('OU')
    expect(cells[0].find('.widget-stub').attributes('data-oid')).toBe('OU_VISION_DESCRIPTION')
  })

  it('renders German column tells when the locale is de', () => {
    const row: BilateralRow = {
      kind: 'bilateral',
      key: 'BCVA',
      label: 'BCVA',
      od: mkItem('OD_BCVA', 'OD BCVA', 'integer'),
      os: null,
    }
    const wrapper = mount(BilateralItemGroup, {
      global: { plugins: [mkI18n('de')] },
      props: { row },
      slots: { widget: '<span class="widget-stub"></span>' },
    })
    expect(wrapper.text()).toContain('nur OD')
  })
})

describe('CrfItemWidget — BL rendering', () => {
  it('renders a boolean item as a Yes/No radio pair', async () => {
    const item = mkItem('OD_CATARACT_PRESENT', 'Cataract present (OD)', 'boolean')

    const wrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n()] },
      props: { item, modelValue: '', suppressLabel: true },
    })

    const radios = wrapper.findAll<HTMLInputElement>('input[type="radio"]')
    expect(radios.length).toBe(2)
    expect(radios[0]!.element.value).toBe('1')
    expect(radios[1]!.element.value).toBe('0')
    // Empty modelValue → neither selected (unanswered state).
    expect(radios[0]!.element.checked).toBe(false)
    expect(radios[1]!.element.checked).toBe(false)
    expect(wrapper.text()).toContain('No')
    expect(wrapper.text()).toContain('Yes')

    await radios[0]!.setValue('1')
    const emits = wrapper.emitted('update:modelValue')
    expect(emits).toBeTruthy()
    expect(emits?.[0]?.[0]).toBe('1')

    // Re-mount with modelValue='1' to confirm Yes is selected.
    const onWrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n()] },
      props: { item, modelValue: '1', suppressLabel: true },
    })
    const onRadios = onWrapper.findAll<HTMLInputElement>('input[type="radio"]')
    expect(onRadios[0]!.element.checked).toBe(true)
    expect(onRadios[1]!.element.checked).toBe(false)
  })

  it('renders German Ja/Nein for a boolean item under the de locale', () => {
    const item = mkItem('OD_CATARACT_PRESENT', 'Katarakt vorhanden (OD)', 'boolean')
    const wrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n('de')] },
      props: { item, modelValue: '1', suppressLabel: true },
    })
    expect(wrapper.text()).toContain('Ja')
  })
})
