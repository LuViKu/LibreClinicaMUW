/**
 * Browser de-identification (layer 1) as the upload workbench drives it, with
 * the deployment flag on (`GET /me` → deidentificationRequired).
 *
 * What is pinned here, because the server rejects anything else:
 *  - only .e2e and DICOM are accepted; JPEG/PNG/other are refused in the browser;
 *  - the header's patient ID is matched LOCALLY against the study's labels, and
 *    neither it nor any other header value is in any request payload;
 *  - nothing is sent before the operator ticks the preview confirmation;
 *  - what is sent is the stripped file, under <label>_<date>_<eye>.<ext>,
 *    with deidConfirmed + the SHA-256 of the stripped bytes;
 *  - any strip/sweep failure → the row errors and nothing is uploaded.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import dcmjs from 'dcmjs'

const api = vi.hoisted(() => ({
  resolveRows: vi.fn(),
  preflight: vi.fn(),
  commitFile: vi.fn(),
  undoItem: vi.fn(),
  undoJob: vi.fn(),
  sha256OfFile: vi.fn(),
  listVisibleSubjectLabels: vi.fn(),
}))
vi.mock('@/api/uploadWorkbench', () => ({
  resolveRows: (...a: unknown[]) => api.resolveRows(...a),
  preflight: (...a: unknown[]) => api.preflight(...a),
  commitFile: (...a: unknown[]) => api.commitFile(...a),
  undoItem: (...a: unknown[]) => api.undoItem(...a),
  undoJob: (...a: unknown[]) => api.undoJob(...a),
  sha256OfFile: (...a: unknown[]) => api.sha256OfFile(...a),
  listVisibleSubjectLabels: (...a: unknown[]) => api.listVisibleSubjectLabels(...a),
}))
vi.mock('@/lib/deid/preview', async (orig) => ({
  ...(await orig<typeof import('@/lib/deid/preview')>()),
  previewToUrl: vi.fn(async () => 'blob:preview-1'),
}))

const cs = vi.hoisted(() => ({ cornerstonePreview: vi.fn() }))
vi.mock('@/lib/deid/cornerstonePreview', async (orig) => ({
  ...(await orig<typeof import('@/lib/deid/cornerstonePreview')>()),
  cornerstonePreview: (...a: unknown[]) => cs.cornerstonePreview(...a),
}))

import { useUploadWorkbenchStore } from '@/stores/uploadWorkbench'
import { useAuthStore } from '@/stores/auth'
import {
  PHI, TYPE_ACQ_INFO, asFile, e2eWith, fileBytes, fixtureBytes, fundusChunk,
} from '@/lib/__tests__/deid/testUtils'
import type { AuthenticatedUser } from '@/types/auth'

const LABEL = 'HAE-042'
const SECRET_STRINGS = ['Mustermann', 'Maximilian', 'MRN-778899', '19700101']

function candidate(label: string) {
  return {
    studyId: 1, studyName: 'Study', studyOid: 'S_1', studySubjectId: 9, subjectLabel: label, siteName: null,
    matchingEvent: { studyEventId: 42, eventCrfId: null, definitionLabel: 'V1', dateStart: '2024-05-06', matchPolicy: 'exact' },
  }
}

function setUser(deid: boolean | undefined) {
  const auth = useAuthStore()
  auth.user = { username: 'u', deidentificationRequired: deid } as unknown as AuthenticatedUser
}

/** E2E whose patient_id slot already is a study label, the rest being identity. */
function e2eForLabel(label: string): File {
  return asFile(e2eWith({ ...PHI, pid: label }, [fundusChunk()]), 'Mustermann_Maximilian_OCT.e2e')
}

const { DicomDict } = (dcmjs as unknown as {
  data: { DicomDict: new (meta: Record<string, unknown>) => { dict: Record<string, unknown>; write(): ArrayBuffer } }
}).data

