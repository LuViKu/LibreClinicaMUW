/**
 * Phase E.7 Wave 4 — FundusOverlay spec.
 *
 * Pins the registration-correct overlays:
 *   1. Scan-bbox rect carries the geometry's x/y/width/height verbatim.
 *   2. Three ETDRS rings centred on the fovea estimate at the math-
 *      derived radii (mm → fundus-px via lateral_mm_per_px).
 *   3. For 'fluid' payloads: one B-scan polyline per
 *      bscan_positions_fundus_px entry, dominant biomarker → stroke colour.
 *   4. Hover on a polyline emits the matching `hoverBscan` z index.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import FundusOverlay from '../FundusOverlay.vue'
import { BIOMARKER_COLORS } from '../retinalPalette'
import deMessages from '@/locales/de.json'
import type { GeometryJson } from '@/api/retinal'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

function makeGeometry(overrides: Partial<GeometryJson> = {}): GeometryJson {
  return {
    fundus: {
      width_px: 768,
      height_px: 768,
      lateral_mm_per_px: 0.01, // 1 mm → 100 px
      slice_mm_per_px: 0.06,
    },
    bscan: {
      dim_x_ascans: 512,
      dim_y_rows: 496,
      dim_z_bscans: 49,
      pixel_axial_mm: 0.00391,
      pixel_lateral_mm: 0.0114,
      pixel_slice_mm: 0.121,
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
      bscan_z: 24,
      ascan_x: 256,
      source: 'volume-center-mvp',
    },
    ...overrides,
  }
}

function makeFluidPayload() {
  return {
    biomarkers: { irf_mm3: 1, srf_mm3: 0, ped_mm3: 0, total_mm3: 1 },
    per_bscan_mm2: {
      irf: [1, 0, 0],
      srf: [0, 1, 0],
      ped: [0, 0, 1],
    },
  }
}

describe('FundusOverlay — registration', () => {
  it('renders the scan-bbox rect from geometry.scan_bbox_fundus_px', () => {
    const geometry = makeGeometry()
    const wrapper = mount(FundusOverlay, {
      global: { plugins: [i18n] },
      props: {
        fundusUrl: '/fake/fundus.png',
        geometry,
        payload: makeFluidPayload(),
        task: 'fluid',
        laterality: 'OD',
      },
    })
    const rect = wrapper.find('[data-testid="scan-bbox"]')
    expect(rect.exists()).toBe(true)
    expect(rect.attributes('x')).toBe('100')
    expect(rect.attributes('y')).toBe('100')
    expect(rect.attributes('width')).toBe('500')
    expect(rect.attributes('height')).toBe('200')
  })

  it('renders three ETDRS rings centred on the fovea estimate with correct radii (mm → px)', () => {
    const geometry = makeGeometry()
    const wrapper = mount(FundusOverlay, {
      global: { plugins: [i18n] },
      props: {
        fundusUrl: '/fake/fundus.png',
        geometry,
        payload: makeFluidPayload(),
        task: 'fluid',
        laterality: 'OD',
      },
    })
    const ringsGroup = wrapper.find('[data-testid="etdrs-rings"]')
    expect(ringsGroup.exists()).toBe(true)
    // 2026-06-25 — the etdrs-rings group also contains a center hit-test
    // circle (etdrs-region-center) for clickable region toggling, so a
    // bare findAll('circle') now returns 4. Filter to the dashed
    // reference circles only (they're the ones with stroke-dasharray).
    const circles = ringsGroup
      .findAll('circle')
      .filter((c) => c.attributes('stroke-dasharray') === '4 4')
    expect(circles.length).toBe(3)
    // 1mm diameter → 0.5 mm radius / 0.01 mm-per-px = 50 px
    // 3mm diameter → 1.5 mm radius                = 150 px
    // 6mm diameter → 3.0 mm radius                = 300 px
    expect(circles[0].attributes('r')).toBe('50')
    expect(circles[1].attributes('r')).toBe('150')
    expect(circles[2].attributes('r')).toBe('300')
    // All centred on the fovea estimate.
    for (const c of circles) {
      expect(c.attributes('cx')).toBe('384')
      expect(c.attributes('cy')).toBe('384')
    }
  })

  // 2026-06-25 — the per-B-scan biomarker polylines were replaced by a
  // single current-B-scan position line that hooks up via the BscanViewer
  // hover instead of mouseenter on the fundus side (see FundusOverlay.vue
  // line 886: "current-B-scan position line. Drawn from the RAW geometry
  // positions, not the biomarker-filtered list"). Skip the legacy
  // assertions — the contract is now exercised via the integration test
  // in NamdViewerTab.test.ts.
  it.skip('renders one polyline per B-scan, dominant biomarker → stroke colour (fluid task)', () => {
    const wrapper = mount(FundusOverlay, {
      global: { plugins: [i18n] },
      props: {
        fundusUrl: '/fake/fundus.png',
        geometry: makeGeometry(),
        payload: makeFluidPayload(),
        task: 'fluid',
        laterality: 'OD',
      },
    })
    const lines = wrapper.findAll('[data-testid^="bscan-line-"]')
    expect(lines.length).toBe(3)
    expect(lines[0].attributes('data-dominant')).toBe('IRF')
    expect(lines[0].attributes('stroke')).toBe(BIOMARKER_COLORS.irf)
    expect(lines[1].attributes('data-dominant')).toBe('SRF')
    expect(lines[1].attributes('stroke')).toBe(BIOMARKER_COLORS.srf)
    expect(lines[2].attributes('data-dominant')).toBe('PED')
    expect(lines[2].attributes('stroke')).toBe(BIOMARKER_COLORS.ped)
  })

  it.skip('renders neutral lines for onl / pr tasks (no per-B-scan biomarker)', () => {
    const wrapper = mount(FundusOverlay, {
      global: { plugins: [i18n] },
      props: {
        fundusUrl: '/fake/fundus.png',
        geometry: makeGeometry(),
        payload: { thickness_mean_um: 100, valid_ascans: 1, total_ascans: 1 },
        task: 'onl',
        laterality: 'OD',
      },
    })
    const lines = wrapper.findAll('[data-testid^="bscan-line-"]')
    // Three polylines, none coloured as a biomarker.
    expect(lines.length).toBe(3)
    for (const line of lines) {
      expect(line.attributes('data-dominant')).toBe('—')
    }
  })

  it.skip('emits hoverBscan with the z index on line mouseenter', async () => {
    const wrapper = mount(FundusOverlay, {
      global: { plugins: [i18n] },
      props: {
        fundusUrl: '/fake/fundus.png',
        geometry: makeGeometry(),
        payload: makeFluidPayload(),
        task: 'fluid',
        laterality: 'OD',
      },
    })
    // The hit area + visible line are both inside the per-z <g>. Trigger
    // mouseenter on the wrapping group so the bound listener fires.
    const groups = wrapper.find('[data-testid="bscan-lines"]').findAll('g')
    // 1st <g> is the wrapping `g`, then one per B-scan.
    await groups[0].trigger('mouseenter')
    const emitted = wrapper.emitted('hoverBscan')
    expect(emitted).toBeTruthy()
    expect(emitted![0]).toEqual([0])
  })

  it.skip('emits null on mouseleave', async () => {
    const wrapper = mount(FundusOverlay, {
      global: { plugins: [i18n] },
      props: {
        fundusUrl: '/fake/fundus.png',
        geometry: makeGeometry(),
        payload: makeFluidPayload(),
        task: 'fluid',
        laterality: 'OD',
      },
    })
    const groups = wrapper.find('[data-testid="bscan-lines"]').findAll('g')
    await groups[0].trigger('mouseenter')
    await groups[0].trigger('mouseleave')
    const emitted = wrapper.emitted('hoverBscan')
    expect(emitted).toBeTruthy()
    // First [0], second [null].
    expect(emitted!.length).toBeGreaterThanOrEqual(2)
    expect(emitted![emitted!.length - 1]).toEqual([null])
  })

  it('renders the fovea crosshair with a <title> tooltip carrying the source', () => {
    const wrapper = mount(FundusOverlay, {
      global: { plugins: [i18n] },
      props: {
        fundusUrl: '/fake/fundus.png',
        geometry: makeGeometry(),
        payload: makeFluidPayload(),
        task: 'fluid',
        laterality: 'OD',
      },
    })
    const crosshair = wrapper.find('[data-testid="fovea-crosshair"]')
    expect(crosshair.exists()).toBe(true)
    expect(crosshair.find('title').text()).toContain('volume-center-mvp')
  })
})
