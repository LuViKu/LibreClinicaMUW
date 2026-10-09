/**
 * sdretinanet — BscanViewer legends.
 *
 * Pins that an sdretinanet job shows its 12-boundary legend (own labels,
 * CRT-trio defaults) plus a lesion legend built from the lesion
 * envelope's labels header with display names (Cyst → IRF,
 * Pseudodrusen → SDD), every class on by default; and that no other task
 * asks for the lesion envelope. The canvas paint itself needs a real 2D
 * context, so it is not exercised here — the decoding it relies on is
 * covered in useSegmentationEnvelope.spec.ts.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { computed, ref, type Ref } from 'vue'
import { createI18n } from 'vue-i18n'

import deMessages from '@/locales/de.json'
import type { SegmentationEnvelope } from '@/composables/useSegmentationEnvelope'

// Cornerstone never initialises in jsdom; failing fast leaves the viewer
// in its error state, which still renders the header legends.
vi.mock('@cornerstonejs/core', () => ({
  init: () => Promise.reject(new Error('no cornerstone in jsdom')),
}))
vi.mock('@cornerstonejs/dicom-image-loader/dist/cornerstoneDICOMImageLoaderNoWebWorkers.bundle.min.js', () => ({
  default: { external: {} },
}))
vi.mock('dicom-parser', () => ({ default: {} }))

const envelopes: { default: SegmentationEnvelope | null; lesions: SegmentationEnvelope | null } = {
  default: null,
  lesions: null,
}
const lesionJobIds: Array<Ref<number | null>> = []

vi.mock('@/composables/useSegmentationEnvelope', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/composables/useSegmentationEnvelope')>()
  return {
    ...actual,
    useSegmentationEnvelope: (jobId: Ref<number | null>, part?: string | null) => {
      if (part === 'lesions') {
        lesionJobIds.push(jobId)
        // Mirror the real composable: nothing to fetch without a job id.
        const envelope = computed(() => (jobId.value == null ? null : envelopes.lesions))
        return { envelope, loading: ref(false), error: ref(null) }
      }
      return { envelope: ref(envelopes.default), loading: ref(false), error: ref(null) }
    },
  }
})

// eslint-disable-next-line import/first
import BscanViewer from '../BscanViewer.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

const SD_LAYER_LABELS = [
  'ILM', 'RNFL-GCL', 'GCL-IPL', 'IPL-INL', 'INL-OPL', 'OPL-HFL',
  'OB_ELM', 'BMEIS', 'IB_RPE', 'OB_RPE', 'BM', 'HL-S',
]

function surfaceEnvelope(task: string): SegmentationEnvelope {
  return {
    task,
    kind: 'surface_y',
    dtype: 'float32',
    shape: [12, 1, 4],
    labels: SD_LAYER_LABELS,
    data: new Float32Array(12 * 4),
    correctedSurfaceIndices: [],
  }
}

function lesionEnvelope(): SegmentationEnvelope {
  return {
    task: 'sdretinanet',
    kind: 'lesion_packed',
    dtype: 'uint8',
    shape: [1, 2, 2],
    labels: ['Cyst', 'SRF', 'PED', 'SHRM', 'Pseudodrusen', 'ORT', '+HRF'],
    data: new Uint8Array([0, 1, 0x81, 0x80]),
    correctedSurfaceIndices: [],
  }
}

async function mountViewer(jobId: number) {
  const w = mount(BscanViewer, {
    props: { bscanDcmUrl: '/x/bscan.dcm', nBscans: 1, modelValue: 0, jobId },
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return w
}

describe('BscanViewer — sdretinanet legends', () => {
  beforeEach(() => {
    envelopes.default = null
    envelopes.lesions = null
    lesionJobIds.length = 0
    try { window.localStorage.clear() } catch { /* not available */ }
  })

  it('shows the 12 SD-RetinaNet boundaries with ILM, IB_RPE and BM on by default', async () => {
    envelopes.default = surfaceEnvelope('sdretinanet')
    envelopes.lesions = lesionEnvelope()
    const w = await mountViewer(501)
    const chips = w.findAll('[data-testid^="bscan-layers-chip-"]')
    expect(chips.map((c) => c.text())).toEqual(SD_LAYER_LABELS)
    const on = chips.filter((c) => c.classes().includes('text-white/90')).map((c) => c.text())
    expect(on).toEqual(['ILM', 'IB_RPE', 'BM'])
  })

  it('builds the lesion legend from the header with display names, all on', async () => {
    envelopes.default = surfaceEnvelope('sdretinanet')
    envelopes.lesions = lesionEnvelope()
    const w = await mountViewer(502)
    const chips = w.findAll('[data-testid^="bscan-lesions-chip-"]')
    expect(chips.map((c) => c.text())).toEqual(['IRF', 'SRF', 'PED', 'SHRM', 'SDD', 'ORT', 'HRF'])
    expect(chips.every((c) => c.attributes('aria-pressed') === 'true')).toBe(true)

    await chips[6]!.trigger('click')
    expect(w.find('[data-testid="bscan-lesions-chip-6"]').attributes('aria-pressed')).toBe('false')
    await w.find('[data-testid="bscan-lesions-chip-6"]').trigger('click')
    expect(w.find('[data-testid="bscan-lesions-chip-6"]').attributes('aria-pressed')).toBe('true')
  })

  it('does not request the lesion envelope for another task', async () => {
    envelopes.default = surfaceEnvelope('layers')
    envelopes.lesions = lesionEnvelope()
    const w = await mountViewer(503)
    expect(lesionJobIds).toHaveLength(1)
    expect(lesionJobIds[0]!.value).toBeNull()
    expect(w.find('[data-testid="bscan-viewer-lesions-legend"]').exists()).toBe(false)
    // The IOWA legend keeps its own defaults (ILM, IB_RPE, BM at 0 / 9 / 11).
    const on = w.findAll('[data-testid^="bscan-layers-chip-"]')
      .filter((c) => c.classes().includes('text-white/90'))
      .map((c) => c.attributes('data-testid'))
    expect(on).toEqual(['bscan-layers-chip-0', 'bscan-layers-chip-9', 'bscan-layers-chip-11'])
  })
})