function dicomForPatient(pid: string, extra: Record<string, unknown> = {}, syntax = '1.2.840.10008.1.2.1'): File {
  const d = new DicomDict({
    '00020002': { vr: 'UI', Value: ['1.2.840.10008.5.1.4.1.1.77.1.5.1'] },
    '00020003': { vr: 'UI', Value: ['1.2.3.4'] },
    '00020010': { vr: 'UI', Value: [syntax] },
  })
  d.dict = {
    '00080016': { vr: 'UI', Value: ['1.2.840.10008.5.1.4.1.1.77.1.5.1'] },
    '00080018': { vr: 'UI', Value: ['1.2.3.4'] },
    '00080022': { vr: 'DA', Value: ['20240506'] },
    '00080060': { vr: 'CS', Value: ['OP'] },
    '00100010': { vr: 'PN', Value: [{ Alphabetic: 'Mustermann^Maximilian' }] },
    '00100020': { vr: 'LO', Value: [pid] },
    '00100030': { vr: 'DA', Value: ['19700101'] },
    '00200060': { vr: 'CS', Value: ['L'] },
    '00280002': { vr: 'US', Value: [1] },
    '00280004': { vr: 'CS', Value: ['MONOCHROME2'] },
    '00280010': { vr: 'US', Value: [2] },
    '00280011': { vr: 'US', Value: [2] },
    '00280100': { vr: 'US', Value: [8] },
    '00280101': { vr: 'US', Value: [8] },
    '00280103': { vr: 'US', Value: [0] },
    '7FE00010': { vr: 'OB', Value: [new Uint8Array([1, 2, 3, 4]).buffer] },
    ...extra,
  }
  return asFile(new Uint8Array(d.write()), 'Maximilian_Mustermann.dcm')
}

/** Every argument any mocked request function ever received, as one string. */
function everythingSent(): string {
  const all = [
    ...api.resolveRows.mock.calls, ...api.commitFile.mock.calls, ...api.preflight.mock.calls,
    ...api.sha256OfFile.mock.calls, ...api.undoItem.mock.calls, ...api.undoJob.mock.calls,
  ].map((c) => c.map((a: unknown) => {
    if (a && typeof a === 'object' && 'file' in (a as object)) {
      const r = a as { file: File } & Record<string, unknown>
      return JSON.stringify({ ...r, file: { name: r.file.name, size: r.file.size } })
    }
    return JSON.stringify(a)
  }).join('|'))
  return all.join('\n')
}

beforeEach(() => {
  setActivePinia(createPinia())
  for (const m of Object.values(api)) m.mockReset()
  api.listVisibleSubjectLabels.mockResolvedValue(['HAE-001', LABEL, 'HAE-100'])
  api.resolveRows.mockImplementation(async (_m: string, scans: Array<{ patientId: string }>) => ({
    scans: scans.map((s) => (
      s.patientId === LABEL || s.patientId === 'HAE-001'
        ? { patientId: s.patientId, state: 'suggested', candidates: [candidate(s.patientId)] }
        : { patientId: s.patientId, state: 'nopatient', candidates: [] }
    )),
  }))
  api.commitFile.mockImplementation(async () => ({ ingestItemId: 5, jobId: 77, kind: 'e2e', format: 'e2e', status: 'QUEUED' }))
  setUser(true)
})

