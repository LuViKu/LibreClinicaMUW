/**
 * ResponseOptionsEditor spec.
 *
 * The component is fully controlled — it holds no row state — so every
 * case here asserts on the emitted replacement set rather than on
 * internal state. That contract is the fix for the stale-rows bug the
 * orphaned ResponseSetPicker would have brought into the rail (one
 * instance serves the whole canvas, selection changes on every click).
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import ResponseOptionsEditor from '@/components/crfAuthoring/ResponseOptionsEditor.vue'
import type {
  AuthoringDataType,
  AuthoringResponseSet,
  AuthoringResponseType,
  ResponseSetCatalogEntry,
  ResponseSetOption,
} from '@/stores/crfAuthoring'
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

interface MountOpts {
  modelValue?: AuthoringResponseSet
  responseType?: AuthoringResponseType
  dataType?: AuthoringDataType
  catalog?: ResponseSetCatalogEntry[]
}

const inline = (options: ResponseSetOption[], label = ''): AuthoringResponseSet => ({
  type: 'single-select',
  label,
  options,
})

function mountEditor(opts: MountOpts = {}) {
  return mount(ResponseOptionsEditor, {
    global: { plugins: [i18n] },
    props: {
      modelValue: opts.modelValue ?? inline([{ text: '', value: '' }, { text: '', value: '' }]),
      responseType: opts.responseType ?? 'single-select',
      dataType: opts.dataType ?? 'ST',
      catalog: opts.catalog ?? [],
    },
  })
}

/** Last emitted response set, narrowed to the inline branch. */
function lastEmitted(w: ReturnType<typeof mountEditor>) {
  const events = w.emitted('update:modelValue')
  expect(events).toBeTruthy()
  return events![events!.length - 1]![0] as {
    type: string
    label: string
    options: ResponseSetOption[]
  }
}

