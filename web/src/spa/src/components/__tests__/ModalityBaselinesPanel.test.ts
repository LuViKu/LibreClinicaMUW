/**
 * Phase E.6 — ModalityBaselinesPanel specs.
 *
 * Pins the behaviours the SubjectDetailView block depends on:
 *   1. Calls store.load(label, eye) on mount.
 *   2. Renders one row per modality with the labelDe column when
 *      i18nLocale='de' (default) — labelEn when 'en'.
 *   3. Empty cells (date === null) render as the em-dash sentinel.
 *   4. Numeric values append the unit suffix; categorical values
 *      render bare.
 *   5. The error path shows ErrorText + a retry button that calls
 *      load(...,true) — bypassing the cache.
 *   6. The loading path renders skeleton rows.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
  }
})

// eslint-disable-next-line import/first
import { apiGet, ApiNetworkError } from '@/api/client'
// eslint-disable-next-line import/first
import ModalityBaselinesPanel from '@/components/ModalityBaselinesPanel.vue'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'
// eslint-disable-next-line import/first
import type { ModalityBaseline } from '@/types/baselines'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>

// Stub the modality-baselines i18n subtree so the test isn't coupled
// to the i18n worktree's locale edits (which land separately during
// harmonization). Same trick the SubjectDetailView spec uses for the
// eye-transition keys.
const messages = {
  en: {
    ...enMessages,
    subjectDetail: {
      ...(enMessages as { subjectDetail: Record<string, unknown> }).subjectDetail,
      modalityBaselines: {
        title: 'Modality baselines ({eye})',
        empty: 'No baselines on file for this eye.',
        loading: 'Loading baselines…',
        columns: {
          modality: 'Modality',
          baselineDateGlobal: 'First obs. (global)',
          baselineValueGlobal: 'Value (global)',
          baselineDatePerStudy: 'First obs. (study)',
          baselineValuePerStudy: 'Value (study)',
          count: 'n',
        },
        error: {
          network: 'Backend unreachable — please retry.',
          retry: 'Retry',
        },
      },
    },
  },
}

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages,
  missingWarn: false,
  fallbackWarn: false,
})

function makeBaseline(overrides: Partial<ModalityBaseline> = {}): ModalityBaseline {
  return {
    modalityCode: 'IOP',
    labelEn: 'Intraocular pressure',
    labelDe: 'Augeninnendruck',
    itemOid: 'I_IOP',
    dataType: 'numeric',
    unit: 'mmHg',
    global: { date: '2026-04-01', value: '14', observationCount: 8 },
    perStudy: { date: '2026-05-01', value: '15', observationCount: 3 },
    ...overrides,
  }
}

interface MountOptions {
  subjectLabel?: string
  eye?: 'OD' | 'OS'
  i18nLocale?: 'en' | 'de'
}

async function mountAt(options: MountOptions = {}) {
  setActivePinia(createPinia())
  const wrapper = mount(ModalityBaselinesPanel, {
    props: {
      subjectLabel: options.subjectLabel ?? 'M-001',
      eye: options.eye ?? 'OD',
      i18nLocale: options.i18nLocale ?? 'de',
    },
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

describe('ModalityBaselinesPanel', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
  })

  it('calls the store load action on mount with the right (label, eye)', async () => {
    apiGetMock.mockResolvedValueOnce([])
    await mountAt({ subjectLabel: 'M-007', eye: 'OS' })
    expect(apiGetMock).toHaveBeenCalledWith(
      '/pages/api/v1/subjects/M-007/eyes/OS/modality-baselines',
    )
  })

  it('renders one row per modality, with the German label by default', async () => {
    apiGetMock.mockResolvedValueOnce([
      makeBaseline({ modalityCode: 'IOP' }),
      makeBaseline({
        modalityCode: 'BCVA',
        labelEn: 'Best-corrected visual acuity',
        labelDe: 'Bestkorrigierter Visus',
        itemOid: 'I_BCVA',
        unit: 'logMAR',
        global: { date: '2026-03-01', value: '0.6', observationCount: 5 },
        perStudy: { date: '2026-04-01', value: '0.5', observationCount: 2 },
      }),
    ])

    const w = await mountAt({ i18nLocale: 'de' })
    const rows = w.findAll('[data-testid="modality-baselines-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('Augeninnendruck')
    expect(rows[1]!.text()).toContain('Bestkorrigierter Visus')
  })

  it('switches to the English label when i18nLocale is en', async () => {
    apiGetMock.mockResolvedValueOnce([makeBaseline()])
    const w = await mountAt({ i18nLocale: 'en' })
    const row = w.find('[data-testid="modality-baselines-row"]')
    expect(row.text()).toContain('Intraocular pressure')
    expect(row.text()).not.toContain('Augeninnendruck')
  })

  it('appends the unit suffix on numeric values', async () => {
    apiGetMock.mockResolvedValueOnce([makeBaseline({ unit: 'mmHg' })])
    const w = await mountAt()
    const text = w.find('[data-testid="modality-baselines-row"]').text()
    expect(text).toContain('14 mmHg')
    expect(text).toContain('15 mmHg')
  })

  it('renders categorical values without a unit suffix', async () => {
    apiGetMock.mockResolvedValueOnce([
      makeBaseline({
        modalityCode: 'LENS',
        labelEn: 'Lens status',
        labelDe: 'Linsenstatus',
        dataType: 'categorical',
        unit: null,
        global: { date: '2026-04-01', value: 'phakic', observationCount: 1 },
        perStudy: { date: '2026-05-01', value: 'phakic', observationCount: 1 },
      }),
    ])
    const w = await mountAt()
    const row = w.find('[data-testid="modality-baselines-row"]')
    // Two cells of 'phakic' — global + perStudy — both without unit.
    expect(row.text()).toContain('phakic')
    // No 'phakic mmHg' / 'phakic logMAR' from carry-over.
    expect(row.text()).not.toMatch(/phakic\s+mmHg/)
  })

  it('shows an em-dash for empty date / value cells', async () => {
    apiGetMock.mockResolvedValueOnce([
      makeBaseline({
        global: { date: null, value: null, observationCount: 0 },
        perStudy: { date: '2026-05-01', value: '15', observationCount: 3 },
      }),
    ])
    const w = await mountAt()
    const row = w.find('[data-testid="modality-baselines-row"]')
    // Two of the cells are global date + global value — both em-dashes.
    expect(row.text()).toContain('—')
    // perStudy side still renders.
    expect(row.text()).toContain('15 mmHg')
    expect(row.text()).toContain('2026-05-01')
  })

  it('renders skeleton rows while the fetch is in flight', async () => {
    // Don't resolve the promise — leave the fetch hanging so the
    // component stays in loading state.
    apiGetMock.mockReturnValueOnce(new Promise(() => {}))
    const w = await mountAt()
    const skeletons = w.findAll('[data-testid="modality-baselines-skeleton-row"]')
    expect(skeletons.length).toBeGreaterThan(0)
    expect(w.find('[data-testid="modality-baselines-row"]').exists()).toBe(false)
  })

  it('renders the empty state when the backend returns zero modalities', async () => {
    apiGetMock.mockResolvedValueOnce([])
    const w = await mountAt()
    expect(w.find('[data-testid="modality-baselines-empty"]').exists()).toBe(true)
    expect(w.find('[data-testid="modality-baselines-row"]').exists()).toBe(false)
  })

  it('shows the error region + retry button on failure', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiNetworkError('refused', null))
    const w = await mountAt()
    const err = w.find('[data-testid="modality-baselines-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text()).toMatch(/Backend nicht erreichbar/)
    expect(w.find('[data-testid="modality-baselines-retry"]').exists()).toBe(true)
  })

  it('re-fetches with force=true when the retry button is clicked', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiNetworkError('refused', null))
    const w = await mountAt()
    expect(apiGetMock).toHaveBeenCalledTimes(1)

    // The retry succeeds — surface a single row to confirm the table
    // is re-rendered.
    apiGetMock.mockResolvedValueOnce([makeBaseline()])
    await w.find('[data-testid="modality-baselines-retry"]').trigger('click')
    await flushPromises()

    expect(apiGetMock).toHaveBeenCalledTimes(2)
    expect(w.find('[data-testid="modality-baselines-error"]').exists()).toBe(false)
    expect(w.find('[data-testid="modality-baselines-row"]').exists()).toBe(true)
  })

  it('exposes the eye on the wrapper data-testid for the SubjectDetailView block', async () => {
    apiGetMock.mockResolvedValueOnce([])
    const w = await mountAt({ eye: 'OS' })
    expect(w.find('[data-testid="modality-baselines-OS"]').exists()).toBe(true)
  })
})
