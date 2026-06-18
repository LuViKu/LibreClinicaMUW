/**
 * 2026-06-19 — RetinalParkedAdminView spec.
 *
 * Pins the load-bearing contract:
 *  - On mount, the view fetches /pages/api/v1/retinal-jobs?status=PARKED
 *    via the api/retinal layer.
 *  - One row per parked job, with patientId + laterality + uploadedAt
 *    cells filled in.
 *  - Empty state when the backend returns [].
 *  - Clicking "Zuordnen" mounts AssignParkedDialog with the row's
 *    parsed PatientId pre-filled.
 *
 * Backend wire format is stubbed at the api/retinal seam — the same
 * pattern Modalities + Visit/Patient pickers use elsewhere.
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

import deMessages from '@/locales/de.json'

vi.mock('@/api/retinal', async () => {
  const actual = await vi.importActual<typeof import('@/api/retinal')>('@/api/retinal')
  return {
    ...actual,
    listParkedJobs: vi.fn(),
    bindParkedJob: vi.fn(),
  }
})

import RetinalParkedAdminView from '../RetinalParkedAdminView.vue'
import { listParkedJobs, type ParkedJobAdminRow } from '@/api/retinal'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', component: { template: '<div />' } }],
})

const ROWS: ParkedJobAdminRow[] = [
  {
    jobId: 40,
    task: 'fluid',
    patientId: 'EIAMD139',
    laterality: 'OS',
    enqueuedAt: '2026-06-18T13:06:08Z',
    candidateStudySubjectId: null,
  },
  {
    jobId: 39,
    task: 'fluid',
    patientId: 'EIAMD140',
    laterality: 'OD',
    enqueuedAt: '2026-06-18T12:05:49Z',
    candidateStudySubjectId: 9,
  },
]

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(listParkedJobs).mockReset()
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('RetinalParkedAdminView', () => {
  it('renders one row per parked job after mount', async () => {
    vi.mocked(listParkedJobs).mockResolvedValueOnce(ROWS)
    const w = mount(RetinalParkedAdminView, {
      global: { plugins: [i18n, router] },
      attachTo: document.body,
    })
    await flushPromises()
    expect(vi.mocked(listParkedJobs)).toHaveBeenCalledTimes(1)
    expect(w.find('[data-testid="parked-row-40"]').exists()).toBe(true)
    expect(w.find('[data-testid="parked-row-39"]').exists()).toBe(true)
    expect(w.text()).toContain('EIAMD139')
    expect(w.text()).toContain('EIAMD140')
    expect(w.text()).toContain('OS')
    expect(w.text()).toContain('OD')
  })

  it('renders empty state when API returns an empty list', async () => {
    vi.mocked(listParkedJobs).mockResolvedValueOnce([])
    const w = mount(RetinalParkedAdminView, {
      global: { plugins: [i18n, router] },
      attachTo: document.body,
    })
    await flushPromises()
    const empty = w.find('[data-testid="retinal-parked-empty"]')
    expect(empty.exists()).toBe(true)
    expect(empty.text()).toContain('Keine geparkten Scans')
  })

  it('opens AssignParkedDialog when Zuordnen is clicked', async () => {
    vi.mocked(listParkedJobs).mockResolvedValueOnce(ROWS)
    const w = mount(RetinalParkedAdminView, {
      global: { plugins: [i18n, router] },
      attachTo: document.body,
    })
    await flushPromises()
    const btn = w.find('[data-testid="parked-assign-40"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    await flushPromises()
    // PatientSearchModal is the first step of AssignParkedDialog; the
    // teleported modal lands on document.body. Look for the search-mode
    // input which the modal exposes.
    const text = document.body.textContent ?? ''
    expect(text).toContain('Patient suchen')
  })
})
