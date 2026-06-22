/**
 * Phase E.7 Wave 4 — RetinalMetricsView spec.
 *
 * Pins the four task surfaces:
 *   1. Fluid → 4 KPI tiles (IRF / SRF / PED / Total).
 *   2. GA    → 1 KPI tile + ETDRS sub-totals (single-column).
 *   3. ONL   → 1 KPI tile (thickness), no per-B-scan trace.
 *   4. Empty / failed states render the inflight banner.
 *
 * The store is mocked at the api/retinal boundary so we don't have to
 * jury-rig the fetch mock for every code path.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

// eslint-disable-next-line import/first
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

vi.mock('@/api/retinal', () => {
  return {
    getJob: vi.fn(),
    listEventCrfJobs: vi.fn(),
    listSubjectJobs: vi.fn(),
    fetchGeometry: vi.fn(),
    retryRetinalJob: vi.fn(),
    artifactUrl: (jobId: number, name: string) =>
      `/LibreClinica/pages/api/v1/retinal-jobs/${jobId}/artifacts/${name}`,
  }
})

// eslint-disable-next-line import/first
import { getJob, fetchGeometry, retryRetinalJob } from '@/api/retinal'
// eslint-disable-next-line import/first
import RetinalMetricsView from '../RetinalMetricsView.vue'

const getJobMock = getJob as unknown as ReturnType<typeof vi.fn>
const fetchGeometryMock = fetchGeometry as unknown as ReturnType<typeof vi.fn>
const retryRetinalJobMock = retryRetinalJob as unknown as ReturnType<typeof vi.fn>

function makeGeometry() {
  return {
    fundus: { width_px: 768, height_px: 768, lateral_mm_per_px: 0.01, slice_mm_per_px: 0.06 },
    bscan: {
      dim_x_ascans: 512,
      dim_y_rows: 496,
      dim_z_bscans: 3,
      pixel_axial_mm: 0.004,
      pixel_lateral_mm: 0.01,
      pixel_slice_mm: 0.1,
    },
    bscan_positions_fundus_px: [
      { z: 0, x1: 100, y1: 100, x2: 600, y2: 100 },
      { z: 1, x1: 100, y1: 200, x2: 600, y2: 200 },
      { z: 2, x1: 100, y1: 300, x2: 600, y2: 300 },
    ],
    scan_bbox_fundus_px: { x: 100, y: 100, width: 500, height: 200 },
    fovea_estimate_fundus_px: {
      x: 384,
      y: 384,
      bscan_z: 1,
      ascan_x: 256,
      source: 'volume-center-mvp',
    },
  }
}

function makeFluidJob(overrides: Record<string, unknown> = {}) {
  return {
    jobId: 7,
    eventCrfId: 42,
    task: 'fluid',
    laterality: 'OD',
    status: 'succeeded',
    modelVersion: 'retinsight-v1.2',
    enqueuedAt: '2026-06-15T10:00:00Z',
    completedAt: '2026-06-15T10:05:30Z',
    e2eUuid: 'abc-uuid',
    primaryMetric: { value: 0.42, unit: 'mm³' },
    outputPayload: {
      biomarkers: {
        irf_mm3: 0.2,
        srf_mm3: 0.1,
        ped_mm3: 0.12,
        total_mm3: 0.42,
      },
      etdrs_mm3: {
        central_1mm: { irf: 0.05, srf: 0.02, ped: 0.03, total: 0.1 },
        central_3mm: { irf: 0.15, srf: 0.05, ped: 0.08, total: 0.28 },
        central_6mm: { irf: 0.2, srf: 0.1, ped: 0.12, total: 0.42 },
      },
      etdrs_center: { bscan_z: 1, ascan_x: 256, source: 'volume-center-mvp' },
      voxel_volume_mm3: 0.0001,
      per_bscan_mm2: { irf: [1, 0, 0], srf: [0, 1, 0], ped: [0, 0, 1] },
      segmentation_file: 'fluidseg.npz',
    },
    confidence: 0.88,
    artifactNames: ['fluidseg.npz'],
    companionNames: ['bscan.dcm', 'fundus.png', 'geometry.json'],
    fundusUrl: '/LibreClinica/pages/api/v1/retinal-jobs/7/artifacts/fundus.png',
    geometryUrl: '/LibreClinica/pages/api/v1/retinal-jobs/7/artifacts/geometry.json',
    bscanDcmUrl: '/LibreClinica/pages/api/v1/retinal-jobs/7/artifacts/bscan.dcm',
    ...overrides,
  }
}

function makeGaJob() {
  return {
    ...makeFluidJob({
      task: 'ga',
      primaryMetric: { value: 1.23, unit: 'mm²' },
      outputPayload: {
        ga_area_mm2: 1.23,
        hot_pixel_count: 1234,
        rpel_csv: '001-RPEL.csv',
        etdrs_mm2: { central_1mm: 0.1, central_3mm: 0.5, central_6mm: 1.23 },
        etdrs_center: { bscan_z: 1, ascan_x: 256, source: 'volume-center-mvp' },
        per_bscan_mm2: [0.1, 0.3, 0.6],
      },
      artifactNames: ['001-RPEL.csv', 'ga-mask.npz'],
    }),
  }
}

function makeOnlJob() {
  return {
    ...makeFluidJob({
      task: 'onl',
      primaryMetric: { value: 85.4, unit: 'µm' },
      outputPayload: {
        thickness_mean_um: 85.4,
        valid_ascans: 12000,
        total_ascans: 13000,
        surface_csvs: ['001-ILM.csv', '001-ELM.csv'],
        axial_mm_per_px: 0.004,
      },
      artifactNames: ['001-ILM.csv', '001-ELM.csv'],
    }),
  }
}

function makeRouter(jobId: number): Router {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      {
        path: '/retinal-jobs/:jobId',
        name: 'retinal-job',
        component: { template: '<div />' },
      },
      { path: '/subjects', name: 'subjects', component: { template: '<div />' } },
    ],
  })
  router.push(`/retinal-jobs/${jobId}`)
  return router
}

async function mountView(jobPayload: Record<string, unknown>) {
  setActivePinia(createPinia())
  getJobMock.mockReset()
  fetchGeometryMock.mockReset()
  getJobMock.mockResolvedValue(jobPayload)
  fetchGeometryMock.mockResolvedValue(makeGeometry())

  const router = makeRouter((jobPayload as { jobId: number }).jobId)
  await router.isReady()

  const wrapper = mount(RetinalMetricsView, {
    global: {
      plugins: [router, i18n],
      stubs: {
        // PerBscanTrace is async + pulls in Chart.js — stub at the
        // tree level so the test doesn't try to load the chart bundle.
        PerBscanTrace: true,
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
  await flushPromises()
  // Allow the async geometry load to settle too.
  await flushPromises()
  return wrapper
}

describe('RetinalMetricsView — task surfaces', () => {
  beforeEach(() => {
    getJobMock.mockReset()
    fetchGeometryMock.mockReset()
  })

  it('renders 4 KPI tiles for the fluid task (IRF / SRF / PED / Gesamt)', async () => {
    const w = await mountView(makeFluidJob())
    const tiles = w.findAll('[data-testid="retinal-kpi-tile"]')
    expect(tiles.length).toBe(4)
    const joined = tiles.map((t) => t.text()).join(' ')
    expect(joined).toContain('IRF')
    expect(joined).toContain('SRF')
    expect(joined).toContain('PED')
    // DE locale — "Gesamt" is the localised "Total" label.
    expect(joined).toContain('Gesamt')
  })

  it('renders the ETDRS sub-totals table with three full circles + two annular rings for fluid', async () => {
    const w = await mountView(makeFluidJob())
    const etdrs = w.find('[data-testid="retinal-view-etdrs"]')
    expect(etdrs.exists()).toBe(true)
    const rows = etdrs.findAll('[data-testid="retinal-etdrs-row"]')
    // 2026-06-22 — 3 cumulative central discs (1mm / 3mm / 6mm) +
    // 2 disjoint annular rings (1–3 mm / 3–6 mm).
    expect(rows.length).toBe(5)
    expect(rows[0].text()).toContain('1 mm')
    expect(rows[1].text()).toContain('3 mm')
    expect(rows[2].text()).toContain('6 mm')
    expect(rows[3].text()).toContain('1–3 mm')
    expect(rows[4].text()).toContain('3–6 mm')
  })

  it('renders 1 KPI tile + ETDRS sub-totals for the GA task', async () => {
    const w = await mountView(makeGaJob())
    const tiles = w.findAll('[data-testid="retinal-kpi-tile"]')
    expect(tiles.length).toBe(1)
    expect(tiles[0].text()).toContain('GA-Fläche')
    const etdrs = w.find('[data-testid="retinal-view-etdrs"]')
    expect(etdrs.exists()).toBe(true)
    expect(etdrs.findAll('[data-testid="retinal-etdrs-row"]').length).toBe(3)
  })

  it('renders 1 thickness KPI for the ONL task (per-B-scan trace dropped in design pass)', async () => {
    const w = await mountView(makeOnlJob())
    const tiles = w.findAll('[data-testid="retinal-kpi-tile"]')
    expect(tiles.length).toBe(1)
    expect(tiles[0].text()).toContain('ONL-Dicke')
    // 2026-06-22 — per-B-scan trace was removed when the view adopted
    // the new design (footprint bands on the en-face supersede it).
    expect(w.find('[data-testid="retinal-view-trace"]').exists()).toBe(false)
  })

  it('renders the inflight banner when status is queued', async () => {
    const w = await mountView(
      makeFluidJob({ status: 'queued', primaryMetric: null }),
    )
    const banner = w.find('[data-testid="retinal-view-inflight"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('Sidecar')
  })

  it('renders the no-metric banner on failed jobs', async () => {
    const w = await mountView(
      makeFluidJob({ status: 'failed', primaryMetric: null }),
    )
    const banner = w.find('[data-testid="retinal-view-inflight"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('fehlgeschlagen')
  })

  it('renders the no-metric banner when status is succeeded but primary metric is null', async () => {
    const w = await mountView(
      makeFluidJob({ status: 'succeeded', primaryMetric: null }),
    )
    const banner = w.find('[data-testid="retinal-view-no-metric"]')
    expect(banner.exists()).toBe(true)
  })

  it('shows a retry button on failed jobs that calls retryRetinalJob and flips the cached status', async () => {
    retryRetinalJobMock.mockReset()
    retryRetinalJobMock.mockResolvedValue({ jobId: 7, status: 'remote_pending' })
    const w = await mountView(
      makeFluidJob({ status: 'failed', primaryMetric: null }),
    )
    const button = w.find('[data-testid="retinal-view-retry"]')
    expect(button.exists()).toBe(true)
    expect(button.text()).toContain('Erneut versuchen')

    await button.trigger('click')
    await flushPromises()

    expect(retryRetinalJobMock).toHaveBeenCalledWith(7)
    // Banner should disappear (status now remote_pending — handled by
    // the `queued`/`remote_pending` inflight branch which surfaces a
    // different copy) OR text changes; either way the failed copy is gone.
    expect(w.html()).not.toContain('fehlgeschlagen')
  })

  it('renders the retry button always (design promotes it to the header)', async () => {
    // 2026-06-22 — the design moved the retry button from the inflight
    // banner into the page header where it is always visible and only
    // disabled while a retry is in flight. Verify it renders for a
    // non-failed job too.
    const w = await mountView(
      makeFluidJob({ status: 'queued', primaryMetric: null }),
    )
    expect(w.find('[data-testid="retinal-view-retry"]').exists()).toBe(true)
  })
})
