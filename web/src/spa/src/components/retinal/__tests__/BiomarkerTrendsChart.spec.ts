/**
 * Wave 2A — Tests for {@link BiomarkerTrendsChart}.
 *
 * Stubs the global {@code fetch} so each task's canned trends response
 * drives the test. Asserts:
 *   - The expected URL is hit (per-task query param).
 *   - The empty-state banner renders for an empty result set.
 *   - Per-task dataset shape is correct (fluid → 4 datasets;
 *     onl/pr/ga → 1).
 *
 * The component lazy-loads vue-chartjs; we don't drive the Line
 * mount here, only the data-shape contract via the test-only
 * {@code defineExpose}'d {@code chartData}.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import BiomarkerTrendsChart from '../BiomarkerTrendsChart.vue'
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function mountChart(task: 'fluid' | 'onl' | 'pr' | 'ga', subjectId = 42) {
  return mount(BiomarkerTrendsChart, {
    props: { subjectId, task },
    global: { plugins: [i18n] },
  })
}

interface ExposedChart {
  points: { value: unknown[] }
  chartData: { value: { datasets: Array<{ label: string; data: Array<number | null> }> } | null }
}

const fluidFixture = [
  {
    jobId: 1,
    completedAt: '2026-01-15T10:00:00Z',
    eyeLaterality: 'OD',
    primaryMetricValue: 0.21,
    primaryMetricUnit: 'mm³',
    outputPayload: {
      biomarkers: { irf_mm3: 0.10, srf_mm3: 0.05, ped_mm3: 0.03, total_mm3: 0.18 },
    },
  },
  {
    jobId: 2,
    completedAt: '2026-03-20T10:00:00Z',
    eyeLaterality: 'OD',
    primaryMetricValue: 0.25,
    primaryMetricUnit: 'mm³',
    outputPayload: {
      biomarkers: { irf_mm3: 0.12, srf_mm3: 0.07, ped_mm3: 0.05, total_mm3: 0.24 },
    },
  },
]

const onlFixture = [
  {
    jobId: 10,
    completedAt: '2026-02-10T10:00:00Z',
    eyeLaterality: 'OS',
    primaryMetricValue: 95.3,
    primaryMetricUnit: 'µm',
    outputPayload: { thickness_mean_um: 95.3 },
  },
  {
    jobId: 11,
    completedAt: '2026-05-12T10:00:00Z',
    eyeLaterality: 'OS',
    primaryMetricValue: 92.1,
    primaryMetricUnit: 'µm',
    outputPayload: { thickness_mean_um: 92.1 },
  },
]

const gaFixture = [
  {
    jobId: 20,
    completedAt: '2026-04-10T10:00:00Z',
    eyeLaterality: 'OD',
    primaryMetricValue: 2.5,
    primaryMetricUnit: 'mm²',
    outputPayload: { ga_area_mm2: 2.5 },
  },
]

describe('BiomarkerTrendsChart', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: false })
    vi.useRealTimers()
  })
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('hits the trends URL with the task query parameter', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(fluidFixture))
    vi.stubGlobal('fetch', fetchMock)
    mountChart('fluid', 42)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const url = fetchMock.mock.calls[0][0] as string
    expect(url).toBe(
      '/LibreClinica/pages/api/v1/study-subjects/42/retinal-trends?task=fluid',
    )
  })

  it('renders the empty-state banner when the response is empty', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]))
    vi.stubGlobal('fetch', fetchMock)
    const w = mountChart('onl', 7)
    await flushPromises()
    expect(w.find('[data-testid="biomarker-trends-empty"]').exists()).toBe(true)
    // The DE string from de.json should be visible
    expect(w.text()).toContain('Noch keine')
  })

  it('produces 4 datasets for the fluid task (IRF / SRF / PED / total)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(fluidFixture))
    vi.stubGlobal('fetch', fetchMock)
    const w = mountChart('fluid', 42)
    await flushPromises()
    const cd = (w.vm as unknown as ExposedChart).chartData.value
    expect(cd).not.toBeNull()
    expect(cd!.datasets).toHaveLength(4)
    expect(cd!.datasets.map((d) => d.data)).toEqual([
      [0.10, 0.12],
      [0.05, 0.07],
      [0.03, 0.05],
      [0.18, 0.24],
    ])
  })

  it('produces 1 dataset for the onl task (single primary metric series)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(onlFixture))
    vi.stubGlobal('fetch', fetchMock)
    const w = mountChart('onl', 99)
    await flushPromises()
    const cd = (w.vm as unknown as ExposedChart).chartData.value
    expect(cd).not.toBeNull()
    expect(cd!.datasets).toHaveLength(1)
    expect(cd!.datasets[0].data).toEqual([95.3, 92.1])
  })

  it('produces 1 dataset for the ga task', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(gaFixture))
    vi.stubGlobal('fetch', fetchMock)
    const w = mountChart('ga', 5)
    await flushPromises()
    const cd = (w.vm as unknown as ExposedChart).chartData.value
    expect(cd).not.toBeNull()
    expect(cd!.datasets).toHaveLength(1)
    expect(cd!.datasets[0].data).toEqual([2.5])
  })

  it('refetches when the task prop changes', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(fluidFixture))
      .mockResolvedValueOnce(jsonResponse(onlFixture))
    vi.stubGlobal('fetch', fetchMock)
    const w = mountChart('fluid', 42)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await w.setProps({ task: 'onl' })
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const url = fetchMock.mock.calls[1][0] as string
    expect(url).toContain('task=onl')
  })
})
