/**
 * 2026-10-09 — where a retinal job lives on its page: one canonical address
 * (/subjects/<label>/jobs/<jobId>), the trail to the subject and the visit,
 * and the switcher to the same scan's other analyses.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

import deMessages from '@/locales/de.json'

vi.mock('@/api/retinal', () => ({
  getJob: vi.fn(),
  getJobBySubject: vi.fn(),
  rerunRetinalJobAs: vi.fn(),
  listEventCrfJobs: vi.fn(),
  listSubjectJobs: vi.fn(),
  fetchGeometry: vi.fn(),
  retryRetinalJob: vi.fn(),
  artifactUrl: (jobId: number, name: string) => `/LibreClinica/pages/api/v1/retinal-jobs/${jobId}/artifacts/${name}`,
}))

// eslint-disable-next-line import/first
import { getJob, getJobBySubject, rerunRetinalJobAs } from '@/api/retinal'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import RetinalMetricsView from '../RetinalMetricsView.vue'

const getJobMock = getJob as unknown as ReturnType<typeof vi.fn>
const bySubjectMock = getJobBySubject as unknown as ReturnType<typeof vi.fn>
const rerunMock = rerunRetinalJobAs as unknown as ReturnType<typeof vi.fn>

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

function job(overrides: Record<string, unknown> = {}) {
  return {
    jobId: 7, eventCrfId: 0, task: 'fluid', laterality: 'OD', status: 'done', modelVersion: 'v1',
    enqueuedAt: '2026-03-04T10:00:00Z', completedAt: '2026-03-04T10:05:00Z', e2eUuid: null,
    primaryMetric: null, outputPayload: {}, confidence: null, artifactNames: [], companionNames: [],
    fundusUrl: null, geometryUrl: null, bscanDcmUrl: null, subjectArm: null,
    subjectLabel: 'EIAMD150', studyEventId: 42, visitName: 'V1 Baseline',
    visitDate: '2026-03-04',
    siblings: [
      { jobId: 7, task: 'fluid', status: 'done' },
      { jobId: 8, task: 'layers', status: 'done' },
      { jobId: 12, task: 'sdretinanet', status: 'queued' },
    ],
    ...overrides,
  }
}

const UNFILED = {
  subjectLabel: null, studyEventId: null, visitName: null, visitDate: null, siblings: [],
}

let router: Router

async function mountAt(path: string, payload: Record<string, unknown>, notThisSubjects = false) {
  setActivePinia(createPinia())
  useAuthStore().user = {
    username: 'dm', displayName: 'DM', email: null, role: 'Data Manager', siteLabel: null, source: 'local',
    mfaSatisfied: true, profileComplete: true, mustChangePassword: false, passwordChangeReason: null,
    locale: null, timezone: null,
    activeStudy: { id: 1, oid: 'S_DEFAULTS1', name: 'iAMD', isSite: false },
  } as unknown as ReturnType<typeof useAuthStore>['user']
  getJobMock.mockResolvedValue(payload)
  if (notThisSubjects) bySubjectMock.mockRejectedValue(Object.assign(new Error('No retinal job 99 for this subject'), { status: 404 }))
  else bySubjectMock.mockResolvedValue(payload)
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/retinal-jobs/:jobId', name: 'retinal-job', component: { template: '<div />' } },
      { path: '/subjects/:subjectLabel/jobs/:jobId', name: 'retinal-job-by-subject', component: { template: '<div />' } },
      { path: '/subjects/:id', component: { template: '<div />' } },
      { path: '/subjects', component: { template: '<div />' } },
      { path: '/events/:eventId', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const w = mount(RetinalMetricsView, {
    global: { plugins: [router, i18n], stubs: { PerBscanTrace: true } },
  })
  await flushPromises()
  await flushPromises()
  return w
}

describe('RetinalMetricsView — one address per job', () => {
  beforeEach(() => {
    getJobMock.mockReset()
    bySubjectMock.mockReset()
    rerunMock.mockReset()
  })

  it('replaces the id address with the subject address once the job is loaded', async () => {
    await mountAt('/retinal-jobs/7', job())
    // The replace happened during load; the route now is the canonical one.
    expect(router.currentRoute.value.fullPath).toBe('/subjects/EIAMD150/jobs/7')
    // …without an extra history entry: going back does not return to the id address.
    router.back()
    await flushPromises()
    expect(router.currentRoute.value.fullPath).not.toBe('/retinal-jobs/7')
  })

  it('stays on the id address for a job with no visit', async () => {
    await mountAt('/retinal-jobs/7', job(UNFILED))
    expect(router.currentRoute.value.fullPath).toBe('/retinal-jobs/7')
  })

  it('goes to an existing job of the task at its subject address after rerun-as', async () => {
    rerunMock.mockRejectedValue(Object.assign(new Error('409'), {
      status: 409, body: { message: 'exists', existingJobId: 12, subjectLabel: 'EIAMD150' },
    }))
    const w = await mountAt('/subjects/EIAMD150/jobs/7', job())
    await w.find('[data-testid="retinal-view-rerun-as"] button').trigger('click')
    await w.find('[data-testid="retinal-view-rerun-as-ga"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/subjects/EIAMD150/jobs/12')
  })

  it('goes to the new job at its subject address after rerun-as', async () => {
    rerunMock.mockResolvedValue({ jobId: 30, task: 'ga', status: 'remote_pending', subjectLabel: 'EIAMD150' })
    const w = await mountAt('/subjects/EIAMD150/jobs/7', job())
    await w.find('[data-testid="retinal-view-rerun-as"] button').trigger('click')
    await w.find('[data-testid="retinal-view-rerun-as-ga"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/subjects/EIAMD150/jobs/30')
  })
})

describe('RetinalMetricsView — the canonical address', () => {
  beforeEach(() => {
    getJobMock.mockReset()
    bySubjectMock.mockReset()
  })

  it('asks the server for the job under its subject, by its id', async () => {
    await mountAt('/subjects/EIAMD150/jobs/7', job())
    expect(bySubjectMock).toHaveBeenCalledWith('EIAMD150', 7)
    expect(getJobMock).not.toHaveBeenCalled()
  })

  it('shows the error and no job when the server says the job is not this subject\'s', async () => {
    const w = await mountAt('/subjects/EIAMD150/jobs/99', job(), true)
    expect(w.find('[data-testid="retinal-view-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="retinal-view-heading"]').exists()).toBe(false)
    expect(getJobMock).not.toHaveBeenCalled()
  })
})

describe('RetinalMetricsView — trail', () => {
  beforeEach(() => {
    getJobMock.mockReset()
    bySubjectMock.mockReset()
  })

  it('leads to the register, the subject and the visit with its date', async () => {
    const w = await mountAt('/subjects/EIAMD150/jobs/7', job())
    const links = w.find('[data-testid="page-trail"]').findAll('a')
    expect(links.map((a) => a.text())).toEqual(['Studienteilnehmer', 'EIAMD150', 'V1 Baseline · 04.03.2026'])
    expect(links.map((a) => a.attributes('href'))).toEqual(['/subjects', '/subjects/EIAMD150', '/events/42'])
  })

  it('says "nicht zugeordnet", not as a link, for a job with no visit', async () => {
    const w = await mountAt('/retinal-jobs/7', job(UNFILED))
    const trail = w.find('[data-testid="page-trail"]')
    expect(trail.findAll('a').map((a) => a.text())).toEqual(['Studienteilnehmer'])
    expect(trail.find('[data-testid="page-trail-plain"]').text()).toBe('nicht zugeordnet')
  })
})

describe('RetinalMetricsView — the scan\'s other analyses', () => {
  beforeEach(() => {
    getJobMock.mockReset()
    bySubjectMock.mockReset()
  })

  it('lists every analysis of the scan at its subject address, the current one marked', async () => {
    const w = await mountAt('/subjects/EIAMD150/jobs/7', job())
    const tabs = w.find('[data-testid="retinal-view-siblings"]').findAll('a')
    expect(tabs.map((a) => a.attributes('href'))).toEqual([
      '/subjects/EIAMD150/jobs/7', '/subjects/EIAMD150/jobs/8', '/subjects/EIAMD150/jobs/12'])
    expect(tabs.map((a) => a.attributes('aria-current') ?? null)).toEqual(['page', null, null])
    expect(tabs[1].text()).toBe('Schichtsegmentierung (IOWA + BM)')
  })

  it('shows no switcher for a scan analysed once', async () => {
    const w = await mountAt('/subjects/EIAMD150/jobs/7', job({ siblings: [{ jobId: 7, task: 'fluid', status: 'done' }] }))
    expect(w.find('[data-testid="retinal-view-siblings"]').exists()).toBe(false)
  })
})
