import { describe, expect, it } from 'vitest'

import { hasFundusGeometry } from '../retinalGeometry'
import type { GeometryJson } from '@/api/retinal'

const bscan = {
  dim_x_ascans: 512, dim_y_rows: 496, dim_z_bscans: 49,
  pixel_axial_mm: 0.00387, pixel_lateral_mm: 0.01155, pixel_slice_mm: 0.121,
}

describe('hasFundusGeometry (DR-039)', () => {
  it('is true for an .e2e source that placed the scan on its SLO', () => {
    const g: GeometryJson = {
      fundus: { width_px: 768, height_px: 768, lateral_mm_per_px: 0.0116, slice_mm_per_px: 0.0116 },
      bscan,
      bscan_positions_fundus_px: [{ z: 0, x1: 1, y1: 1, x2: 2, y2: 2 }],
      scan_bbox_fundus_px: { x: 0, y: 0, width: 10, height: 10 },
      fovea_estimate_fundus_px: { x: 5, y: 5, bscan_z: 24, ascan_x: 256, source: 'volume-center-mvp' },
    }
    expect(hasFundusGeometry(g)).toBe(true)
  })

  it('is false for a DICOM source, whose fundus fields are null', () => {
    const g: GeometryJson = {
      fundus: null,
      bscan,
      bscan_positions_fundus_px: [],
      scan_bbox_fundus_px: null,
      fovea_estimate_fundus_px: null,
      source_format: 'dicom',
    }
    expect(hasFundusGeometry(g)).toBe(false)
  })

  it('is false without a geometry', () => {
    expect(hasFundusGeometry(null)).toBe(false)
    expect(hasFundusGeometry(undefined)).toBe(false)
  })
})
