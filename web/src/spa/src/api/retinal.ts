/**
 * Phase E.7 Wave 4 — Retinal inference read-side API client.
 *
 * Typed wrappers around the four `GET /pages/api/v1/retinal-jobs/...`
 * endpoints owned by {@code RetinalResultsApiController}. The viewer
 * SPA never POSTs through this module — upload + enqueue go through
 * the multipart `oct-upload` endpoint (see {@code api/upload.ts}).
 *
 * <p>Why hand-authored types instead of openapi-typescript codegen:
 * the OpenAPI schema for these endpoints isn't yet exported through
 * the SPA's `codegen:openapi` script (the Wave 3 controller landed
 * after the last codegen sweep). Wave 4 hand-shapes the types here
 * matching the controller's record DTOs byte-for-byte; once the next
 * codegen sweep picks the endpoints up, callers can switch over by
 * replacing the import path without changing the type names.
 *
 * <p>All endpoints surface auth + visibility errors the same way as
 * the rest of the SPA API (401 / 403 / 404 / 5xx → {@link ApiError}).
 * Stores re-throw so the router-level guard can redirect to /login,
 * and the global error toast picks up the rest.
 */

import { apiGet } from './client'

/* ====================================================================== */
/* Public types                                                           */
/* ====================================================================== */

/**
 * Per-task laterality of the underlying OCT volume — ophthalmology
 * convention: OD = right eye, OS = left eye.
 */
export type RetinalLaterality = 'OD' | 'OS'

/**
 * Async state machine for a single inference job. Drives the empty
 * state in the viewer:
 *   - 'queued'        → "Awaiting sidecar…" spinner.
 *   - 'preprocessing' → fundus + geometry not yet on disk.
 *   - 'segmenting'    → segmentation artifacts still streaming.
 *   - 'failed'        → "Metrics couldn't be computed" banner.
 *   - 'succeeded'     → full viewer renders.
 *
 * Phase E.7 Wave 4 — kept open with `string` to match the controller's
 * `j.status` column shape; the SPA only branches on the four canonical
 * values above, anything else falls through to the "raw" state.
 */
export type RetinalJobStatus =
  | 'queued'
  | 'preprocessing'
  | 'segmenting'
  | 'succeeded'
  | 'failed'
  | string

/** Inference task — drives KPI / overlay / per-B-scan-trace layout. */
export type RetinalTask = 'fluid' | 'onl' | 'pr' | 'ga' | string

/** Primary metric — `null` when the job hasn't produced one yet. */
export interface PrimaryMetric {
  value: number
  unit: string
}

/**
 * Fat DTO returned by {@code GET /retinal-jobs/{jobId}}.
 *
 * `outputPayload` is intentionally untyped (Wave 2 emits a different
 * shape per task — see the per-task `OutputPayload*` shapes below for
 * the discriminator the viewer uses).
 */
export interface RetinalJobDetail {
  jobId: number
  eventCrfId: number
  task: RetinalTask
  laterality: RetinalLaterality
  status: RetinalJobStatus
  modelVersion: string | null
  enqueuedAt: string | null
  completedAt: string | null
  e2eUuid: string | null
  primaryMetric: PrimaryMetric | null
  /** Raw map from Wave 2's FluidMetric / OnlMetric / PrMetric / GaMetric. */
  outputPayload: Record<string, unknown>
  confidence: number | null
  artifactNames: string[]
  /** Per-scan companion files — subset of `["bscan.dcm","fundus.png","geometry.json"]`. */
  companionNames: string[]
  fundusUrl: string | null
  geometryUrl: string | null
  bscanDcmUrl: string | null
}

/** Lean summary returned by the per-event-CRF + per-subject list endpoints. */
export interface RetinalJobSummary {
  jobId: number
  task: RetinalTask
  laterality: RetinalLaterality
  status: RetinalJobStatus
  modelVersion: string | null
  completedAt: string | null
  primaryMetric: PrimaryMetric | null
}

/* ---- Per-task output payload shapes ---------------------------------- */

/**
 * `fluid` task — payload of {@link RetinalJobDetail.outputPayload}.
 *
 * Mirrors Wave 2's {@code FluidMetric}: per-B-scan area arrays + ETDRS
 * sub-totals + voxel volume + biomarker totals. The viewer reads:
 *   - {@link FluidPayload.biomarkers}     → KPI strip.
 *   - {@link FluidPayload.etdrs_mm3}      → ETDRS sub-totals table.
 *   - {@link FluidPayload.per_bscan_mm2}  → per-B-scan trace chart.
 */
export interface FluidPayload {
  biomarkers: {
    irf_mm3: number
    srf_mm3: number
    ped_mm3: number
    total_mm3: number
  }
  etdrs_mm3: {
    central_1mm: FluidEtdrsRing
    central_3mm: FluidEtdrsRing
    central_6mm: FluidEtdrsRing
  }
  etdrs_center: EtdrsCenter
  voxel_volume_mm3: number
  per_bscan_mm2: {
    irf: number[]
    srf: number[]
    ped: number[]
  }
  segmentation_file?: string
}

export interface FluidEtdrsRing {
  irf: number
  srf: number
  ped: number
  total: number
}

