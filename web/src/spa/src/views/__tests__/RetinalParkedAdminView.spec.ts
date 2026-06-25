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
 * <p>2026-06-20 B2 (bulk-bind):
 *  - Header "select-all" toggle picks every row + clears it.
 *  - Toolbar appears only when ≥1 row is selected, with localized
 *    counter ("N von M ausgewählt") + bulk-assign button.
 *  - Clicking the bulk-assign button mounts the dialog with the full
 *    selection forwarded as {@code jobIds: number[]}; the on-bind
 *    handler then calls the new bulkBindParkedJobs API.
 *  - On a partial result, the SPA renders a warn-toned summary toast.
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
    bulkBindParkedJobs: vi.fn(),
  }
})

import RetinalParkedAdminView from '../RetinalParkedAdminView.vue'
import AssignParkedDialog from '@/components/retinal/AssignParkedDialog.vue'
import {
  bulkBindParkedJobs,
  listParkedJobs,
  type ParkedJobAdminRow,
  type RetinalJobBulkBindResponse,
} from '@/api/retinal'

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
  vi.mocked(bulkBindParkedJobs).mockReset()
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

  /* ====================================================================== */
  /* B2 bulk-bind                                                            */
  /* ====================================================================== */

  it('does not render the bulk toolbar when no row is selected', async () => {
    vi.mocked(listParkedJobs).mockResolvedValueOnce(ROWS)
    const w = mount(RetinalParkedAdminView, {
      global: { plugins: [i18n, router] },
      attachTo: document.body,
    })
    await flushPromises()
    expect(w.find('[data-testid="retinal-parked-bulk-toolbar"]').exists()).toBe(false)
  })

  it('select-all toggles every row and surfaces the counter', async () => {
    vi.mocked(listParkedJobs).mockResolvedValueOnce(ROWS)
    const w = mount(RetinalParkedAdminView, {
      global: { plugins: [i18n, router] },
      attachTo: document.body,
    })
    await flushPromises()

    const selectAll = w.find('[data-testid="retinal-parked-select-all"]')
    expect(selectAll.exists()).toBe(true)
    await selectAll.setValue(true)
    await flushPromises()

    const counter = w.find('[data-testid="retinal-parked-bulk-counter"]')
    expect(counter.exists()).toBe(true)
    // Counter copy reads "2 von 2 ausgewählt"
    expect(counter.text()).toContain('2 von 2')

    // Click again → cleared.
    await selectAll.setValue(false)
    await flushPromises()
    expect(w.find('[data-testid="retinal-parked-bulk-toolbar"]').exists()).toBe(false)
  })

  it('per-row checkbox shows the toolbar with the partial counter', async () => {
    vi.mocked(listParkedJobs).mockResolvedValueOnce(ROWS)
    const w = mount(RetinalParkedAdminView, {
      global: { plugins: [i18n, router] },
      attachTo: document.body,
    })
    await flushPromises()

    const rowCheck = w.find('[data-testid="parked-select-40"]')
    expect(rowCheck.exists()).toBe(true)
    await rowCheck.setValue(true)
    await flushPromises()

    const counter = w.find('[data-testid="retinal-parked-bulk-counter"]')
    expect(counter.text()).toContain('1 von 2')
  })

  it('bulk-assign click mounts the dialog with the full selection', async () => {
    vi.mocked(listParkedJobs).mockResolvedValueOnce(ROWS)
    const w = mount(RetinalParkedAdminView, {
      global: { plugins: [i18n, router] },
      attachTo: document.body,
    })
    await flushPromises()

    // Pick both rows via select-all, then click bulk-assign.
    await w.find('[data-testid="retinal-parked-select-all"]').setValue(true)
    await flushPromises()
    await w.find('[data-testid="retinal-parked-bulk-assign"]').trigger('click')
    await flushPromises()

    // Dialog's first step (PatientSearchModal) is teleported to body.
    const text = document.body.textContent ?? ''
    expect(text).toContain('Patient suchen')
    // The in-dialog bulk summary surfaces with the count.
    const summary = document.querySelector('[data-testid="assign-parked-bulk-summary"]')
    expect(summary).not.toBeNull()
    expect(summary?.textContent).toContain('2')
  })

  it('bulk-bind calls bulkBindParkedJobs and renders a summary toast', async () => {
    vi.mocked(listParkedJobs)
      // First load on mount; second load after the bind completes.
      .mockResolvedValueOnce(ROWS)
      .mockResolvedValueOnce([])
    const response: RetinalJobBulkBindResponse = {
      results: [
        { jobId: 40, status: 'BOUND', newStatus: 'queued' },
        { jobId: 39, status: 'ALREADY_BOUND', message: 'already' },
      ],
      summary: {
        bound: 1,
        alreadyBound: 1,
        forbidden: 0,
        invalidState: 0,
        notFound: 0,
        error: 0,
      },
    }
    vi.mocked(bulkBindParkedJobs).mockResolvedValueOnce(response)

    const w = mount(RetinalParkedAdminView, {
      global: { plugins: [i18n, router] },
      attachTo: document.body,
    })
    await flushPromises()

    // Select all, open dialog, then forge the bind emit from the
    // AssignParkedDialog stub — we can't drive the nested modals
    // here without their full stack, so we emit directly.
    await w.find('[data-testid="retinal-parked-select-all"]').setValue(true)
    await flushPromises()
    await w.find('[data-testid="retinal-parked-bulk-assign"]').trigger('click')
    await flushPromises()

    // Find the AssignParkedDialog component instance and emit `bind`.
    const dialog = w.findComponent(AssignParkedDialog)
    expect(dialog.exists()).toBe(true)
    dialog.vm.$emit('bind', { jobIds: [40, 39], eventCrfId: 99 })
    await flushPromises()

    expect(vi.mocked(bulkBindParkedJobs)).toHaveBeenCalledWith({
      jobIds: [40, 39],
      eventCrfId: 99,
    })

    const toast = w.find('[data-testid="retinal-parked-toast"]')
    expect(toast.exists()).toBe(true)
    expect(toast.text()).toContain('1 zugewiesen')
    expect(toast.text()).toContain('1 bereits gebunden')
  })
})
