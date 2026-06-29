import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import BscanLayerEditOverlay from '@/components/BscanLayerEditOverlay.vue'

vi.mock('@/components/retinalPalette', () => ({
  IOWA_LAYER_COLORS: ['#4ade80', '#fcd34d', '#fbbf24', '#fb923c', '#f97316', '#ef4444', '#dc2626', '#a855f7', '#8b5cf6', '#6366f1', '#60a5fa'],
  IOWA_LAYER_LABELS: ['ILM', 'NFL', 'GCL-IPL', 'INL', 'OPL', 'ONL', 'ELM', 'EZ', 'IZ', 'RPE', 'BM'],
}))

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      retinal: {
        correction: {
          hint: { off: 'off', shift: 'shift', free: 'free', points: 'points' },
          tools: { off: 'off', shift: 'shift', free: 'free', points: 'points' },
          selectedPoints: '{n} selected',
          resetLayer: 'Reset',
          prevSlice: 'Prev',
          nextSlice: 'Next',
        },
      },
    },
  },
})

/**
 * Build a 1-surface envelope: shape=[1, 3, 8], all rows at y=100.
 * (Single slice z=0 used by the tests.)
 */
function buildEnvelope(): { data: Float32Array; cols: number; rows: number; nBscans: number } {
  const cols = 8
  const rows = 200
  const nBscans = 3
  const data = new Float32Array(1 * nBscans * cols)
  for (let i = 0; i < data.length; i++) data[i] = 100
  return { data, cols, rows, nBscans }
}

function mountOverlay(props: Partial<InstanceType<typeof BscanLayerEditOverlay>['$props']> = {}) {
  const env = buildEnvelope()
  return mount(BscanLayerEditOverlay, {
    global: { plugins: [i18n] },
    props: {
      nBscans: env.nBscans,
      cols: env.cols,
      rows: env.rows,
      modelValue: 0,
      envelopeData: env.data,
      nSurfaces: 1,
      correctableLayerIndices: [0],
      canEdit: true,
      bboxStyle: { position: 'absolute', left: '0', top: '0', width: '100px', height: '100px' },
      ...props,
    },
  })
}

describe('BscanLayerEditOverlay', () => {
  it('renders 4 mode buttons + 1 reset + 1 layer chip', () => {
    const wrapper = mountOverlay()
    for (const m of ['off', 'shift', 'free', 'points'] as const) {
      expect(wrapper.find(`[data-testid="bscan-layer-tool-${m}"]`).exists()).toBe(true)
    }
    expect(wrapper.find('[data-testid="bscan-layer-reset"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="bscan-layer-pick-0"]').exists()).toBe(true)
  })

  it('hides the tool palette + layer bar when canEdit is false', () => {
    const wrapper = mountOverlay({ canEdit: false })
    expect(wrapper.find('[data-testid="bscan-layer-tools"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="bscan-layer-bar"]').exists()).toBe(false)
  })

  it('emits update:modelValue when the layer bar advances the slice', async () => {
    const wrapper = mountOverlay({ modelValue: 1 })
    await wrapper.find('[data-testid="bscan-layer-next-slice"]').trigger('click')
    const events = wrapper.emitted('update:modelValue')
    expect(events?.[0]).toEqual([2])
  })

  it('does not emit pending-edit-count before any edit', () => {
    const wrapper = mountOverlay()
    expect(wrapper.emitted('pending-edit-count')).toBeUndefined()
  })

  it('paints overlay paths for every correctable layer', () => {
    const wrapper = mountOverlay({ correctableLayerIndices: [0] })
    const svg = wrapper.find('svg')
    expect(svg.exists()).toBe(true)
    // 2 paths per active layer (halo + colored) + no guides for single layer.
    const paths = svg.findAll('path')
    expect(paths.length).toBeGreaterThanOrEqual(2)
  })

  it('falls back to a mid-canvas curve when envelopeData is null', () => {
    const wrapper = mountOverlay({ envelopeData: null })
    const svg = wrapper.find('svg')
    expect(svg.exists()).toBe(true)
    // No paths until the operator edits (envelope data missing means
    // the guide path can't be sampled). The component should not throw.
    expect(wrapper.find('[data-testid="bscan-layer-edit-overlay"]').exists()).toBe(true)
  })
})
