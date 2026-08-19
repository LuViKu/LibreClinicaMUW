/**
 * #26 (2026-08-12) — RepeatingTablePreview spec.
 *
 * Pins the repeating-table entry contract: columns render as headers, rows
 * seed to the declared minimum, add/remove respect min/max, and — the
 * crown jewel — picking a terminology suggestion in an autocomplete column
 * fans its properties into the mapped sibling cells of the SAME row
 * (explicit property→field fill, the 2026-08-12 design decision).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, DOMWrapper, enableAutoUnmount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn() }
})

import { apiGet } from '@/api/client'
import RepeatingTablePreview from '@/components/RepeatingTablePreview.vue'
import type { RepeatingTableSpec } from '@/stores/crfAuthoring'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const MED_SPEC: RepeatingTableSpec = {
  minRows: 1,
  maxRows: 3,
  columns: [
    {
      key: 'med',
      label: 'Medikament',
      type: 'text',
      autocomplete: { system: 'medication', fills: [{ fromProperty: 'strength', toKey: 'dose' }, { fromProperty: 'unit', toKey: 'unit' }] },
    },
    { key: 'dose', label: 'Dosis', type: 'text' },
    { key: 'unit', label: 'Einheit', type: 'text' },
  ],
}

function mountTable(modelValue: Record<string, string>[] | null = null) {
  return mount(RepeatingTablePreview, {
    props: { spec: MED_SPEC, idPrefix: 'tbl', modelValue },
    global: { plugins: [i18n] },
  })
}

// A diagnosis table: ICD-10 autocomplete → fills the code column, plus a
// laterality column that should only apply to EYE diagnoses (ICD H00–H59).
const DIAG_SPEC: RepeatingTableSpec = {
  minRows: 1,
  maxRows: 3,
  columns: [
    {
      key: 'dx',
      label: 'Diagnose',
      type: 'text',
      autocomplete: { system: 'icd10gm', fills: [{ fromProperty: 'code', toKey: 'icd' }] },
    },
    { key: 'icd', label: 'ICD-Code', type: 'text' },
    { key: 'side', label: 'Auge', type: 'laterality' },
  ],
}

function mountDiag(modelValue: Record<string, string>[] | null = null) {
  return mount(RepeatingTablePreview, {
    props: { spec: DIAG_SPEC, idPrefix: 'tbl', modelValue },
    global: { plugins: [i18n] },
  })
}

// A diagnosis table with NO dedicated ICD-Code column — the code is never
// stored in a visible cell, so eye-gating must lean on the code remembered
// from the pick.
const DIAG_NOCODE_SPEC: RepeatingTableSpec = {
  minRows: 1,
  maxRows: 3,
  columns: [
    { key: 'dx', label: 'Diagnose', type: 'text', autocomplete: { system: 'icd10gm', fills: [] } },
    { key: 'side', label: 'Auge', type: 'laterality' },
  ],
}

function mountNoCode(modelValue: Record<string, string>[] | null = null) {
  return mount(RepeatingTablePreview, {
    props: { spec: DIAG_NOCODE_SPEC, idPrefix: 'tbl', modelValue },
    global: { plugins: [i18n] },
  })
}

enableAutoUnmount(afterEach)
beforeEach(() => vi.mocked(apiGet).mockReset())

describe('RepeatingTablePreview', () => {
  it('renders one header per column and seeds the minimum rows', () => {
    const w = mountTable()
    expect(w.findAll('thead th').length).toBe(4) // 3 columns + action column
    expect(w.findAll('[data-testid="repeating-table-row"]').length).toBe(1)
  })

  it('adds a row up to maxRows then stops', async () => {
    const w = mountTable([{ med: '', dose: '', unit: '' }])
    const add = w.get('[data-testid="repeating-table-add-row"]')
    await add.trigger('click')
    let rows = w.emitted('update:modelValue')!.at(-1)![0] as unknown[]
    expect(rows.length).toBe(2)
    await w.setProps({ modelValue: rows as Record<string, string>[] })
    await add.trigger('click')
    rows = w.emitted('update:modelValue')!.at(-1)![0] as unknown[]
    expect(rows.length).toBe(3)
    await w.setProps({ modelValue: rows as Record<string, string>[] })
    // at max — the add button disables
    expect((w.get('[data-testid="repeating-table-add-row"]').element as HTMLButtonElement).disabled).toBe(true)
  })

  it('fans picked properties into mapped sibling cells of the same row', async () => {
    vi.mocked(apiGet).mockResolvedValue([
      { code: 'B01AC06', display: 'Acetylsalicylsäure', properties: '{"strength":"100","unit":"mg"}' },
    ])
    const w = mountTable([{ med: '', dose: '', unit: '' }])
    await w.find('[data-testid="terminology-autocomplete-input"]').setValue('aspir')
    await new Promise((r) => setTimeout(r, 260))
    await flushPromises()
    // The results list is teleported to <body>.
    await new DOMWrapper(document.body).find('[role="option"]').trigger('mousedown')

    const rows = w.emitted('update:modelValue')!.at(-1)![0] as Record<string, string>[]
    expect(rows[0]).toEqual({ med: 'Acetylsalicylsäure', dose: '100', unit: 'mg' })
  })

  it('disables the laterality cell until the row carries an eye ICD code', () => {
    const w = mountDiag([{ dx: '', icd: '', side: '' }])
    expect((w.get('#tbl-r0-side').element as HTMLSelectElement).disabled).toBe(true)
  })

  it('enables the laterality cell for an eye ICD code (H00–H59)', () => {
    const w = mountDiag([{ dx: 'Glaukom', icd: 'H40', side: 'OD' }])
    const sel = w.get('#tbl-r0-side').element as HTMLSelectElement
    expect(sel.disabled).toBe(false)
    expect(sel.value).toBe('OD')
  })

  it('keeps the laterality cell disabled for a non-eye ICD code (e.g. ear H60+)', () => {
    const w = mountDiag([{ dx: 'Otitis', icd: 'H66', side: '' }])
    expect((w.get('#tbl-r0-side').element as HTMLSelectElement).disabled).toBe(true)
  })

  it('clears the laterality cell when a non-eye diagnosis is picked', async () => {
    vi.mocked(apiGet).mockResolvedValue([
      { code: 'E11', display: 'Diabetes mellitus Typ 2', properties: '{}' },
    ])
    const w = mountDiag([{ dx: '', icd: '', side: 'OD' }])
    await w.find('[data-testid="terminology-autocomplete-input"]').setValue('diab')
    await new Promise((r) => setTimeout(r, 260))
    await flushPromises()
    await new DOMWrapper(document.body).find('[role="option"]').trigger('mousedown')

    const rows = w.emitted('update:modelValue')!.at(-1)![0] as Record<string, string>[]
    expect(rows[0]!.icd).toBe('E11')
    expect(rows[0]!.side).toBe('')
  })

  // --- Eye-gating WITHOUT a dedicated ICD-Code column (code remembered from
  //     the pick) ---

  it('enables laterality after picking an eye diagnosis, with no ICD-Code column', async () => {
    vi.mocked(apiGet).mockResolvedValue([{ code: 'H40', display: 'Glaukom', properties: '{}' }])
    const w = mountNoCode([{ dx: '', side: '' }])
    await w.find('[data-testid="terminology-autocomplete-input"]').setValue('glauk')
    await new Promise((r) => setTimeout(r, 260))
    await flushPromises()
    await new DOMWrapper(document.body).find('[role="option"]').trigger('mousedown')
    // Feed the emitted rows back (controlled component) so the cell re-renders.
    await w.setProps({ modelValue: w.emitted('update:modelValue')!.at(-1)![0] as Record<string, string>[] })
    expect((w.get('#tbl-r0-side').element as HTMLSelectElement).disabled).toBe(false)
  })

  it('disables + clears laterality after picking a non-eye diagnosis, with no ICD-Code column', async () => {
    vi.mocked(apiGet).mockResolvedValue([{ code: 'E11', display: 'Diabetes mellitus Typ 2', properties: '{}' }])
    const w = mountNoCode([{ dx: '', side: 'OD' }])
    await w.find('[data-testid="terminology-autocomplete-input"]').setValue('diab')
    await new Promise((r) => setTimeout(r, 260))
    await flushPromises()
    await new DOMWrapper(document.body).find('[role="option"]').trigger('mousedown')
    const rows = w.emitted('update:modelValue')!.at(-1)![0] as Record<string, string>[]
    expect(rows[0]!.side).toBe('')
    await w.setProps({ modelValue: rows })
    expect((w.get('#tbl-r0-side').element as HTMLSelectElement).disabled).toBe(true)
  })

  it('leaves laterality available for a freehand diagnosis with no code', () => {
    const w = mountNoCode([{ dx: 'Netzhautablösung (freihand)', side: '' }])
    expect((w.get('#tbl-r0-side').element as HTMLSelectElement).disabled).toBe(false)
  })

  it('keeps laterality disabled until a diagnosis is entered (no code column)', () => {
    const w = mountNoCode([{ dx: '', side: '' }])
    expect((w.get('#tbl-r0-side').element as HTMLSelectElement).disabled).toBe(true)
  })
})
