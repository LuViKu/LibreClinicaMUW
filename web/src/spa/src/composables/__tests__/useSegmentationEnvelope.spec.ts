/**
 * sdretinanet — the {@code lesion_packed} envelope kind and the
 * {@code ?part=lesions} fetch.
 *
 * <p>The packing rules come from the backend contract: the labels header
 * lists the main lesions, then the overlay lesions prefixed with
 * {@code +}. With K overlays, overlay k sets bit (7 - k) and the main id
 * is {@code value & ((1 << (8 - K)) - 1)}; id i+1 means main label i.
 * The decoder must take K from the header, never from a hard-coded list.
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref, type EffectScope } from 'vue'
import { flushPromises } from '@vue/test-utils'

import {
  clearSegmentationEnvelopeCache,
  decodeLesionPackedValue,
  lesionPackedLayout,
  useSegmentationEnvelope,
} from '../useSegmentationEnvelope'

const SD_LESION_LABELS = ['Cyst', 'SRF', 'PED', 'SHRM', 'Pseudodrusen', 'ORT', '+HRF']

describe('lesionPackedLayout', () => {
  it('splits main and + overlay labels and derives the main mask from K', () => {
    const layout = lesionPackedLayout(SD_LESION_LABELS)
    expect(layout.mainLabels).toEqual(['Cyst', 'SRF', 'PED', 'SHRM', 'Pseudodrusen', 'ORT'])
    expect(layout.overlayLabels).toEqual(['HRF'])
    // K = 1 → seven low bits carry the main id.
    expect(layout.mainMask).toBe(0x7f)
  })

  it('takes K from the header: two overlays leave six bits for the main id', () => {
    const layout = lesionPackedLayout(['A', 'B', '+X', '+Y'])
    expect(layout.overlayLabels).toEqual(['X', 'Y'])
    expect(layout.mainMask).toBe(0x3f)
  })

  it('without overlays the whole byte is the main id', () => {
    expect(lesionPackedLayout(['A', 'B']).mainMask).toBe(0xff)
  })
})

describe('decodeLesionPackedValue', () => {
  const layout = lesionPackedLayout(SD_LESION_LABELS)

  it('0 is no lesion at all', () => {
    expect(decodeLesionPackedValue(0, layout)).toEqual({ mainIndex: null, overlayIndices: [] })
  })

  it('main id i+1 maps to main label i', () => {
    expect(decodeLesionPackedValue(1, layout).mainIndex).toBe(0) // Cyst
    expect(layout.mainLabels[decodeLesionPackedValue(5, layout).mainIndex!]).toBe('Pseudodrusen')
    expect(decodeLesionPackedValue(6, layout)).toEqual({ mainIndex: 5, overlayIndices: [] }) // ORT
  })

  it('an overlay bit alone is the overlay with no main lesion', () => {
    expect(decodeLesionPackedValue(0x80, layout)).toEqual({ mainIndex: null, overlayIndices: [0] })
  })

  it('a pixel carries a main lesion AND an overlay (HRF inside a cyst)', () => {
    const decoded = decodeLesionPackedValue(0x80 | 1, layout)
    expect(decoded.mainIndex).toBe(0)
    expect(layout.mainLabels[decoded.mainIndex!]).toBe('Cyst')
    expect(decoded.overlayIndices).toEqual([0])
    expect(layout.overlayLabels[decoded.overlayIndices[0]!]).toBe('HRF')
  })

  it('with two overlays, overlay k is bit (7 - k)', () => {
    const two = lesionPackedLayout(['A', 'B', '+X', '+Y'])
    expect(decodeLesionPackedValue(0x40 | 2, two)).toEqual({ mainIndex: 1, overlayIndices: [1] })
    expect(decodeLesionPackedValue(0xc0, two)).toEqual({ mainIndex: null, overlayIndices: [0, 1] })
  })

  it('a main id the header has no label for decodes as no main lesion', () => {
    expect(decodeLesionPackedValue(7, layout).mainIndex).toBeNull()
  })
})

describe('useSegmentationEnvelope — parts', () => {
  // Each test's watchers live in their own scope: the cache-clear refresh
  // tick is module-global, so a previous test's live consumer would
  // otherwise re-fetch through the next test's fetch stub.
  let scope: EffectScope
  afterEach(() => {
    scope?.stop()
    vi.unstubAllGlobals()
  })

  function inScope<T>(fn: () => T): T {
    return scope.run(fn)!
  }

  function stubFetch(headers: Record<string, string>, body: Uint8Array) {
    const fetchMock = vi.fn(async () => new Response(body, { status: 200, headers }))
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  it('fetches ?part=lesions and decodes a lesion_packed body as Uint8Array', async () => {
    const body = new Uint8Array([0, 1, 0x81, 0x80])
    const fetchMock = stubFetch({
      'X-MUW-Seg-Task': 'sdretinanet',
      'X-MUW-Seg-Kind': 'lesion_packed',
      'X-MUW-Seg-Dtype': 'uint8',
      'X-MUW-Seg-Shape': '1,2,2',
      'X-MUW-Seg-Labels': SD_LESION_LABELS.join(','),
    }, body)
    const jobId = ref<number | null>(9101)
    scope = effectScope()
    const { envelope } = inScope(() => useSegmentationEnvelope(jobId, 'lesions'))
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0]![0])).toBe(
      '/LibreClinica/pages/api/v1/retinal-jobs/9101/segmentation?part=lesions',
    )
    const env = envelope.value!
    expect(env.kind).toBe('lesion_packed')
    expect(env.shape).toEqual([1, 2, 2])
    expect(env.labels).toEqual(SD_LESION_LABELS)
    expect(env.data).toBeInstanceOf(Uint8Array)
    expect(Array.from(env.data)).toEqual([0, 1, 0x81, 0x80])
    scope.stop()
    clearSegmentationEnvelopeCache(9101)
  })

  it('caches the default part and the lesions part separately, and clears both', async () => {
    const fetchMock = stubFetch({
      'X-MUW-Seg-Task': 'sdretinanet',
      'X-MUW-Seg-Kind': 'lesion_packed',
      'X-MUW-Seg-Dtype': 'uint8',
      'X-MUW-Seg-Shape': '1,1,1',
      'X-MUW-Seg-Labels': 'Cyst',
    }, new Uint8Array([0]))
    const jobId = ref<number | null>(9102)
    scope = effectScope()
    inScope(() => useSegmentationEnvelope(jobId))
    inScope(() => useSegmentationEnvelope(jobId, 'lesions'))
    await flushPromises()
    const urls = fetchMock.mock.calls.map((c) => String(c[0]))
    expect(urls).toEqual([
      '/LibreClinica/pages/api/v1/retinal-jobs/9102/segmentation',
      '/LibreClinica/pages/api/v1/retinal-jobs/9102/segmentation?part=lesions',
    ])

    // A second consumer of either part is served from the cache.
    inScope(() => useSegmentationEnvelope(jobId, 'lesions'))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)

    // Clearing the job drops every part; the three live consumers re-fetch.
    clearSegmentationEnvelopeCache(9102)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(4)
    scope.stop()
    clearSegmentationEnvelopeCache(9102)
  })
})