describe('de-identifying mode', () => {
  it('is on only for a signed-in (staff) user whose /me says so', () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    expect(store.deidRequired).toBe(true)
    setUser(false)
    expect(store.deidRequired).toBe(false)
    setUser(undefined)
    expect(store.deidRequired).toBe(false)
    setUser(true)
    store.setMode('public')
    expect(store.deidRequired).toBe(false)
  })

  it('refuses JPEG, PNG and anything else in the browser, before any request', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    const png = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])], 'p.png')
    const jpg = new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0])], 'p.jpg')
    const txt = new File(['hello'], 'n.txt')
    await store.addFiles([png, jpg, txt])
    expect(store.rows.map((r) => r.state)).toEqual(['error', 'error', 'error'])
    expect(store.rows.map((r) => r.error)).toEqual(['deid.format', 'deid.format', 'deid.format'])
    expect(api.commitFile).not.toHaveBeenCalled()
    expect(api.resolveRows).not.toHaveBeenCalled()
  })

  it('matches the header patient_id against the study labels locally and resolves by the LABEL', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    await store.addFiles([e2eForLabel(LABEL)])
    expect(api.listVisibleSubjectLabels).toHaveBeenCalledTimes(1)
    expect(store.rows).toHaveLength(1)
    const row = store.rows[0]!
    expect(row.state).toBe('suggested')
    expect(row.patientId).toBe(LABEL)
    expect(row.needsDeidConfirm).toBe(true)
    expect(row.previewUrl).toBe('blob:preview-1')
    expect(api.resolveRows).toHaveBeenCalledTimes(1)
    expect(api.resolveRows.mock.calls[0]![1]).toEqual([{ patientId: LABEL, scanDate: expect.any(String), laterality: 'OD' }])
    for (const s of SECRET_STRINGS) expect(everythingSent()).not.toContain(s)
  })

  it('does not match on the surname slot (the fallback is off)', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    // patient_id empty, surname = a real label: the legacy fallback would have matched it.
    await store.addFiles([asFile(e2eWith({ surname: LABEL, first: 'Max' }), 'x.e2e')])
    expect(store.rows[0]!.state).toBe('nopatient')
    expect(store.rows[0]!.patientId).toBe('')
    expect(api.resolveRows).not.toHaveBeenCalled()
  })

  it('an unmatched header value is never transmitted; the operator picks, and the label is what travels', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    await store.addFiles([asFile(e2eWith(PHI), 'Mustermann_Max.e2e')]) // pid MRN-778899 is no label here
    const row = store.rows[0]!
    expect(row.state).toBe('nopatient')
    expect(row.patientId).toBe('')
    expect(api.resolveRows).not.toHaveBeenCalled()
    // The header value is not held on the row either.
    expect(JSON.stringify(store.rows)).not.toContain('MRN-778899')
    expect(JSON.stringify(store.rows)).not.toContain('Mustermann')
    expect(JSON.stringify(store.rows)).not.toContain('Maximilian')
    // No image chunk in this file: no preview, which the row shows as a notice.
    expect(row.previewUrl).toBeNull()

    await store.assignFromSearch(row.rowId, { studySubjectId: 9, label: LABEL, studyId: 1, studyName: 'Study', siteName: null })
    store.setDeidConfirmed(row.rowId, true)
    await store.confirm(row.rowId)
    expect(api.commitFile).toHaveBeenCalledTimes(1)
    for (const s of SECRET_STRINGS) expect(everythingSent()).not.toContain(s)
    expect(everythingSent()).not.toContain('Mustermann_Max')
  })

  it('blocks the commit until the preview is ticked, then sends the stripped file', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    const original = e2eForLabel(LABEL)
    await store.addFiles([original])
    const row = store.rows[0]!

    await store.confirm(row.rowId)
    expect(api.commitFile).not.toHaveBeenCalled()
    await store.confirmAll()
    expect(api.commitFile).not.toHaveBeenCalled()

    store.setDeidConfirmed(row.rowId, true)
    await store.confirm(row.rowId)
    expect(api.commitFile).toHaveBeenCalledTimes(1)

    const [mode, req] = api.commitFile.mock.calls[0]! as [string, Record<string, unknown>]
    expect(mode).toBe('staff')
    const sent = req.file as File
    expect(sent.name).toBe(`${LABEL}_20240506_OD.e2e`)
    expect(req.patientId).toBe(LABEL)
    expect(req.deidConfirmed).toBe(true)
    const bytes = await fileBytes(sent)
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as BufferSource))
    expect(req.deidSha256).toBe(Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join(''))
    const text = new TextDecoder('latin1').decode(bytes)
    for (const s of SECRET_STRINGS) expect(text).not.toContain(s)
    expect(sent.size).toBe((await fileBytes(original)).length)
    expect(store.rows[0]!.state).toBe('committed')
  })

  it('confirmAll sends only the rows that were ticked', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    await store.addFiles([e2eForLabel(LABEL), asFile(e2eWith({ ...PHI, pid: 'HAE-001' }), 'b.e2e')])
    expect(store.rows).toHaveLength(2)
    store.setDeidConfirmed(store.rows[1]!.rowId, true)
    await store.confirmAll()
    expect(api.commitFile).toHaveBeenCalledTimes(1)
    expect((api.commitFile.mock.calls[0]![1] as { patientId: string }).patientId).toBe('HAE-001')
    expect(store.rows[0]!.state).toBe('suggested')
  })

  it('a batch tick confirms every waiting row', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    await store.addFiles([e2eForLabel(LABEL), asFile(e2eWith({ ...PHI, pid: 'HAE-001' }), 'b.e2e')])
    store.setAllDeidConfirmed(true)
    await store.confirmAll()
    expect(api.commitFile).toHaveBeenCalledTimes(2)
  })

  it('a refusal (residual identifier) uploads nothing and shows a code, not the value', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    const extra = new Uint8Array(60)
    extra.set(new TextEncoder().encode('Mustermann'), 4)
    await store.addFiles([asFile(e2eWith({ ...PHI, pid: LABEL }, [{ type: TYPE_ACQ_INFO, payload: extra }]), 'x.e2e')])
    const row = store.rows[0]!
    store.setDeidConfirmed(row.rowId, true)
    await store.confirm(row.rowId)
    expect(api.commitFile).not.toHaveBeenCalled()
    expect(store.rows[0]!.state).toBe('error')
    expect(store.rows[0]!.error).toBe('deid.residual')
  })

  it('a label that does not fit the E2E slot is refused, nothing sent', async () => {
    const long = 'H'.repeat(30)
    api.listVisibleSubjectLabels.mockResolvedValue([long])
    api.resolveRows.mockImplementation(async (_m: string, scans: Array<{ patientId: string }>) => ({
      scans: scans.map((s) => ({ patientId: s.patientId, state: 'suggested', candidates: [candidate(long)] })),
    }))
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    // patient_id slot is 25 bytes: a 25-char header value cannot even equal a 30-char label, so assign by hand.
    await store.addFiles([asFile(e2eWith(PHI), 'x.e2e')])
    const row = store.rows[0]!
    await store.assignFromSearch(row.rowId, { studySubjectId: 9, label: long, studyId: 1, studyName: 'S', siteName: null })
    store.setDeidConfirmed(row.rowId, true)
    await store.confirm(row.rowId)
    expect(api.commitFile).not.toHaveBeenCalled()
    expect(store.rows[0]!.error).toBe('deid.label')
  })

  it('DICOM: matched on PatientID, stripped, named <label>_<date>_<eye>.dcm', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    await store.addFiles([dicomForPatient(LABEL)])
    const row = store.rows[0]!
    expect(row.kind).toBe('dicom')
    expect(row.patientId).toBe(LABEL)
    expect(row.laterality).toBe('OS')
    store.setDeidConfirmed(row.rowId, true)
    await store.confirm(row.rowId)
    const req = api.commitFile.mock.calls[0]![1] as { file: File; patientId: string; deidSha256: string }
    expect(req.file.name).toBe(`${LABEL}_20240506_OS.dcm`)
    expect(req.patientId).toBe(LABEL)
    const text = new TextDecoder('latin1').decode(await fileBytes(req.file))
    for (const s of SECRET_STRINGS) expect(text).not.toContain(s)
    expect(everythingSent()).not.toContain('Maximilian_Mustermann')
  })

  it('DICOM in JPEG 2000: the preview comes from the Cornerstone decoder; a genuine failure gives the notice', async () => {
    const J2K = '1.2.840.10008.1.2.4.90'
    cs.cornerstonePreview.mockReset()
    cs.cornerstonePreview.mockResolvedValueOnce({ width: 2, height: 2, rgba: new Uint8ClampedArray(16) })
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    await store.addFiles([dicomForPatient(LABEL, {}, J2K)])
    expect(cs.cornerstonePreview).toHaveBeenCalledTimes(1)
    expect(store.rows[0]!.previewUrl).toBe('blob:preview-1')

    store.reset()
    store.setMode('staff')
    cs.cornerstonePreview.mockResolvedValueOnce(null)
    await store.addFiles([dicomForPatient(LABEL, {}, J2K)])
    expect(store.rows[0]!.previewUrl).toBeNull()
    expect(store.rows[0]!.state).not.toBe('error')
  })

  it('DICOM with BurnedInAnnotation=YES is refused and never offered for upload', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    await store.addFiles([dicomForPatient(LABEL, { '00280301': { vr: 'CS', Value: ['YES'] } })])
    expect(store.rows[0]!.state).toBe('error')
    expect(store.rows[0]!.error).toBe('deid.burnedIn')
  })

  it('DICOM with an OU/unknown eye is stopped at commit (the file name needs OD or OS)', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    await store.addFiles([dicomForPatient(LABEL)])
    const row = store.rows[0]!
    store.setRowLaterality(row.rowId, 'OU')
    store.setDeidConfirmed(row.rowId, true)
    await store.confirm(row.rowId)
    expect(api.commitFile).not.toHaveBeenCalled()
    expect(store.rows[0]!.error).toBe('deid.laterality')
  })

  it('a multi-volume E2E gets an ordinal in the upload name, one stripped copy for all volumes', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    const two = asFile(fixtureBytes('multi-scan-OD-OS.e2e'), 'multi.e2e')
    // The fixture's patient_id (TEST-002) is a label for this study.
    api.listVisibleSubjectLabels.mockResolvedValue(['TEST-002'])
    api.resolveRows.mockImplementation(async (_m: string, scans: Array<{ patientId: string }>) => ({
      scans: scans.map((s) => ({ patientId: s.patientId, state: 'suggested', candidates: [candidate('TEST-002')] })),
    }))
    await store.addFiles([two])
    expect(store.rows).toHaveLength(2)
    store.setAllDeidConfirmed(true)
    await store.confirmAll()
    const names = api.commitFile.mock.calls.map((c) => (c[1] as { file: File }).file.name).sort()
    expect(names).toEqual(['TEST-002_20240320_OD_1.e2e', 'TEST-002_20240320_OS_2.e2e'])
    const hashes = new Set(api.commitFile.mock.calls.map((c) => (c[1] as { deidSha256: string }).deidSha256))
    expect(hashes.size).toBe(1)
  })
})

