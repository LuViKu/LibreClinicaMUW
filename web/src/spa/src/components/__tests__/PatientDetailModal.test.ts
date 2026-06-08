/**
 * Phase E.6 — PatientDetailModal spec.
 *
 * Pins:
 *  - Eye timeline renders one pill per enrolment + transition, in date
 *    order, current-eye pill carries an "aktuell" badge.
 *  - Modality picker toggle adds/removes the corresponding chart section.
 *  - Numeric modalities render the Chart.js Line stub with the right
 *    {data, options} pair; categorical modalities render a stepped
 *    pill row per eye.
 *  - Clicking a past pill calls auth.pickStudy(studyOid) + routes to
 *    /subjects/{label}.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import { nextTick } from 'vue'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiDelete: vi.fn(),
  }
})

// Capture Chart.js + vue-chartjs prop bindings without registering the
// actual Chart.js runtime — the test asserts on `data` + `options` flow
// from the modal to the stub component.
const lineRenders: Array<{ data: unknown; options: unknown }> = []

vi.mock('vue-chartjs', () => ({
  Line: {
    name: 'LineChartStub',
    props: ['data', 'options'],
    setup(props: { data: unknown; options: unknown }) {
      lineRenders.push({ data: props.data, options: props.options })
      return () => null
    },
    template: '<div data-testid="line-stub" />',
  },
}))

// chart.js' registration side effect is a no-op when the runtime isn't
// referenced — but defineAsyncComponent imports it eagerly. Stub the
// register call so the test doesn't blow up on missing scale classes.
vi.mock('chart.js', () => {
  const dummy = {}
  return {
    Chart: { register: vi.fn() },
    LineController: dummy,
    LineElement: dummy,
    PointElement: dummy,
    LinearScale: dummy,
    CategoryScale: dummy,
    TimeScale: dummy,
    Tooltip: dummy,
    Legend: dummy,
    Title: dummy,
    Filler: dummy,
  }
})

import PatientDetailModal from '@/components/PatientDetailModal.vue'
import { useAuthStore } from '@/stores/auth'
import { usePatientsOverviewStore } from '@/stores/patientsOverview'
import enMessages from '@/locales/en.json'
import type { MeasurementSeries, Modality, PatientDetail } from '@/types/patient'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: enMessages },
})

function makeRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/subjects/:label', name: 'subject-detail', component: { template: '<div />' } },
      { path: '/patients', name: 'patients-overview', component: { template: '<div />' } },
    ],
  })
  return router
}

const DETAIL: PatientDetail = {
  subjectId: 101,
  uniqueIdentifier: 'PERSON-001',
  gender: 'F',
  yearOfBirth: 1962,
  enrolments: [
    { studyOid: 'S_IAMD', studyName: 'iAMD', label: 'M-007', studyEye: 'OD', enrolledOn: '2024-01-15', lastVisitAt: '2024-06-01T10:00:00Z' },
    { studyOid: 'S_GA', studyName: 'GA Cohort', label: 'M-001', studyEye: 'OU', enrolledOn: '2025-03-01', lastVisitAt: '2025-09-01T10:00:00Z' },
  ],
  eyeTransitions: [
    {
      transitionId: 1,
      eye: 'OD',
      eventAt: '2025-03-01T09:00:00Z',
      fromStudyOid: 'S_IAMD',
      fromStudyName: 'iAMD',
      fromLabel: 'M-007',
      toStudyOid: 'S_GA',
      toStudyName: 'GA Cohort',
      toLabel: 'M-001',
      reason: 'Progression',
    },
  ],
}

const MODALITIES: Modality[] = [
  { modalityId: 1, code: 'va', labelEn: 'Visual acuity', labelDe: 'Visus', ordinal: 1, itemOidOd: 'I_VA_OD', itemOidOs: 'I_VA_OS', dataType: 'numeric', unit: 'logMAR' },
  { modalityId: 2, code: 'lens', labelEn: 'Lens status', labelDe: 'Linsenstatus', ordinal: 2, itemOidOd: 'I_LENS_OD', itemOidOs: 'I_LENS_OS', dataType: 'categorical', unit: null },
]

const SERIES_VA_OD: MeasurementSeries = {
  modalityCode: 'va',
  dataType: 'numeric',
  unit: 'logMAR',
  series: [
    { date: '2024-01-15', value: '0.10', numericValue: 0.10, studyOid: 'S_IAMD', studyName: 'iAMD', eventCrfId: 1, eventName: 'V1' },
    { date: '2024-07-15', value: '0.20', numericValue: 0.20, studyOid: 'S_IAMD', studyName: 'iAMD', eventCrfId: 2, eventName: 'V2' },
  ],
}

const SERIES_LENS_OD: MeasurementSeries = {
  modalityCode: 'lens',
  dataType: 'categorical',
  unit: null,
  series: [
    { date: '2024-01-15', value: 'phakic', numericValue: null, studyOid: 'S_IAMD', studyName: 'iAMD', eventCrfId: 1, eventName: 'V1' },
    { date: '2025-03-01', value: 'pseudophakic', numericValue: null, studyOid: 'S_GA', studyName: 'GA Cohort', eventCrfId: 2, eventName: 'V1' },
  ],
}

async function mountModal() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = makeRouter()
  const auth = useAuthStore()
  auth.user = {
    username: 'demo',
    displayName: 'Demo',
    email: null,
    role: 'Investigator',
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    locale: null,
    timezone: null,
    activeStudy: { id: 1, oid: 'S_GA', name: 'GA Cohort', isSite: false },
  }
  const store = usePatientsOverviewStore()
  store.detail = DETAIL
  store.modalities = MODALITIES
  // Seed cached series so the chart paints immediately.
  store.seriesByKey.set(store.seriesKey(101, 'va', 'OD'), SERIES_VA_OD)
  store.seriesByKey.set(store.seriesKey(101, 'lens', 'OD'), SERIES_LENS_OD)
  // Re-assign to keep Pinia's reactivity in step with the seed.
  store.seriesByKey = new Map(store.seriesByKey)

  const wrapper = mount(PatientDetailModal, {
    props: { open: true, subjectId: 101 },
    global: { plugins: [pinia, router, i18n] },
    attachTo: document.body,
  })
  await flushPromises()
  // Resolve the Chart.js dynamic import + register tick.
  await flushPromises()
  await nextTick()
  return { wrapper, router, auth, store }
}

describe('PatientDetailModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    lineRenders.length = 0
  })

  it('renders one timeline row per eye with pills in date order', async () => {
    const { wrapper } = await mountModal()
    const odPills = document.body.querySelectorAll('[data-testid^="pill-OD-"]')
    const osPills = document.body.querySelectorAll('[data-testid^="pill-OS-"]')
    // OD: enrolment pill + transition pill = 2.
    expect(odPills.length).toBe(2)
    // OS: covered by the OU enrolment (GA Cohort) = 1 pill.
    expect(osPills.length).toBe(1)
    // First OD pill is the iAMD start (label M-007).
    expect((odPills[0] as HTMLElement).textContent).toContain('iAMD')
    expect((odPills[0] as HTMLElement).textContent).toContain('M-007')
    // Second OD pill is the GA Cohort transition (label M-001) with the
    // current badge.
    expect((odPills[1] as HTMLElement).textContent).toContain('GA Cohort')
    expect((odPills[1] as HTMLElement).textContent).toContain('M-001')
    wrapper.unmount()
  })

  it('marks the current pill with the aktuell badge', async () => {
    const { wrapper } = await mountModal()
    // OD's current pill is GA Cohort (the last transition's to-side).
    const currentBadges = document.body.querySelectorAll('[data-testid^="pill-"]')
    let foundCurrent = false
    currentBadges.forEach((el) => {
      const html = el as HTMLElement
      if (html.className.includes('muw-blue-50')) foundCurrent = true
    })
    expect(foundCurrent).toBe(true)
    wrapper.unmount()
  })

  it('renders a Chart.js Line stub for the numeric modality with data + options', async () => {
    const { wrapper } = await mountModal()
    // numeric is default-on, so the chart paints on mount.
    expect(lineRenders.length).toBeGreaterThan(0)
    const last = lineRenders[lineRenders.length - 1]
    expect(last.data).toBeDefined()
    expect(last.options).toBeDefined()
    const data = last.data as {
      labels: string[]
      datasets: Array<{ label: string; data: Array<{ x: string; y: number }> }>
    }
    // OD dataset is present (because we seeded only the OD series).
    expect(data.datasets.some((d) => d.label === 'OD')).toBe(true)
    expect(data.datasets[0].data.length).toBe(2)
    wrapper.unmount()
  })

  it('toggling a categorical modality on renders a stepped pill row per eye', async () => {
    const { wrapper } = await mountModal()
    // Initially the categorical chart isn't rendered (default off).
    expect(document.body.querySelector('[data-testid="categorical-lens"]')).toBeNull()
    const toggle = document.body.querySelector('[data-testid="modality-toggle-lens"]') as HTMLInputElement | null
    expect(toggle).not.toBeNull()
    toggle!.checked = true
    toggle!.dispatchEvent(new Event('change'))
    await flushPromises()
    await nextTick()
    expect(document.body.querySelector('[data-testid="categorical-lens"]')).not.toBeNull()
    // OD row is rendered because we seeded the OD series.
    const odRow = document.body.querySelector('[data-testid="categorical-row-lens-OD"]')
    expect(odRow).not.toBeNull()
    expect((odRow as HTMLElement).textContent).toContain('phakic')
    expect((odRow as HTMLElement).textContent).toContain('pseudophakic')
    wrapper.unmount()
  })

  it('clicking a past pill calls auth.pickStudy + routes to /subjects/{label}', async () => {
    const { wrapper, router, auth } = await mountModal()
    const pickSpy = vi.spyOn(auth, 'pickStudy').mockResolvedValue()
    const pushSpy = vi.spyOn(router, 'push')
    // First OD pill (enrolment, iAMD / M-007).
    const firstPill = document.body.querySelector('[data-testid="pill-OD-0"]') as HTMLButtonElement | null
    expect(firstPill).not.toBeNull()
    firstPill!.click()
    await flushPromises()
    expect(pickSpy).toHaveBeenCalledWith('S_IAMD')
    expect(pushSpy).toHaveBeenCalledWith('/subjects/M-007')
    wrapper.unmount()
  })
})
