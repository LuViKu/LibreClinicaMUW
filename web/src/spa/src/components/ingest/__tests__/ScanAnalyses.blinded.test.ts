/**
 * "Auswertung starten" for a session that may start an analysis but may not
 * open the job page (a treating clinician in a blinded study): the card
 * shows what was started, or what was already there, and stays put.
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

async function mountCard(canOpen: boolean, it: IngestItem = item()) {
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
    props: { item: it, subjectLabel: 'EIAMD150', canStart: true, canOpen },
    global: { plugins: [router, i18n] },
  })
}

async function pick(w: Awaited<ReturnType<typeof mountCard>>, task: string) {
  await w.find('[data-testid="scan-analyses-start"] button').trigger('click')
  await w.find(`[data-testid="scan-analyses-task-${task}"]`).trigger('click')
  await flushPromises()
}

describe('ScanAnalyses — a session that may not open the job page', () => {
  beforeEach(() => {
    startMock.mockReset()
  })

  it('stays on the page and lists the started analysis with its status', async () => {
    startMock.mockResolvedValue({ jobId: 31, task: 'ga', status: 'queued', subjectLabel: 'EIAMD150' })
    const w = await mountCard(false)
    await pick(w, 'ga')
    expect(router.currentRoute.value.fullPath).toBe('/')
    const list = w.find('[data-testid="scan-analyses-list"]').text()
    expect(list).toContain('GA-Fläche')
    expect(list).toContain('In Warteschlange')
    expect(w.find('[data-testid="scan-analysis-link-31"]').exists()).toBe(false)
    expect(w.find('[data-testid="scan-analyses-notice"]').text()).toContain('gestartet')
    expect(w.emitted('changed')).toHaveLength(1)
    // The task is no longer offered.
    await w.find('[data-testid="scan-analyses-start"] button').trigger('click')
    expect(w.find('[data-testid="scan-analyses-task-ga"]').exists()).toBe(false)
  })

  it('shows an analysis that was already there instead of going to it', async () => {
    startMock.mockImplementation(async () => {
      throw Object.assign(new Error('409'), {
        status: 409, body: { code: 'ANALYSIS_EXISTS', existingJobId: 12, subjectLabel: 'EIAMD150', message: 'x' },
      })
    })
    const w = await mountCard(false)
    await pick(w, 'onl')
    expect(router.currentRoute.value.fullPath).toBe('/')
    expect(w.find('[data-testid="scan-analyses-list"]').text()).toContain('ONL-Dicke')
    expect(w.find('[data-testid="scan-analyses-notice"]').text()).toContain('bereits')
    expect(useErrorsStore().recent.length).toBe(0)
    expect(w.emitted('changed')).toHaveLength(1)
  })

  it('still goes to the job for a session that may open the page', async () => {
    startMock.mockResolvedValue({ jobId: 31, task: 'ga', status: 'queued', subjectLabel: 'EIAMD150' })
    const w = await mountCard(true)
    await pick(w, 'ga')
    expect(router.currentRoute.value.fullPath).toBe('/subjects/EIAMD150/jobs/31')
    expect(w.find('[data-testid="scan-analyses-notice"]').exists()).toBe(false)
  })
})
