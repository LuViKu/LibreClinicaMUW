/**
 * "Auswertung starten" on a filed scan's card (EventDetailView): the existing
 * analyses, the menu of the tasks the scan lacks, and where the operator is
 * taken afterwards.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

import deMessages from '@/locales/de.json'

vi.mock('@/api/ingest', () => ({ startScanAnalysis: vi.fn() }))

// eslint-disable-next-line import/first
import { startScanAnalysis, type IngestItem } from '@/api/ingest'
// eslint-disable-next-line import/first
import { useErrorsStore } from '@/stores/errors'
// eslint-disable-next-line import/first
import ScanAnalyses from '../ScanAnalyses.vue'

const startMock = startScanAnalysis as unknown as ReturnType<typeof vi.fn>

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

function item(overrides: Partial<IngestItem> = {}): IngestItem {
  return {
    id: 55, kind: 'e2e', sourceKind: 'upload', device: null, patientId: 'EIAMD150', laterality: 'OD',
    acquisitionDate: null, acquisitionDateSource: null, modality: null, originalFilename: 'a.e2e',
    byteSize: 1, scanIndex: 0, receivedAt: null, previewUrl: '/x', hasPreview: false,
    suggestion: null, twin: null,
    analysable: true,
    analyses: [{ jobId: 9, task: 'fluid', status: 'done' }],
    ...overrides,
  }
}

let router: Router

async function mountCard(props: { item: IngestItem; canStart?: boolean; canOpen?: boolean }) {
  setActivePinia(createPinia())
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/subjects/:label/jobs/:jobId', component: { template: '<div />' } },
      { path: '/retinal-jobs/:id', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  return mount(ScanAnalyses, {
    props: { subjectLabel: 'EIAMD150', canStart: true, canOpen: true, ...props },
    global: { plugins: [router, i18n] },
  })
}

describe('ScanAnalyses', () => {
  beforeEach(() => {
    startMock.mockReset()
  })

  it('lists the scan\'s analyses, linked to their subject address', async () => {
    const w = await mountCard({ item: item() })
    const link = w.find('[data-testid="scan-analysis-link-9"]')
    expect(link.attributes('href')).toBe('/subjects/EIAMD150/jobs/9')
    expect(link.text()).toBe('Flüssigkeit')
    expect(w.find('[data-testid="scan-analyses-list"]').text()).toContain('Abgeschlossen')
  })

  it('offers only the tasks the scan does not have yet', async () => {
    const w = await mountCard({ item: item() })
    await w.find('[data-testid="scan-analyses-start"] button').trigger('click')
    const offered = w.findAll('[data-testid^="scan-analyses-task-"]').map((b) => b.attributes('data-testid'))
    expect(offered).toEqual(['ga', 'onl', 'pr', 'layers', 'sdretinanet'].map((x) => `scan-analyses-task-${x}`))
  })

  it('offers nothing to start without the permission, and lists without links when the page is closed to the role', async () => {
    const w = await mountCard({ item: item(), canStart: false, canOpen: false })
    expect(w.find('[data-testid="scan-analyses-start"]').exists()).toBe(false)
    expect(w.find('[data-testid="scan-analysis-link-9"]').exists()).toBe(false)
    expect(w.find('[data-testid="scan-analyses-list"]').text()).toContain('Flüssigkeit')
  })

  it('shows nothing for a file that is not an OCT volume', async () => {
    const w = await mountCard({ item: item({ kind: 'image', analysable: false, analyses: null }) })
    expect(w.find('[data-testid="scan-analyses-55"]').exists()).toBe(false)
  })

  it('offers nothing when the analyses could not be listed', async () => {
    const w = await mountCard({ item: item({ analyses: null }) })
    expect(w.find('[data-testid="scan-analyses-start"]').exists()).toBe(false)
  })

  it('offers nothing when the scan already has every task', async () => {
    const all = ['fluid', 'ga', 'onl', 'pr', 'layers', 'sdretinanet']
      .map((task, i) => ({ jobId: i + 1, task, status: 'done' }))
    const w = await mountCard({ item: item({ analyses: all }) })
    expect(w.find('[data-testid="scan-analyses-start"]').exists()).toBe(false)
  })

  it('starts the task and goes to the new job at its subject address', async () => {
    startMock.mockResolvedValue({ jobId: 31, task: 'ga', status: 'queued', subjectLabel: 'EIAMD150' })
    const w = await mountCard({ item: item() })
    await w.find('[data-testid="scan-analyses-start"] button').trigger('click')
    await w.find('[data-testid="scan-analyses-task-ga"]').trigger('click')
    await flushPromises()
    expect(startMock).toHaveBeenCalledWith(55, 'ga')
    expect(router.currentRoute.value.fullPath).toBe('/subjects/EIAMD150/jobs/31')
  })

  it('goes to the existing job when the server says the task is already there', async () => {
    startMock.mockImplementation(async () => { throw Object.assign(new Error('409'), {
      status: 409,
      body: { code: 'ANALYSIS_EXISTS', existingJobId: 12, subjectLabel: 'EIAMD150', message: 'x' },
    }) })
    const w = await mountCard({ item: item() })
    await w.find('[data-testid="scan-analyses-start"] button').trigger('click')
    await w.find('[data-testid="scan-analyses-task-onl"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/subjects/EIAMD150/jobs/12')
    expect(useErrorsStore().recent.length).toBe(0)
  })

  it('shows the server\'s message in the error toast otherwise', async () => {
    startMock.mockImplementation(async () => { throw Object.assign(new Error('409'), {
      status: 409,
      body: { code: 'SCAN_FILE_MISSING', message: 'The original scan file is no longer on the server' },
    }) })
    const w = await mountCard({ item: item() })
    await w.find('[data-testid="scan-analyses-start"] button').trigger('click')
    await w.find('[data-testid="scan-analyses-task-pr"]').trigger('click')
    await flushPromises()
    const recent = useErrorsStore().recent
    expect(recent.length).toBe(1)
    expect(recent[0].message).toContain('Auswertung konnte nicht gestartet werden.')
    expect(recent[0].message).toContain('HTTP 409')
    expect(recent[0].message).toContain('no longer on the server')
    expect(router.currentRoute.value.fullPath).toBe('/')
  })
})