export interface EtdrsCenter {
  bscan_z: number
  ascan_x: number
  source: string
}

/** `onl` / `pr` task payload — outer-nuclear / photoreceptor thickness. */
export interface ThicknessPayload {
  thickness_mean_um: number
  valid_ascans: number
  total_ascans: number
  surface_csvs: string[]
  axial_mm_per_px: number
}

/** `ga` task — geographic-atrophy area + per-B-scan trace. */
export interface GaPayload {
  ga_area_mm2: number
  hot_pixel_count: number
  rpel_csv: string
  etdrs_mm2: {
    central_1mm: number
    central_3mm: number
    central_6mm: number
  }
  etdrs_center: EtdrsCenter
  per_bscan_mm2: number[]
}

/* ---- geometry.json shape --------------------------------------------- */

/**
 * The {@code geometry.json} companion file written by the preprocess
 * sidecar. Shape pinned by Wave 1A.
 *
 * <p>Coordinate system: {@code fundus.png} is rendered in its native
 * pixel space — the viewer's overlay SVG sets
 * {@code viewBox="0 0 fundus.width_px fundus.height_px"} and every
 * overlay coordinate is in fundus pixels. Conversions:
 *
 *   - mm → fundus px (lateral): multiply by {@code 1 / lateral_mm_per_px}
 *   - mm → fundus px (slice):   multiply by {@code 1 / slice_mm_per_px}
 */
export interface GeometryJson {
  fundus: {
    width_px: number
    height_px: number
    lateral_mm_per_px: number
    slice_mm_per_px: number
  }
  bscan: {
    dim_x_ascans: number
    dim_y_rows: number
    dim_z_bscans: number
    pixel_axial_mm: number
    pixel_lateral_mm: number
    pixel_slice_mm: number
  }
  /** One polyline per B-scan in fundus-pixel space; `z` is the slice index. */
  bscan_positions_fundus_px: Array<{
    z: number
    x1: number
    y1: number
    x2: number
    y2: number
  }>
  /** Bounding box of the OCT scan footprint on the fundus image (fundus px). */
  scan_bbox_fundus_px: {
    x: number
    y: number
    width: number
    height: number
  }
  /**
   * Fovea estimate — MVP uses {@code volume-center-mvp} (volume center +
   * B-scan/A-scan derivation). Future replacement (true detection) will
   * change the {@code source} string only; consumers should not rely on
   * the value for medical decision-making.
   */
  fovea_estimate_fundus_px: {
    x: number
    y: number
    bscan_z: number
    ascan_x: number
    source: string
  }
}

/* ====================================================================== */
/* Endpoints                                                              */
/* ====================================================================== */

const BASE = '/pages/api/v1/retinal-jobs'

/**
 * `GET /pages/api/v1/retinal-jobs/{jobId}` — fat DTO for the metrics
 * viewer. Single source of truth for the per-job render.
 */
export function getJob(jobId: number): Promise<RetinalJobDetail> {
  return apiGet<RetinalJobDetail>(`${BASE}/${jobId}`)
}

/**
 * `GET /pages/api/v1/event-crfs/{eventCrfId}/retinal-jobs` — per-CRF
 * summary list, ordered enqueued-at desc by the backend.
 */
export function listEventCrfJobs(eventCrfId: number): Promise<RetinalJobSummary[]> {
  return apiGet<RetinalJobSummary[]>(
    `/pages/api/v1/event-crfs/${eventCrfId}/retinal-jobs`,
  )
}

/**
 * `GET /pages/api/v1/study-subjects/{studySubjectId}/retinal-jobs` —
 * subject-scoped summary list across every event-CRF the subject owns.
 */
export function listSubjectJobs(studySubjectId: number): Promise<RetinalJobSummary[]> {
  return apiGet<RetinalJobSummary[]>(
    `/pages/api/v1/study-subjects/${studySubjectId}/retinal-jobs`,
  )
}

/**
 * Build the absolute URL for a single artifact / companion file. Used
 * as the {@code src} of {@code <img>} for {@code fundus.png}, the
 * {@code href} of download anchors, and as the fetch target for
 * {@link fetchGeometry}.
 *
 * <p>Mirrors the URL shape baked into the controller's
 * {@code fundusUrl} / {@code geometryUrl} / {@code bscanDcmUrl}
 * shorthands so direct lookups stay in lockstep with the DTO.
 */
export function artifactUrl(jobId: number, name: string): string {
  return `/LibreClinica${BASE}/${jobId}/artifacts/${encodeURIComponent(name)}`
}

/**
 * `GET …/artifacts/geometry.json` — typed. The controller serves a JSON
 * body for this companion, so we go through `fetch` directly rather
 * than the JSON-only `apiGet` wrapper (which would unnecessarily parse
 * the body twice given the controller already advertises
 * {@code application/json}). Same-origin cookie auth carries over.
 */
export async function fetchGeometry(jobId: number): Promise<GeometryJson> {
  const url = artifactUrl(jobId, 'geometry.json')
  const response = await fetch(url, {
    method: 'GET',
    credentials: 'include',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) {
    throw new Error(`Failed to fetch geometry for job ${jobId}: HTTP ${response.status}`)
  }
  return (await response.json()) as GeometryJson
}
