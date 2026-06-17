/**
 * Phase E.7 Wave 4 — retinal API client smoke tests.
 *
 * Two layers:
 *  1. URL-shape pins for the three GET endpoints — guards against
 *     accidental drift between the SPA and the Wave 3 controller.
 *  2. {@code artifactUrl} static-construction pin so the absolute URL
 *     stays in lockstep with the controller's {@code fundusUrl} /
 *     {@code geometryUrl} shorthands.
 */
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  artifactUrl,
  fetchGeometry,
  getJob,
  listEventCrfJobs,
  listSubjectJobs,
} from '../retinal'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('api/retinal — URL construction', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('artifactUrl builds the per-artifact absolute URL', () => {
    expect(artifactUrl(42, 'bscan.dcm')).toBe(
      '/LibreClinica/pages/api/v1/retinal-jobs/42/artifacts/bscan.dcm',
    )
  })

  it('artifactUrl percent-encodes artifact names with reserved characters', () => {
    expect(artifactUrl(42, 'fluid mask (1).npz')).toBe(
      '/LibreClinica/pages/api/v1/retinal-jobs/42/artifacts/fluid%20mask%20(1).npz',
    )
  })

  it('getJob hits /pages/api/v1/retinal-jobs/{jobId}', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ jobId: 7 }))
    vi.stubGlobal('fetch', fetchMock)

    await getJob(7)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const url = fetchMock.mock.calls[0][0] as string
    expect(url).toBe('/LibreClinica/pages/api/v1/retinal-jobs/7')
  })

  it('listEventCrfJobs hits the per-CRF list URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]))
    vi.stubGlobal('fetch', fetchMock)

    await listEventCrfJobs(123)

    expect(fetchMock).toHaveBeenCalledWith(
      '/LibreClinica/pages/api/v1/event-crfs/123/retinal-jobs',
      expect.any(Object),
    )
  })

  it('listSubjectJobs hits the per-subject list URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]))
    vi.stubGlobal('fetch', fetchMock)

    await listSubjectJobs(99)

    expect(fetchMock).toHaveBeenCalledWith(
      '/LibreClinica/pages/api/v1/study-subjects/99/retinal-jobs',
      expect.any(Object),
    )
  })

  it('fetchGeometry hits the geometry.json artifact endpoint', async () => {
    const geom = { fundus: { width_px: 768 } }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(geom))
    vi.stubGlobal('fetch', fetchMock)

    const parsed = await fetchGeometry(42)

    expect(fetchMock).toHaveBeenCalledWith(
      '/LibreClinica/pages/api/v1/retinal-jobs/42/artifacts/geometry.json',
      expect.objectContaining({ credentials: 'include' }),
    )
    expect(parsed).toEqual(geom)
  })
})
