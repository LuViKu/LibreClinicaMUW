/**
 * DR-039 — the job page for a scan that arrived as a DICOM OCT volume.
 *
 * A DICOM volume has no SLO: its geometry.json carries `fundus`, the scan box
 * and the fovea estimate as null, and there is no fundus.png. The page must
 * say so rather than show an empty frame or crash, and it names the source
 * format, the device and how the pixel spacing was read. A job refused
 * because its task is not validated for the device shows that reason.
 */
import { describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'

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
import { getJob, fetchGeometry } from '@/api/retinal'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import RetinalMetricsView from '../RetinalMetricsView.vue'

const getJobMock = getJob as unknown as ReturnType<typeof vi.fn>
const fetchGeometryMock = fetchGeometry as unknown as ReturnType<typeof vi.fn>

/** What the sidecar writes for a DICOM source. */
function dicomGeometry() {
  return {
    scan_index: 0,
    fundus: null,
    bscan: {
      dim_x_ascans: 1024,
      dim_y_rows: 496,
      dim_z_bscans: 3,
      pixel_axial_mm: 0.003872,
      pixel_lateral_mm: 0.005825,
      pixel_slice_mm: 0.062138,
    },
    bscan_positions_fundus_px: [],
    scan_bbox_fundus_px: null,
    fovea_estimate_fundus_px: null,
    source_format: 'dicom',
    spacing_order: 'swapped',
    device: { manufacturer: 'Heidelberg Engineering', model: 'SPECTRALIS' },
  }
}

function dicomJob(overrides: Record<string, unknown> = {}) {
  return {
    jobId: 11,
    eventCrfId: 42,
    task: 'fluid',
    laterality: 'OD',
    status: 'succeeded',
    modelVersion: 'fluid-1.3.0',
    enqueuedAt: '2026-10-09T10:00:00Z',
    completedAt: '2026-10-09T10:05:30Z',
    e2eUuid: '6f1c2a3b-4d5e-3f60-8a7b-9c0d1e2f3a4b',
    primaryMetric: { value: 0.42, unit: 'mm³' },
    outputPayload: {
      biomarkers: { irf_mm3: 0.2, srf_mm3: 0.1, ped_mm3: 0.12, total_mm3: 0.42 },
      etdrs_mm3: {
        central_1mm: { irf: 0.05, srf: 0.02, ped: 0.03, total: 0.1 },
        central_3mm: { irf: 0.15, srf: 0.05, ped: 0.08, total: 0.28 },
        central_6mm: { irf: 0.2, srf: 0.1, ped: 0.12, total: 0.42 },
      },
      etdrs_center: { bscan_z: 1, ascan_x: 512, source: 'volume-center-mvp' },
      voxel_volume_mm3: 0.0001,
      per_bscan_mm2: { irf: [1, 0, 0], srf: [0, 1, 0], ped: [0, 0, 1] },
      segmentation_file: 'fluidseg.npz',
    },
    confidence: 0.88,
    artifactNames: ['fluidseg.npz'],
    companionNames: ['bscan.dcm', 'geometry.json'],
    fundusUrl: null,
    geometryUrl: '/LibreClinica/pages/api/v1/retinal-jobs/11/artifacts/geometry.json',
    bscanDcmUrl: '/LibreClinica/pages/api/v1/retinal-jobs/11/artifacts/bscan.dcm',
    subjectArm: null,
    sourceFormat: 'dicom',
    deviceManufacturer: 'Heidelberg Engineering',
    deviceModel: 'SPECTRALIS',
    spacingOrder: 'swapped',
    statusMessage: null,
    ...overrides,
  }
}

async function mountView(job: Record<string, unknown>, geometry: unknown) {
  setActivePinia(createPinia())
  useAuthStore().user = {
    username: 'demo',
    displayName: 'Demo',
    email: null,
    role: 'Investigator',
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    mustChangePassword: false,
    passwordChangeReason: null,
    locale: null,
    timezone: null,
    activeStudy: { id: 1, oid: 'S_DEFAULTS1', name: 'iAMD', isSite: false },
  } as unknown as ReturnType<typeof useAuthStore>['user']
  getJobMock.mockReset()
  fetchGeometryMock.mockReset()
  getJobMock.mockResolvedValue(job)
  fetchGeometryMock.mockResolvedValue(geometry)

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/retinal-jobs/:jobId', name: 'retinal-job', component: { template: '<div />' } },
      { path: '/subjects', name: 'subjects', component: { template: '<div />' } },
    ],
  })
  router.push(`/retinal-jobs/${(job as { jobId: number }).jobId}`)
  await router.isReady()

  const wrapper = mount(RetinalMetricsView, {
    global: {
      plugins: [router, i18n],
      stubs: {
        PerBscanTrace: true,
        // Reads the DICOM through cornerstone, not geometry.json; out of scope here.
        BscanViewer: true,
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('RetinalMetricsView — a DICOM OCT source (DR-039)', () => {
  it('says there is no SLO in a DICOM instead of drawing an empty fundus panel', async () => {
    const w = await mountView(dicomJob(), dicomGeometry())
    const empty = w.find('[data-testid="retinal-view-fundus-empty"]')
    expect(empty.exists()).toBe(true)
    expect(empty.text()).toBe('Kein SLO-Bild in der DICOM-Datei')
    expect(w.findComponent({ name: 'FundusOverlay' }).exists()).toBe(false)
    // The rest of the page still renders its numbers.
    expect(w.find('[data-testid="retinal-view-heading"]').exists()).toBe(true)
  })

  it('does not draw the overlay even if a fundus URL were present, when the geometry has no fundus', async () => {
    const w = await mountView(
      dicomJob({ fundusUrl: '/LibreClinica/pages/api/v1/retinal-jobs/11/artifacts/fundus.png' }),
      dicomGeometry(),
    )
    expect(w.findComponent({ name: 'FundusOverlay' }).exists()).toBe(false)
    expect(w.find('[data-testid="retinal-view-fundus-empty"]').exists()).toBe(true)
  })

  it('names the source format, the device and the spacing order', async () => {
    const w = await mountView(dicomJob(), dicomGeometry())
    expect(w.find('[data-testid="retinal-view-source-format"]').text()).toBe('DICOM')
    expect(w.find('[data-testid="retinal-view-device"]').text()).toBe('Heidelberg Engineering SPECTRALIS')
    expect(w.find('[data-testid="retinal-view-spacing-order"]').text())
      .toBe('vertauscht gespeichert, physikalisch aufgelöst')
  })

  it('shows the format and device of an .e2e job but no spacing order', async () => {
    const w = await mountView(
      dicomJob({ sourceFormat: 'e2e', deviceManufacturer: 'Heidelberg Retina Angiograph',
        deviceModel: null, spacingOrder: 'standard' }),
      { ...dicomGeometry(), source_format: 'e2e' },
    )
    expect(w.find('[data-testid="retinal-view-source-format"]').text()).toBe('E2E')
    expect(w.find('[data-testid="retinal-view-device"]').text()).toBe('Heidelberg Retina Angiograph')
    expect(w.find('[data-testid="retinal-view-spacing-order"]').exists()).toBe(false)
  })

  it('shows nothing about the source for a job from before it was recorded', async () => {
    const w = await mountView(
      dicomJob({ sourceFormat: null, deviceManufacturer: null, deviceModel: null, spacingOrder: null }),
      dicomGeometry(),
    )
    expect(w.find('[data-testid="retinal-view-source"]').exists()).toBe(false)
  })

  it('shows why a job was refused for its device', async () => {
    const reason = 'fluid is validated for Heidelberg Spectralis only; this scan is from '
      + 'Carl Zeiss Meditec CIRRUS HD-OCT 5000. The scan was not sent for analysis.'
    const w = await mountView(
      dicomJob({
        status: 'failed', primaryMetric: null, outputPayload: {}, artifactNames: [],
        deviceManufacturer: 'Carl Zeiss Meditec', deviceModel: 'CIRRUS HD-OCT 5000',
        spacingOrder: 'standard-assumed', statusMessage: reason,
      }),
      dicomGeometry(),
    )
    const msg = w.find('[data-testid="retinal-view-status-message"]')
    expect(msg.exists()).toBe(true)
    expect(msg.text()).toBe(`Grund: ${reason}`)
    expect(w.find('[data-testid="retinal-view-device"]').text()).toBe('Carl Zeiss Meditec CIRRUS HD-OCT 5000')
  })
})
