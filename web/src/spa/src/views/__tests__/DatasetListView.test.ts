/**
 * Phase E.6 — DatasetListView render spec.
 *
 * Pins the surface that the store + i18n + router + auth combination
 * ought to produce on the Data Export landing:
 *  - empty state vs row state.
 *  - per-row "Export now" button opens the modal scoped to that row.
 *  - export modal submit POSTs through the datasets store with the
 *    radio-selected format.
 *  - Quick ODM toolbar button calls the store action.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => ({
  apiGet: vi.fn().mockResolvedValue([]),
  apiPost: vi.fn().mockResolvedValue({}),
  apiPut: vi.fn().mockResolvedValue({}),
  apiDelete: vi.fn().mockResolvedValue({}),
  ApiError: class ApiError extends Error {
    isUnauthorized = false
    isForbidden = false
    constructor(public status = 0, msg = '', public body: unknown = null) { super(msg) }
  },
  ApiNetworkError: class ApiNetworkError extends Error {},
}))

import { apiGet, apiPost } from '@/api/client'
import DatasetListView from '@/views/DatasetListView.vue'
import { useAuthStore } from '@/stores/auth'
import { useDatasetsStore } from '@/stores/datasets'
import type { DatasetDto } from '@/types/export'

import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/build-study', name: 'build-study', component: { template: '<div />' } },
      { path: '/manage-users', name: 'manage-users', component: { template: '<div />' } },
      { path: '/export', name: 'data-export', component: { template: '<div />' } },
    ],
  })
}

const ROW: DatasetDto = {
  oid: '11',
  id: 11,
  name: 'Alpha cohort',
  description: 'All visits, all CRFs',
  ownerName: 'datamanager',
  dateCreated: '2026-05-01T08:00:00Z',
  lastRunAt: '2026-05-02T09:00:00Z',
  fileCount: 2,
}

async function mountView({ rows, settings }: { rows: DatasetDto[]; settings?: Record<string, string> }) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    username: 'dm',
    displayName: 'Demo DM',
    email: null,
    role: 'Data Manager',
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    locale: null,
    timezone: null,
    activeStudy: { id: 1, oid: 'S_DEFAULT', name: 'Default Study', isSite: false, settings },
  }
  // Seed the datasets store directly so onMounted's load() resolves
  // to the empty default, then we replace rows in-place.
  ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(rows)
  const router = makeRouter()
  const wrapper = mount(DatasetListView, {
    global: { plugins: [pinia, router, i18n] },
  })
  await flushPromises()
  return wrapper
}

describe('DatasetListView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the empty state when the store has no rows', async () => {
    const w = await mountView({ rows: [] })
    expect(w.text()).toContain('No saved datasets')
  })

  it('renders a row per dataset with name + owner + file count', async () => {
    const w = await mountView({ rows: [ROW] })
    expect(w.text()).toContain('Alpha cohort')
    expect(w.text()).toContain('datamanager')
    expect(w.text()).toContain('2') // file count
  })

  it('Export now opens the modal scoped to the clicked row', async () => {
    const w = await mountView({ rows: [ROW] })
    const exportBtn = w.findAll('button').find((b) => b.text() === 'Export now')
    expect(exportBtn).toBeTruthy()
    await exportBtn!.trigger('click')
    expect(w.text()).toContain('Export dataset')
    expect(w.text()).toContain('Alpha cohort')
  })

  it('modal submit calls the datasets store with the chosen format', async () => {
    const w = await mountView({ rows: [ROW] })
    const ds = useDatasetsStore()
    const spy = vi.spyOn(ds, 'triggerExport').mockResolvedValueOnce({
      archivedDatasetFileId: 99,
      downloadUrl: '/LibreClinica/pages/api/v1/archived-files/99/download',
    })

    await w.findAll('button').find((b) => b.text() === 'Export now')!.trigger('click')
    // Default format is ODM; switch to CSV.
    const csvRadio = w.findAll('input[type=radio]').find((r) => (r.element as HTMLInputElement).value === 'csv')
    await csvRadio!.setValue(true)
    // Stub window.open so the auto-download branch doesn't pop a real tab.
    const winOpen = vi.spyOn(window, 'open').mockImplementation(() => null)
    await w.findAll('button').find((b) => b.text() === 'Run export')!.trigger('click')
    await flushPromises()

    expect(spy).toHaveBeenCalledWith('S_DEFAULT', 11, 'csv')
    expect(winOpen).toHaveBeenCalled()
  })

  it('Quick ODM toolbar button calls the store action', async () => {
    const w = await mountView({ rows: [ROW] })
    const ds = useDatasetsStore()
    const spy = vi.spyOn(ds, 'quickOdm').mockResolvedValueOnce({
      archivedDatasetFileId: 42,
      downloadUrl: '/LibreClinica/pages/api/v1/archived-files/42/download',
    })
    vi.spyOn(window, 'open').mockImplementation(() => null)

    await w.findAll('button').find((b) => b.text() === 'Quick ODM export')!.trigger('click')
    await flushPromises()

    expect(spy).toHaveBeenCalledWith('S_DEFAULT')
    void apiPost
  })
})


/**
 * P3.8 — the multimodal bundle runs in the background.
 *
 * <p>Two things worth pinning: the option exists only where the study turned
 * it on (a radio button that always answers 403 is worse than none), and
 * choosing it does not download anything — it queues a job whose progress
 * the row shows until a link appears. The operator keeps the page; the file
 * arrives when the job says so.
 */
