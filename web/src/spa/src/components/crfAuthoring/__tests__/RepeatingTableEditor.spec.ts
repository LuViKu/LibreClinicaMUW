import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import RepeatingTableEditor from '@/components/crfAuthoring/RepeatingTableEditor.vue'
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

function mountEditor() {
  return mount(RepeatingTableEditor, {
    props: {
      spec: {
        minRows: 2,
        maxRows: 8,
        columns: [{ key: 'med', label: 'Medikament', type: 'text' }],
      },
    },
    global: {
      plugins: [i18n],
      stubs: { TerminologyBindingEditor: true },
    },
  })
}

describe('RepeatingTableEditor', () => {
  it('keeps minRows/maxRows internally consistent when either bound is edited', async () => {
    const w = mountEditor()

    const minInput = w.find('[data-testid="crf-canvas-table-minRows"]')
    ;(minInput.element as HTMLInputElement).value = '12'
    await minInput.trigger('input')
    expect(w.emitted('update:spec')?.[0]?.[0]).toMatchObject({ minRows: 8, maxRows: 8 })

    const maxInput = w.find('[data-testid="crf-canvas-table-maxRows"]')
    ;(maxInput.element as HTMLInputElement).value = '1'
    await maxInput.trigger('input')
    expect(w.emitted('update:spec')?.[1]?.[0]).toMatchObject({ minRows: 2, maxRows: 2 })
  })
})
