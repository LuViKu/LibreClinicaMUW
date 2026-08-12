/**
 * #26 (2026-08-12) — RepeatingTablePreview spec.
 *
 * Pins the repeating-table entry contract: columns render as headers, rows
 * seed to the declared minimum, add/remove respect min/max, and — the
 * crown jewel — picking a terminology suggestion in an autocomplete column
 * fans its properties into the mapped sibling cells of the SAME row
 * (explicit property→field fill, the 2026-08-12 design decision).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
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
    await w.find('[role="option"]').trigger('mousedown')

    const rows = w.emitted('update:modelValue')!.at(-1)![0] as Record<string, string>[]
    expect(rows[0]).toEqual({ med: 'B01AC06 — Acetylsalicylsäure', dose: '100', unit: 'mg' })
  })
})
