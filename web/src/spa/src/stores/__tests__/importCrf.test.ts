/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useImportCrfStore } from '../importCrf'
import { ApiError, ApiNetworkError } from '@/api/client'
import type {
  ImportCrfPreview,
  ImportCrfRowsPage,
  ImportCrfCommitResult,
} from '@/types/importCrf'

/**
 * Phase E.6 {@code bulk-import} — Pinia store coverage for
 * {@link useImportCrfStore}.
 *
 * <p>Three flow surfaces are pinned:
 * <ol>
 *   <li>{@code uploadFile} — happy-path preview hydrate + 415 / 422 /
 *       400 (XSD) / 401 / network failure modes. {@code uploadFile}
 *       uses {@code fetch} directly (multipart), so we stub global
 *       {@code fetch}.</li>
 *   <li>{@code fetchMoreRows} — happy-path window append + 410 expiry
 *       (clears preview, sets {@code tokenExpired}).</li>
 *   <li>{@code commit} — happy-path result hydrate, RFC pass-through,
 *       410 expiry, 501 staged-persistence message surfaces in
 *       {@code error}.</li>
 * </ol>
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
  }
})

import { apiGet, apiPost } from '@/api/client'

const PREVIEW: ImportCrfPreview = {
  previewToken: 'tok-123',
  studyOid: 'S_DEMO',
  filename: 'sample.xml',
  subjectCount: 3,
  eventCount: 8,
  crfCount: 17,
  rowCount: 412,
  insertCount: 380,
  overwriteCount: 25,
  errorCount: 5,
  warningCount: 2,
  rows: [
    { status: 'ready', action: 'insert', subjectOid: 'M-001', eventOid: 'V1', crfOid: 'CRF_A', itemOid: 'I1', before: null, after: '1962', detail: null },
    { status: 'overwrite', action: 'overwrite', subjectOid: 'M-001', eventOid: 'V2', crfOid: 'CRF_B', itemOid: 'I2', before: 'Severe', after: 'Moderate', detail: null },
  ],
  issues: [
    { scope: 'row', identifier: 'M-003/V2/CRF_A/I9', severity: 'ERROR', message: 'OCRERR_BAD_VALUE' },
  ],
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

describe('useImportCrfStore', () => {
  let fetchSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /* ---------------------------------------------------------------- */
  /* initial state + reset                                              */
  /* ---------------------------------------------------------------- */

  it('starts empty', () => {
    const store = useImportCrfStore()
    expect(store.preview).toBeNull()
    expect(store.extraRows).toEqual([])
    expect(store.commitResult).toBeNull()
    expect(store.isUploading).toBe(false)
    expect(store.isFetchingRows).toBe(false)
    expect(store.isCommitting).toBe(false)
    expect(store.error).toBeNull()
    expect(store.tokenExpired).toBe(false)
  })

  it('reset() clears all state', async () => {
    const store = useImportCrfStore()
    store.preview = { ...PREVIEW }
    store.extraRows = [PREVIEW.rows[0]]
    store.error = 'something'
    store.tokenExpired = true
    store.reset()
    expect(store.preview).toBeNull()
    expect(store.extraRows).toEqual([])
    expect(store.error).toBeNull()
    expect(store.tokenExpired).toBe(false)
  })

  /* ---------------------------------------------------------------- */
  /* uploadFile                                                         */
  /* ---------------------------------------------------------------- */

  it('uploadFile() hydrates preview on 200', async () => {
    fetchSpy.mockResolvedValueOnce(jsonResponse(PREVIEW))
    const store = useImportCrfStore()
    const file = new File(['<ODM/>'], 'sample.xml', { type: 'application/xml' })
    const res = await store.uploadFile(file)
    expect(res.ok).toBe(true)
    if (res.ok) expect(res.preview.previewToken).toBe('tok-123')
    expect(store.preview).not.toBeNull()
    expect(store.preview?.rowCount).toBe(412)
    expect(store.isUploading).toBe(false)
    expect(fetchSpy).toHaveBeenCalledWith(
      '/LibreClinica/pages/api/v1/import',
      expect.objectContaining({ method: 'POST', credentials: 'include' }),
    )
  })

  it('uploadFile() flips isUploading during the call', async () => {
    let resolveFetch: (r: Response) => void = () => undefined
    fetchSpy.mockReturnValueOnce(new Promise<Response>((resolve) => { resolveFetch = resolve }))
    const store = useImportCrfStore()
    const file = new File(['<ODM/>'], 'sample.xml', { type: 'application/xml' })
    const p = store.uploadFile(file)
    // microtask flush — isUploading should flip true before fetch resolves
    await Promise.resolve()
    expect(store.isUploading).toBe(true)
    resolveFetch(jsonResponse(PREVIEW))
    await p
    expect(store.isUploading).toBe(false)
  })

  it('uploadFile() surfaces 415 wrong-file-type', async () => {
    fetchSpy.mockResolvedValueOnce(jsonResponse(
      { message: 'Only .xml CRF data files are accepted' }, 415,
    ))
    const store = useImportCrfStore()
    const file = new File([''], 'evil.csv', { type: 'text/csv' })
    const res = await store.uploadFile(file)
    expect(res.ok).toBe(false)
    if (!res.ok) expect(res.message).toContain('.xml')
    expect(store.preview).toBeNull()
    expect(store.error).toContain('.xml')
  })

  it('uploadFile() surfaces 422 wrong study', async () => {
    fetchSpy.mockResolvedValueOnce(jsonResponse(
      { message: 'XML studyOID does not match the active study' }, 422,
    ))
    const store = useImportCrfStore()
    const res = await store.uploadFile(new File([], 'a.xml'))
    expect(res.ok).toBe(false)
    if (!res.ok) expect(res.message).toContain('does not match')
  })

  it('uploadFile() surfaces 400 XSD failure with per-field errors', async () => {
    fetchSpy.mockResolvedValueOnce(jsonResponse(
      { message: 'ODM unmarshal failed', errors: [{ field: 'file', message: 'malformed XML' }] }, 400,
    ))
    const store = useImportCrfStore()
    const res = await store.uploadFile(new File([], 'a.xml'))
    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.message).toContain('unmarshal')
      expect(res.errors).toContain('malformed XML')
    }
  })

  it('uploadFile() throws on 401 so the router guard can react', async () => {
    fetchSpy.mockResolvedValueOnce(jsonResponse({ message: 'Not authenticated' }, 401))
    const store = useImportCrfStore()
    await expect(store.uploadFile(new File([], 'a.xml'))).rejects.toBeInstanceOf(ApiError)
  })

  it('uploadFile() reports network failure', async () => {
    fetchSpy.mockRejectedValueOnce(new ApiNetworkError('boom', new Error('refused')))
    const store = useImportCrfStore()
    const res = await store.uploadFile(new File([], 'a.xml'))
    expect(res.ok).toBe(false)
    if (!res.ok) expect(res.message).toContain('unreachable')
  })

  it('uploadFile() clears previous error + token-expired flags', async () => {
    fetchSpy.mockResolvedValueOnce(jsonResponse(PREVIEW))
    const store = useImportCrfStore()
    store.error = 'stale'
    store.tokenExpired = true
    store.extraRows = [PREVIEW.rows[0]]
    await store.uploadFile(new File([], 'a.xml'))
    expect(store.error).toBeNull()
    expect(store.tokenExpired).toBe(false)
    expect(store.extraRows).toEqual([])
  })

  /* ---------------------------------------------------------------- */
  /* fetchMoreRows                                                      */
  /* ---------------------------------------------------------------- */

  it('fetchMoreRows() refuses without a preview', async () => {
    const store = useImportCrfStore()
    const res = await store.fetchMoreRows(200)
    expect(res.ok).toBe(false)
    if (!res.ok) expect(res.message).toContain('upload')
  })

  it('fetchMoreRows() appends rows on 200', async () => {
    const page: ImportCrfRowsPage = {
      total: 412,
      offset: 200,
      limit: 200,
      rows: [PREVIEW.rows[1]],
    }
    vi.mocked(apiGet).mockResolvedValueOnce(page)
    const store = useImportCrfStore()
    store.preview = { ...PREVIEW }
    const res = await store.fetchMoreRows(200, 200)
    expect(res.ok).toBe(true)
    expect(store.extraRows.length).toBe(1)
    expect(vi.mocked(apiGet)).toHaveBeenCalledWith(
      '/pages/api/v1/import/tok-123/rows?offset=200&limit=200',
    )
  })

  it('fetchMoreRows() expires the preview on 410', async () => {
    vi.mocked(apiGet).mockRejectedValueOnce(
      new ApiError(410, 'gone', { message: 'token expired' }),
    )
    const store = useImportCrfStore()
    store.preview = { ...PREVIEW }
    const res = await store.fetchMoreRows(200)
    expect(res.ok).toBe(false)
    if (!res.ok) expect(res.expired).toBe(true)
    expect(store.preview).toBeNull()
    expect(store.tokenExpired).toBe(true)
  })

  it('fetchMoreRows() propagates 401', async () => {
    vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(401, 'no', null))
    const store = useImportCrfStore()
    store.preview = { ...PREVIEW }
    await expect(store.fetchMoreRows(200)).rejects.toBeInstanceOf(ApiError)
  })

  /* ---------------------------------------------------------------- */
  /* commit                                                             */
  /* ---------------------------------------------------------------- */

  it('commit() refuses without a preview', async () => {
    const store = useImportCrfStore()
    const res = await store.commit('reason', 'replace')
    expect(res.ok).toBe(false)
    if (!res.ok) expect(res.message).toContain('upload')
  })

  it('commit() hydrates commitResult on 200', async () => {
    const result: ImportCrfCommitResult = {
      rowsInserted: 380, rowsOverwritten: 25, rowsSkipped: 5,
      discrepancyNotes: 2,
      committedAt: '2026-06-06T10:00:00Z', auditLogStudyId: 11,
    }
    vi.mocked(apiPost).mockResolvedValueOnce(result)
    const store = useImportCrfStore()
    store.preview = { ...PREVIEW }
    const res = await store.commit('Source-data reconciliation', 'replace')
    expect(res.ok).toBe(true)
    if (res.ok) expect(res.result.rowsInserted).toBe(380)
    expect(store.commitResult?.rowsInserted).toBe(380)
    expect(vi.mocked(apiPost)).toHaveBeenCalledWith(
      '/pages/api/v1/import/commit',
      {
        previewToken: 'tok-123',
        reasonForChange: 'Source-data reconciliation',
        overwriteMode: 'replace',
      },
    )
  })

  it('commit() drops blank RFC to undefined in the request', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({
      rowsInserted: 0, rowsOverwritten: 0, rowsSkipped: 0,
      discrepancyNotes: 0, committedAt: '2026-06-06T10:00:00Z', auditLogStudyId: 11,
    } satisfies ImportCrfCommitResult)
    const store = useImportCrfStore()
    store.preview = { ...PREVIEW }
    await store.commit('   ', 'skip')
    expect(vi.mocked(apiPost)).toHaveBeenCalledWith(
      '/pages/api/v1/import/commit',
      { previewToken: 'tok-123', reasonForChange: undefined, overwriteMode: 'skip' },
    )
  })

  it('commit() expires preview on 410', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(410, 'gone', { message: 'expired' }),
    )
    const store = useImportCrfStore()
    store.preview = { ...PREVIEW }
    const res = await store.commit('rfc', 'replace')
    expect(res.ok).toBe(false)
    if (!res.ok) expect(res.expired).toBe(true)
    expect(store.preview).toBeNull()
    expect(store.tokenExpired).toBe(true)
  })

  it('commit() surfaces 400 RFC-required as a non-expiry failure', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(400, 'rfc required', {
        message: 'Validation failed',
        errors: [{ field: 'reasonForChange', message: 'required when overwrites apply' }],
      }),
    )
    const store = useImportCrfStore()
    store.preview = { ...PREVIEW }
    const res = await store.commit(null, 'replace')
    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.expired).toBe(false)
      expect(res.message).toContain('Validation failed')
    }
  })

  it('commit() surfaces 501 staged-persistence as a regular failure', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(501, 'not impl', {
        message: 'persistence extraction is not yet implemented',
      }),
    )
    const store = useImportCrfStore()
    store.preview = { ...PREVIEW }
    const res = await store.commit('rfc', 'replace')
    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.expired).toBe(false)
      expect(res.message).toContain('persistence')
    }
  })

  it('commit() propagates 403', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(403, 'forbidden', null))
    const store = useImportCrfStore()
    store.preview = { ...PREVIEW }
    await expect(store.commit('rfc', 'replace')).rejects.toBeInstanceOf(ApiError)
  })
})