describe('DatasetListView — bundle export (P3.8)', () => {
  const JOB = {
    id: 7, datasetId: 11, format: 'bundle', status: 'queued', progressPct: 0,
    submittedAt: '2026-09-19T10:00:00Z', startedAt: null, finishedAt: null,
    archivedDatasetFileId: null, errorMessage: null, downloadUrl: null,
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('does not offer the bundle for a study that has not enabled it', async () => {
    const wrapper = await mountView({ rows: [ROW] })
    await wrapper.get('[data-testid="dataset-export-now"]').trigger('click')
    expect(wrapper.find('input[type="radio"][value="bundle"]').exists()).toBe(false)
  })

  it('offers the bundle where the study enables it and queues a job on submit', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(JOB)
    const wrapper = await mountView({ rows: [ROW], settings: { 'export.bundle.enabled': 'true' } })
    await wrapper.get('[data-testid="dataset-export-now"]').trigger('click')
    await wrapper.get('input[type="radio"][value="bundle"]').setValue()
    const buttons = wrapper.findAll('[role="dialog"] button')
    await buttons[buttons.length - 1].trigger('click')
    await flushPromises()

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/datasets/11/exports', { format: 'bundle' })
    // Nothing was downloaded; the row now reports the job.
    const strip = wrapper.get('[data-testid="bundle-job-11"]')
    expect(strip.text()).toContain(enMessages.dataExport.job.queued)
    expect(wrapper.find('[data-testid="bundle-job-download"]').exists()).toBe(false)
    // and the export action waits until the job is done
    expect((wrapper.get('[data-testid="dataset-export-now"]').element as HTMLButtonElement).disabled).toBe(true)
  })

  it('polls the job and shows the download link once it is done', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    try {
      ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(JOB)
      const wrapper = await mountView({ rows: [ROW], settings: { 'export.bundle.enabled': 'true' } })
      await wrapper.get('[data-testid="dataset-export-now"]').trigger('click')
      await wrapper.get('input[type="radio"][value="bundle"]').setValue()
      const buttons = wrapper.findAll('[role="dialog"] button')
      await buttons[buttons.length - 1].trigger('click')
      await flushPromises()

      ;(apiGet as ReturnType<typeof vi.fn>).mockImplementation(async (url: string) => {
        if (url === '/pages/api/v1/exports/7') {
          return { ...JOB, status: 'done', progressPct: 100, archivedDatasetFileId: 99,
            finishedAt: '2026-09-19T10:05:00Z',
            downloadUrl: '/LibreClinica/pages/api/v1/exports/7/download' }
        }
        return []
      })
      await vi.advanceTimersByTimeAsync(3000)
      await flushPromises()

      const link = wrapper.get('[data-testid="bundle-job-download"]')
      expect(link.attributes('href')).toBe('/LibreClinica/pages/api/v1/exports/7/download')
      expect((wrapper.get('[data-testid="dataset-export-now"]').element as HTMLButtonElement).disabled).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })
})