describe('required mode off — unchanged behaviour', () => {
  it('sends the original file under its own name, no confirmation fields, preflight as before', async () => {
    setUser(false)
    api.sha256OfFile.mockResolvedValue('a'.repeat(64))
    api.preflight.mockResolvedValue({ exists: false, ingestItemId: null, jobId: null })
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    const f = asFile(fixtureBytes('single-scan.e2e'), 'single-scan.e2e')
    await store.addFiles([f])
    expect(api.listVisibleSubjectLabels).not.toHaveBeenCalled()
    expect(api.preflight).toHaveBeenCalled()
    const row = store.rows[0]!
    expect(row.needsDeidConfirm).toBeUndefined()
    api.resolveRows.mockResolvedValue({ scans: [{ patientId: 'TEST-001', state: 'suggested', candidates: [candidate('TEST-001')] }] })
    store.setBatchVisit({
      studyEventId: 42, eventCrfId: null, studySubjectId: 5, subjectLabel: 'TEST-001',
      eventLabel: 'V1', studyName: 'S', studyId: 1, dateStart: '2024-01-15',
    })
    await store.confirm(store.rows[0]!.rowId)
    const req = api.commitFile.mock.calls[0]![1] as { file: File; deidConfirmed?: boolean; patientId: string }
    expect(req.file).toBe(f)
    expect(req.file.name).toBe('single-scan.e2e')
    expect(req.deidConfirmed).toBeUndefined()
    expect(req.patientId).toBe('TEST-001')
  })
})
