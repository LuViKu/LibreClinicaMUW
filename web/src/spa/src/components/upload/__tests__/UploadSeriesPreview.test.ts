/**
 * DR-041 amendment (b) — the series list of a split row: grouping by eye, ticking, the select
 * helpers, the label warning and the blocked confirm. Thumbnails are mocked; the parser itself is
 * covered by lib/__tests__/e2eSeries.test.ts.
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

vi.mock('@/lib/e2eSeries', async (orig) => ({
  ...(await orig<typeof import('@/lib/e2eSeries')>()),
  renderSeriesThumbnail: vi.fn(async () => 'data:image/png;base64,AAAA'),
}))
const api = vi.hoisted(() => ({
  resolveRows: vi.fn(),
  preflight: vi.fn(),
  commitFile: vi.fn(),
  undoItem: vi.fn(),
  undoJob: vi.fn(),
  sha256OfFile: vi.fn(),
}))
vi.mock('@/api/uploadWorkbench', () => ({
  resolveRows: (...a: unknown[]) => api.resolveRows(...a),
  preflight: (...a: unknown[]) => api.preflight(...a),
  commitFile: (...a: unknown[]) => api.commitFile(...a),
  undoItem: (...a: unknown[]) => api.undoItem(...a),
  undoJob: (...a: unknown[]) => api.undoJob(...a),
  sha256OfFile: (...a: unknown[]) => api.sha256OfFile(...a),
}))

import enMessages from '@/locales/en.json'
import deMessages from '@/locales/de.json'
import UploadRow from '@/components/upload/UploadRow.vue'
import { useUploadWorkbenchStore } from '@/stores/uploadWorkbench'
import { renderSeriesThumbnail } from '@/lib/e2eSeries'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages, de: deMessages },
})
const FIXTURES = resolve(process.cwd(), '../../../testdata/e2e-synthetic')

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(renderSeriesThumbnail).mockClear()
  for (const m of Object.values(api)) m.mockReset()
  api.preflight.mockResolvedValue({ exists: false, ingestItemId: null, jobId: null })
  api.resolveRows.mockImplementation(async (_m: string, scans: Array<{ patientId: string }>) => ({
    scans: scans.map((s) => ({
      patientId: s.patientId,
      state: 'novisit',
      candidates: [{
        studyId: 1, studyName: 'D', studyOid: 'S_1', studySubjectId: 9, subjectLabel: s.patientId,
        siteName: null, matchingEvent: null,
      }],
    })),
  }))
  api.commitFile.mockResolvedValue({ items: [], kind: 'e2e', format: 'e2e', status: 'UNBOUND', ingestItemId: 0, jobId: null })
})

async function mountRow(name = 'both-eyes-mixed', folder = 'SYNTH005') {
  const store = useUploadWorkbenchStore()
  const bytes = new Uint8Array(readFileSync(resolve(FIXTURES, `${name}.e2e`)))
  const file = new File([bytes], `${name}.E2E`)
  await store.addFiles([{ file, relativePath: `iAMD/${folder}/${name}.E2E` }])
  // the store replaces a row object on every change, like the workbench's v-for: render from the store
  const wrapper = mount({ render: () => h(UploadRow, { row: store.rows[0]!, onConfirm: (rid: string) => store.confirm(rid) }) }, { global: { plugins: [i18n] } })
  await flushPromises()
  return { store, wrapper, id: store.rows[0]!.rowId }
}

describe('series preview', () => {
  it('groups the series by eye with thumbnail, modality, frames and date/time', async () => {
    const { wrapper } = await mountRow()
    expect(wrapper.find('[data-testid="series-eye-OD"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="series-eye-OS"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="series-eye-none"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid^="series-row-"]')).toHaveLength(9)
    const first = wrapper.find('[data-testid="series-row-s6"]') // OD OCT volume, 2 B-scans
    expect(first.text()).toContain('OCT volume')
    expect(first.text()).toContain('2 frames')
    expect(first.text()).toContain('30.09.2026 10:00')
    expect(first.find('img').attributes('src')).toContain('data:image/png')
    expect(vi.mocked(renderSeriesThumbnail)).toHaveBeenCalledTimes(9)
    expect(wrapper.find('[data-testid="series-selected-count"]').text()).toBe('9 of 9 selected')
  })

  it('all are ticked by default; unticking one updates the store', async () => {
    const { wrapper, store } = await mountRow()
    const boxes = wrapper.findAll<HTMLInputElement>('input[type="checkbox"]')
    expect(boxes.every((b) => b.element.checked)).toBe(true)
    await wrapper.find('[data-testid="series-check-s6"]').setValue(false)
    expect(store.rows[0]!.excludedKeys).toEqual(['s6'])
    expect(wrapper.find('[data-testid="series-selected-count"]').text()).toBe('8 of 9 selected')
  })

  it('select none / all per eye', async () => {
    const { wrapper, store } = await mountRow()
    await wrapper.find('[data-testid="series-eye-none-OD"]').trigger('click')
    const od = store.rows[0]!.preview!.series.filter((s) => s.laterality === 'OD').map((s) => s.seriesKey)
    expect(od.length).toBeGreaterThan(1)
    expect([...store.rows[0]!.excludedKeys!].sort()).toEqual([...od].sort())
    await wrapper.find('[data-testid="series-eye-all-OD"]').trigger('click')
    expect(store.rows[0]!.excludedKeys).toEqual([])
  })

  it('select by modality toggles that modality in both eyes', async () => {
    const { wrapper, store } = await mountRow()
    await wrapper.find('[data-testid="series-modality-toggle-ICGA"]').trigger('click')
    const icga = store.rows[0]!.preview!.series.filter((s) => s.seriesModality === 'ICGA').map((s) => s.seriesKey)
    expect(icga.length).toBe(2)
    expect([...store.rows[0]!.excludedKeys!].sort()).toEqual([...icga].sort())
    await wrapper.find('[data-testid="series-modality-toggle-ICGA"]').trigger('click')
    expect(store.rows[0]!.excludedKeys).toEqual([])
  })

  it('shows the label from the file and warns when it differs from the folder', async () => {
    const same = await mountRow('both-eyes-mixed', 'SYNTH005')
    expect(same.wrapper.find('[data-testid="series-header-label"]').text()).toContain('SYNTH005')
    expect(same.wrapper.find('[data-testid="series-label-matches"]').exists()).toBe(true)
    expect(same.wrapper.find('[data-testid="series-label-differs"]').exists()).toBe(false)

    setActivePinia(createPinia())
    const other = await mountRow('both-eyes-mixed', 'SYNTH999')
    const warn = other.wrapper.find('[data-testid="series-label-differs"]')
    expect(warn.text()).toContain('SYNTH005')
    expect(warn.text()).toContain('SYNTH999')
    expect(warn.text()).toContain('inbox')
  })

  it('all unticked: says so and the confirm button is disabled; one tick enables it again', async () => {
    const { wrapper, store, id } = await mountRow('enface-multi-image', 'SYNTH001')
    const btn = () => wrapper.find<HTMLButtonElement>(`[data-testid="action-confirm-${id}"]`)
    expect(btn().element.disabled).toBe(false)
    await wrapper.find('[data-testid="series-eye-none-OD"]').trigger('click')
    await wrapper.find('[data-testid="series-eye-none-OS"]').trigger('click')
    expect(wrapper.find('[data-testid="series-none-selected"]').exists()).toBe(true)
    expect(btn().element.disabled).toBe(true)
    await btn().trigger('click')
    expect(api.commitFile).not.toHaveBeenCalled()
    await wrapper.find('[data-testid="series-eye-all-OS"]').trigger('click')
    expect(btn().element.disabled).toBe(false)
    await btn().trigger('click')
    await flushPromises()
    expect(api.commitFile).toHaveBeenCalledTimes(1)
    expect((api.commitFile.mock.calls[0]![1] as { excludeSeries: string[] }).excludeSeries.length).toBe(3)
    expect(store.rows[0]!.state).toBe('committed')
  })

  it('after the upload the list gives way to the server items and a note on what was left out', async () => {
    const { wrapper, store, id } = await mountRow('enface-multi-image', 'SYNTH001')
    await wrapper.find('[data-testid="series-check-s0"]').setValue(false)
    api.commitFile.mockResolvedValue({
      items: [{
        ingestItemId: 5, seriesKey: 's1', seriesModality: 'IRAF', laterality: 'OD', acquisitionDate: '2026-09-30',
        frames: 2, imagingModalityId: null, status: 'UNBOUND',
      }],
      excluded: ['s0'], kind: 'e2e', format: 'e2e', status: 'UNBOUND', ingestItemId: 5, jobId: null,
    })
    await wrapper.find(`[data-testid="action-confirm-${id}"]`).trigger('click')
    await flushPromises()
    expect(store.rows[0]!.state).toBe('committed')
    expect(wrapper.find('[data-testid^="series-check-"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="series-item-5"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="series-left-out-summary"]').text()).toContain('1 series left out')
    expect(wrapper.find('[data-testid="series-left-out-s0"]').text()).toContain('left out')
  })

  it('the German texts exist for every preview key', () => {
    type Tree = { uploadPortal: { split: { preview: Record<string, unknown> } } }
    const keys = (o: Record<string, unknown>, p = ''): string[] =>
      Object.entries(o).flatMap(([k, v]) =>
        v && typeof v === 'object' ? keys(v as Record<string, unknown>, `${p}${k}.`) : [`${p}${k}`])
    expect(keys((deMessages as unknown as Tree).uploadPortal.split.preview))
      .toEqual(keys((enMessages as unknown as Tree).uploadPortal.split.preview))
  })
})
