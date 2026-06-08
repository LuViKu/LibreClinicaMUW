/**
 * Phase E.6 — PatientsOverviewView render spec.
 *
 * Pins the surface that the patients store + i18n + router combination
 * produces for the Patientenübersicht landing:
 *  - the row state renders one row per patient + study chips per enrolment.
 *  - search input debounces and triggers loadList with page=0 + the new term.
 *  - row click opens the detail modal scoped to the clicked subject.
 *  - pagination buttons call loadList with the next/prev page.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

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

// Mock the modal component so we don't need Chart.js to mount.
vi.mock('@/components/PatientDetailModal.vue', () => ({
  default: {
    name: 'PatientDetailModal',
    props: ['open', 'subjectId'],
    emits: ['close', 'update:open'],
    template: '<div v-if="open" data-testid="modal-stub">Detail #{{ subjectId }}</div>',
  },
}))

import PatientsOverviewView from '@/views/PatientsOverviewView.vue'
import { usePatientsOverviewStore } from '@/stores/patientsOverview'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  // The patients-overview keys are owned by the i18n worktree and
  // haven't landed yet — vue-i18n falls back to the key string in
  // missing-translation mode, which is exactly what the test asserts
  // against (rendered text contains the key).
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: enMessages },
})

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/patients', name: 'patients-overview', component: { template: '<div />' } },
      { path: '/subjects/:label', name: 'subject-detail', component: { template: '<div />' } },
    ],
  })
}

async function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = makeRouter()
  await router.push('/patients')
  await router.isReady()
  // Pre-empt the onMounted loadList — tests seed store state directly.
  const store = usePatientsOverviewStore()
  vi.spyOn(store, 'loadList').mockResolvedValue()
  const wrapper = mount(PatientsOverviewView, {
    global: { plugins: [pinia, router, i18n] },
  })
  await flushPromises()
  return wrapper
}

const LIST = {
  totalCount: 2,
  page: 0,
  pageSize: 50,
  patients: [
    {
      subjectId: 101,
      uniqueIdentifier: 'PERSON-001',
      gender: 'F',
      yearOfBirth: 1962,
      enrolments: [
        { studyOid: 'S_GA', studyName: 'GA Cohort', label: 'M-001', studyEye: 'OU', enrolledOn: '2024-01-15', lastVisitAt: '2024-09-01T10:00:00Z' },
        { studyOid: 'S_IAMD', studyName: 'iAMD', label: 'M-007', studyEye: 'OD', enrolledOn: '2024-02-01', lastVisitAt: null },
      ],
    },
    {
      subjectId: 102,
      uniqueIdentifier: 'PERSON-002',
      gender: 'M',
      yearOfBirth: 1971,
      enrolments: [
        { studyOid: 'S_IAMD', studyName: 'iAMD', label: 'M-014', studyEye: 'OS', enrolledOn: '2024-03-15', lastVisitAt: '2024-08-01T11:00:00Z' },
      ],
    },
  ],
}

describe('PatientsOverviewView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  it('renders one row per patient with study chips', async () => {
    const w = await mountView()
    const store = usePatientsOverviewStore()
    store.list = LIST.patients
    store.totalCount = LIST.totalCount
    store.page = LIST.page
    store.pageSize = LIST.pageSize
    await flushPromises()
    expect(w.find('[data-testid="patient-row-101"]').exists()).toBe(true)
    expect(w.find('[data-testid="patient-row-102"]').exists()).toBe(true)
    // Both enrolments for patient 101 render their chips.
    const row101 = w.find('[data-testid="patient-row-101"]')
    expect(row101.text()).toContain('GA Cohort')
    expect(row101.text()).toContain('iAMD')
  })

  it('renders the empty state when the list is empty', async () => {
    const w = await mountView()
    const store = usePatientsOverviewStore()
    store.list = []
    store.totalCount = 0
    await flushPromises()
    // The patients.list.empty key landed via the i18n worktree merge
    // (en.json: "No patients match your access.").
    expect(w.text()).toContain('No patients match your access.')
  })

  it('search input debounces and triggers loadList(0, pageSize, search)', async () => {
    vi.useFakeTimers()
    const w = await mountView()
    const store = usePatientsOverviewStore()
    // Re-grab the spy — mountView already wrapped loadList, but Pinia's
    // ref-based spies survive across the call; just reset the count.
    const spy = vi.mocked(store.loadList)
    spy.mockClear()
    const searchInput = w.find('input[type="search"]')
    expect(searchInput.exists()).toBe(true)
    await searchInput.setValue('Meier')
    // No call yet — the debounce window is 300ms.
    expect(spy).not.toHaveBeenCalled()
    vi.advanceTimersByTime(310)
    await flushPromises()
    expect(spy).toHaveBeenCalledWith(0, 50, 'Meier')
    vi.useRealTimers()
  })

  it('row click opens the detail modal scoped to the clicked subject', async () => {
    const w = await mountView()
    const store = usePatientsOverviewStore()
    const loadDetailSpy = vi.spyOn(store, 'loadDetail').mockResolvedValue()
    store.list = LIST.patients
    store.totalCount = LIST.totalCount
    await flushPromises()
    await w.find('[data-testid="patient-row-101"]').trigger('click')
    await flushPromises()
    expect(loadDetailSpy).toHaveBeenCalledWith(101)
    expect(w.find('[data-testid="modal-stub"]').exists()).toBe(true)
    expect(w.find('[data-testid="modal-stub"]').text()).toContain('Detail #101')
  })

  it('next-page button calls loadList(page+1, pageSize, search)', async () => {
    const w = await mountView()
    const store = usePatientsOverviewStore()
    store.list = LIST.patients
    store.totalCount = 200 // forces multi-page
    store.page = 0
    store.pageSize = 50
    store.search = ''
    const spy = vi.spyOn(store, 'loadList').mockResolvedValue()
    await flushPromises()
    await w.find('[data-testid="page-next"]').trigger('click')
    expect(spy).toHaveBeenCalledWith(1, 50, '')
  })

  it('prev-page button is disabled when on page 0', async () => {
    const w = await mountView()
    const store = usePatientsOverviewStore()
    store.list = LIST.patients
    store.totalCount = 200
    store.page = 0
    await flushPromises()
    const prev = w.find('[data-testid="page-prev"]')
    expect((prev.element as HTMLButtonElement).disabled).toBe(true)
  })
})