describe('ResponseOptionsEditor', () => {
  it('renders one row per option', () => {
    const w = mountEditor({
      modelValue: inline([
        { text: 'Ja', value: 'JA' },
        { text: 'Nein', value: 'NEIN' },
        { text: 'Unbekannt', value: 'UNBEKANNT' },
      ]),
    })
    expect(w.findAll('[data-testid="crf-canvas-properties-options-row"]')).toHaveLength(3)
  })

  it('appends a blank row on add', async () => {
    const w = mountEditor({ modelValue: inline([{ text: 'Ja', value: 'JA' }]) })
    await w.find('[data-testid="crf-canvas-properties-options-add"]').trigger('click')
    const next = lastEmitted(w)
    expect(next.options).toHaveLength(2)
    expect(next.options[1]).toEqual({ text: '', value: '' })
  })

  it('removes the addressed row', async () => {
    const w = mountEditor({
      modelValue: inline([
        { text: 'Ja', value: 'JA' },
        { text: 'Nein', value: 'NEIN' },
      ]),
    })
    await w.find('[data-testid="crf-canvas-properties-options-remove-0"]').trigger('click')
    expect(lastEmitted(w).options).toEqual([{ text: 'Nein', value: 'NEIN' }])
  })

  it('disables removal of the last remaining row', () => {
    const w = mountEditor({ modelValue: inline([{ text: 'Ja', value: 'JA' }]) })
    const btn = w.find('[data-testid="crf-canvas-properties-options-remove-0"]')
    expect((btn.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('auto-derives the stored value from the display text', async () => {
    const w = mountEditor({ modelValue: inline([{ text: '', value: '' }]) })
    const input = w.find('[data-testid="crf-canvas-properties-options-text-0"]')
    ;(input.element as HTMLInputElement).value = 'Nicht zutreffend'
    await input.trigger('input')
    expect(lastEmitted(w).options[0]).toEqual({
      text: 'Nicht zutreffend',
      value: 'NICHT_ZUTREFFEND',
    })
  })

  it('stops auto-deriving once the operator sets their own value', async () => {
    // Sticky-override contract, same as the rail's name → OID behaviour.
    const w = mountEditor({ modelValue: inline([{ text: 'Ja', value: 'CUSTOM' }]) })
    const input = w.find('[data-testid="crf-canvas-properties-options-text-0"]')
    ;(input.element as HTMLInputElement).value = 'Jawohl'
    await input.trigger('input')
    expect(lastEmitted(w).options[0]).toEqual({ text: 'Jawohl', value: 'CUSTOM' })
  })

  it('uses 1-based ordinals as values for numeric data types', async () => {
    // CrfJsonValidator requires option values on INT/REAL items to parse
    // as numbers, so a slug would be rejected server-side.
    const w = mountEditor({
      dataType: 'INT',
      modelValue: inline([{ text: '', value: '' }, { text: '', value: '' }]),
    })
    const input = w.find('[data-testid="crf-canvas-properties-options-text-1"]')
    ;(input.element as HTMLInputElement).value = 'Mittel'
    await input.trigger('input')
    expect(lastEmitted(w).options[1]).toEqual({ text: 'Mittel', value: '2' })
  })

  it('warns while every row is still blank', async () => {
    const w = mountEditor({ modelValue: inline([{ text: '', value: '' }]) })
    expect(w.find('[data-testid="crf-canvas-properties-options-warning"]').exists()).toBe(true)
    await w.setProps({ modelValue: inline([{ text: 'Ja', value: 'JA' }]) })
    expect(w.find('[data-testid="crf-canvas-properties-options-warning"]').exists()).toBe(false)
  })

  it('re-renders rows when modelValue swaps to another item', async () => {
    // The regression the orphaned ResponseSetPicker would have carried in:
    // it copies props into local state once and never re-syncs.
    const w = mountEditor({ modelValue: inline([{ text: 'Ja', value: 'JA' }]) })
    expect(w.findAll('[data-testid="crf-canvas-properties-options-row"]')).toHaveLength(1)
    await w.setProps({
      modelValue: inline([
        { text: 'W', value: 'F' },
        { text: 'M', value: 'M' },
        { text: 'Divers', value: 'I' },
      ]),
    })
    const texts = w
      .findAll('[data-testid="crf-canvas-properties-options-row"] input')
      .map((i) => (i.element as HTMLInputElement).value)
    expect(texts).toContain('W')
    expect(texts).not.toContain('Ja')
  })

  it('copies a catalog entry in rather than linking to it', async () => {
    const catalog: ResponseSetCatalogEntry[] = [
      {
        label: 'ja_nein',
        responseType: 'single-select',
        options: [
          { text: 'Ja', value: 'JA' },
          { text: 'Nein', value: 'NEIN' },
        ],
        usageCount: 3,
        inActiveStudy: true,
      },
    ]
    const w = mountEditor({ catalog })
    const select = w.find('[data-testid="crf-canvas-properties-options-catalog"]')
    ;(select.element as HTMLSelectElement).value = 'ja_nein'
    await select.trigger('change')
    const next = lastEmitted(w)
    // Inline, NOT { ref: ... } — the backend adapter never implemented
    // ref resolution and silently degrades it to a plain text field.
    expect(next.options).toEqual(catalog[0]!.options)
    expect(next.label).toBe('ja_nein')
    expect('ref' in next).toBe(false)
  })

  it('offers a conversion instead of a dead end for a ref-linked set', async () => {
    const catalog: ResponseSetCatalogEntry[] = [
      {
        label: 'yes_no',
        responseType: 'single-select',
        options: [{ text: 'Yes', value: '1' }],
        usageCount: 1,
        inActiveStudy: false,
      },
    ]
    const w = mountEditor({ modelValue: { ref: { label: 'yes_no' } }, catalog })
    expect(w.findAll('[data-testid="crf-canvas-properties-options-row"]')).toHaveLength(0)
    await w.find('[data-testid="crf-canvas-properties-options-detach"]').trigger('click')
    expect(lastEmitted(w).options).toEqual([{ text: 'Yes', value: '1' }])
  })
})
