/**
 * DR-041 — a split row lists the series the server made of its file.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import enMessages from '@/locales/en.json'
import UploadRow from '@/components/upload/UploadRow.vue'
import type { UploadRow as Row } from '@/stores/uploadWorkbench'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function row(extra: Partial<Row> = {}): Row {
  return {
    rowId: 'r1', file: new File(['x'], 'EIAMD01D.E2E'), kind: 'e2e', format: 'e2e', split: true,
    relativePath: 'iAMD/EIAMD005/EIAMD01D.E2E', patientId: 'EIAMD005', date: null, laterality: null, state: 'committed',
    items: [
      { ingestItemId: 11, seriesKey: 's1', seriesModality: 'OCT', laterality: 'OD', acquisitionDate: '2026-09-30', frames: 97, imagingModalityId: null, status: 'UNBOUND' },
      { ingestItemId: 12, seriesKey: 's2', seriesModality: 'ICGA', laterality: 'OS', acquisitionDate: '2026-09-30', frames: 40, imagingModalityId: null, status: 'UNBOUND' },
      { ingestItemId: 13, seriesKey: 's3', seriesModality: 'ICGA', laterality: 'OS', acquisitionDate: null, frames: null, imagingModalityId: null, status: 'MYSTERY' },
    ],
    ...extra,
  }
}

function render(r: Row) {
  setActivePinia(createPinia())
  return mount(UploadRow, { props: { row: r }, global: { plugins: [i18n] } })
}

describe('UploadRow series list', () => {
  it('lists each series with modality, eye, frames and status, and a count summary', () => {
    const w = render(row())
    expect(w.find('[data-testid="series-summary"]').text()).toBe('3 series · 1 OCT volume · 2 ICGA')
    const first = w.find('[data-testid="series-item-11"]').text()
    expect(first).toContain('OCT volume')
    expect(first).toContain('OD')
    expect(first).toContain('97 frames')
    expect(first).toContain('In inbox')
    expect(w.find('[data-testid="series-item-13"]').text()).toContain('MYSTERY') // an unknown status is shown as the server said it
  })

  it('explains a header that differs from the folder', () => {
    const w = render(row({ headerPatient: 'differs', parked: true }))
    expect(w.find('[data-testid="series-header-differs"]').text()).toContain('differs from the folder name')
    expect(w.find('[data-testid="series-parked"]').exists()).toBe(false)
  })

  it('says when everything is in the inbox for another reason', () => {
    const w = render(row({ headerPatient: 'empty', parked: true }))
    expect(w.find('[data-testid="series-parked"]').exists()).toBe(true)
  })

  it('shows nothing for a row that has not been sent', () => {
    const w = render(row({ state: 'suggested', items: undefined }))
    expect(w.find('[data-testid="series-list-r1"]').exists()).toBe(false)
  })
})
