/**
 * DR-039 — whether a scan's geometry can be drawn on a fundus image.
 *
 * Only an `.e2e` source carries an SLO: a DICOM OCT volume has no
 * `fundus.png`, and its `geometry.json` has `fundus`, the scan box and the
 * fovea estimate null. Kept out of `api/retinal` so view tests that mock the
 * API module still get the real check.
 */
import type { FundusGeometryJson, GeometryJson } from '@/api/retinal'

/** True when the geometry places the scan on a fundus image (never for a DICOM source). */
export function hasFundusGeometry(g: GeometryJson | null | undefined): g is FundusGeometryJson {
  return !!g && !!g.fundus && !!g.scan_bbox_fundus_px && !!g.fovea_estimate_fundus_px
}
