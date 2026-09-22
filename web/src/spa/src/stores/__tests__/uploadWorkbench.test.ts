/**
 * DR-029 — the combined uploader's store: one row per thing to file, whatever
 * kind of file it came from, and one visit pick for all of them.
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  resolveRows: vi.fn(),
  preflight: vi.fn(),
  commitFile: vi.fn(),
  undoItem: vi.fn(),
  undoJob: vi.fn(),
  sha256OfFile: vi.fn(),
}))
vi.mock('@/api/uploadWorkbench', () => ({
  resolveRows: (...a: unknown[]) => api.resolveRows(...a),
  preflight: (...a: unknown[]) => api.preflight(...a),
  commitFile: (...a: unknown[]) => api.commitFile(...a),
  undoItem: (...a: unknown[]) => api.undoItem(...a),
  undoJob: (...a: unknown[]) => api.undoJob(...a),
  sha256OfFile: (...a: unknown[]) => api.sha256OfFile(...a),
}))

const hints = vi.hoisted(() => ({ readDicomHints: vi.fn() }))
vi.mock('@/lib/dicomHeader', () => ({
  readDicomHints: (...a: unknown[]) => hints.readDicomHints(...a),
}))

import { useUploadWorkbenchStore, type BatchVisit } from '@/stores/uploadWorkbench'

const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0, 0, 0, 0, 0])
function pngFile(name = 'fundus.png'): File {
  return new File([PNG_BYTES], name, { type: 'image/png' })
}
function dicomFile(name = 'export.dcm'): File {
  const b = new Uint8Array(512)
  b.set([0x44, 0x49, 0x43, 0x4d], 128)
  b[200] = name.length
  return new File([b], name, { type: '' })
}
function e2eFile(): File {
  const bytes = readFileSync(resolve(process.cwd(), 'src/lib/__tests__/fixtures/single-scan.e2e'))
  return new File([new Uint8Array(bytes)], 'single-scan.e2e', { type: 'application/octet-stream' })
}
function textFile(): File {
  return new File(['hello, not an image'], 'notes.txt', { type: 'text/plain' })
}

const VISIT: BatchVisit = {
  studyEventId: 42, eventCrfId: null, studySubjectId: 5, subjectLabel: 'HAE-001',
  eventLabel: 'Baseline', studyName: 'HealthAEye', studyId: 106, dateStart: '2026-09-18',
}

function suggested(label: string, studyEventId: number) {
  return {
    scans: [{
      patientId: label,
      state: 'suggested',
      candidates: [{
        studyId: 1, studyName: 'Study', studyOid: 'S_1', studySubjectId: 9, subjectLabel: label, siteName: null,
        matchingEvent: { studyEventId, eventCrfId: null, definitionLabel: 'V1', dateStart: '2026-09-18', matchPolicy: 'exact' },
      }],
    }],
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  for (const m of Object.values(api)) m.mockReset()
  hints.readDicomHints.mockReset()
  api.sha256OfFile.mockImplementation(async (f: File) => `hash-of-${f.name}`.padEnd(64, '0'))
  api.preflight.mockResolvedValue({ exists: false, ingestItemId: null, jobId: null })
  // The backend answers one result per row, positionally; an unknown label is
  // "nopatient", never a missing entry.
  api.resolveRows.mockImplementation(async (_mode: string, scans: Array<{ patientId: string }>) => ({
    scans: scans.map((s) => ({ patientId: s.patientId, candidates: [], state: 'nopatient' })),
  }))
  api.commitFile.mockImplementation(async (_mode: string, req: { file: File }) => ({
    ingestItemId: 100 + req.file.name.length, jobId: null, kind: 'image', format: 'png', status: 'UNBOUND',
  }))
  hints.readDicomHints.mockResolvedValue({
    laterality: 'OD', acquisitionDate: '2026-09-18', modality: 'OP', modelName: 'CLARUS 700', deviceKey: 'clarus', sopInstanceUid: '1.2.3',
  })
})

describe('uploadWorkbench store', () => {
  it('classifies dropped files by their bytes and reads what each says about itself', async () => {
    const store = useUploadWorkbenchStore()
    await store.addFiles([pngFile(), dicomFile(), e2eFile()])

    const kinds = store.rows.map((r) => r.kind)
    expect(kinds).toContain('image')
    expect(kinds).toContain('dicom')
    expect(kinds).toContain('e2e')

    const image = store.rows.find((r) => r.kind === 'image')!
    expect(image.state).toBe('nopatient') // a photo says nothing; no label was typed
    expect(image.laterality).toBeNull()

    const dicom = store.rows.find((r) => r.kind === 'dicom')!
    expect(dicom.laterality).toBe('OD')
    expect(dicom.date).toBe('2026-09-18')
    expect(dicom.hints?.deviceKey).toBe('clarus')

    const oct = store.rows.find((r) => r.kind === 'e2e')!
    expect(oct.scan).toBeDefined()
    expect(typeof oct.patientId).toBe('string')
    expect(['OD', 'OS']).toContain(oct.laterality)
  })

  it('shows an unsupported file refused rather than dropping it silently', async () => {
    const store = useUploadWorkbenchStore()
    await store.addFiles([textFile()])
    expect(store.rows).toHaveLength(1)
    expect(store.rows[0].state).toBe('error')
    expect(store.rows[0].error).toBe('unsupported')
    expect(api.commitFile).not.toHaveBeenCalled()
  })

  it('a batch visit files every reviewable row, and confirmAll sends each with what it knows', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('public')
    await store.addFiles([pngFile(), dicomFile()])
    store.setBatchVisit(VISIT)

    expect(store.rows.every((r) => r.state === 'suggested')).toBe(true)
    expect(store.rows.every((r) => r.selectedEvent?.studyEventId === 42)).toBe(true)
    const image = store.rows.find((r) => r.kind === 'image')!
    expect(image.patientId).toBe('HAE-001')
    store.setRowLaterality(image.rowId, 'OS')

    await store.confirmAll()

    expect(api.commitFile).toHaveBeenCalledTimes(2)
    const calls = api.commitFile.mock.calls.map((c) => c[1] as Record<string, unknown>)
    const imageCall = calls.find((c) => (c.file as File).name === 'fundus.png')!
    expect(imageCall).toMatchObject({ studyEventId: 42, laterality: 'OS', patientId: 'HAE-001', park: false })
    const dicomCall = calls.find((c) => (c.file as File).name === 'export.dcm')!
    expect(dicomCall).toMatchObject({ studyEventId: 42, laterality: 'OD', scanDate: '2026-09-18' })
    expect(api.commitFile.mock.calls[0][0]).toBe('public')
    expect(store.rows.every((r) => r.state === 'committed')).toBe(true)
  })

  it('an OCT scan under a batch visit keeps its header label and volume index', async () => {
    const store = useUploadWorkbenchStore()
    await store.addFiles([e2eFile()])
    const oct = store.rows[0]
    store.setBatchVisit({ ...VISIT, eventCrfId: 77 })
    await store.confirm(oct.rowId)
    const req = api.commitFile.mock.calls[0][1] as Record<string, unknown>
    // The open CRF wins over the planned visit, as on the OCT page.
    expect(req).toMatchObject({ eventCrfId: 77, studyEventId: null, scanIndex: oct.scan!.scanIndex, park: false })
    expect(req.patientId).toBe(oct.patientId || null)
  })

  it('a typed label resolves the rows that carry none', async () => {
    api.resolveRows.mockResolvedValue(suggested('M-001', 3))
    const store = useUploadWorkbenchStore()
    await store.addFiles([pngFile()])
    expect(store.rows[0].state).toBe('nopatient')

    store.setPatientIdHint('M-001')
    await store.applyPatientIdHint()

    expect(api.resolveRows).toHaveBeenCalledWith('public', [{ patientId: 'M-001', scanDate: null, laterality: null }])
    expect(store.rows[0].state).toBe('suggested')
    expect(store.rows[0].selectedEvent?.studyEventId).toBe(3)
  })

  it('a row can be sent without a visit — parked for OCT, straight to the inbox otherwise', async () => {
    const store = useUploadWorkbenchStore()
    await store.addFiles([pngFile(), e2eFile()])
    const image = store.rows.find((r) => r.kind === 'image')!
    const oct = store.rows.find((r) => r.kind === 'e2e')!

    await store.park(image.rowId)
    const imageReq = api.commitFile.mock.calls[0][1] as Record<string, unknown>
    expect(imageReq.studyEventId).toBeNull()
    expect(imageReq.park).toBe(false)

    if (oct.state === 'nopatient' || oct.state === 'novisit' || oct.state === 'ambiguous' || oct.state === 'suggested') {
      await store.park(oct.rowId)
      const octReq = api.commitFile.mock.calls[1][1] as Record<string, unknown>
      expect(octReq.park).toBe(true)
    }
  })

  it('a file the server already has is marked duplicate before a byte travels', async () => {
    api.preflight.mockResolvedValue({ exists: true, ingestItemId: 555, jobId: null })
    const store = useUploadWorkbenchStore()
    await store.addFiles([pngFile()])
    expect(store.rows[0].state).toBe('duplicate')
    expect(store.rows[0].existingIngestItemId).toBe(555)
    expect(api.resolveRows).not.toHaveBeenCalled()
  })

  it('a 409 on commit marks the row duplicate too', async () => {
    api.commitFile.mockRejectedValue(Object.assign(new Error('dup'), { status: 409, body: { existingIngestItemId: 7 } }))
    const store = useUploadWorkbenchStore()
    await store.addFiles([pngFile()])
    await store.park(store.rows[0].rowId)
    expect(store.rows[0].state).toBe('duplicate')
    expect(store.rows[0].existingIngestItemId).toBe(7)
  })

  it('undo goes by ingest item for images and by job for OCT scans', async () => {
    api.undoItem.mockResolvedValue(undefined)
    api.undoJob.mockResolvedValue(undefined)
    const store = useUploadWorkbenchStore()
    store.setMode('staff')
    await store.addFiles([pngFile()])
    await store.park(store.rows[0].rowId)
    expect(store.rows[0].state).toBe('committed')

    await store.undo(store.rows[0].rowId)
    expect(api.undoItem).toHaveBeenCalledWith('staff', store.rows[0].ingestItemId ?? expect.anything())
    expect(store.rows[0].state).toBe('nopatient')

    api.commitFile.mockResolvedValue({ ingestItemId: 9, jobId: 31, kind: 'e2e', format: 'e2e', status: 'queued' })
    await store.addFiles([e2eFile()])
    const oct = store.rows.find((r) => r.kind === 'e2e')!
    store.setBatchVisit(VISIT)
    await store.confirm(oct.rowId)
    await store.undo(oct.rowId)
    expect(api.undoJob).toHaveBeenCalledWith('staff', 31)
  })

  it('a re-dropped file is one row, not two', async () => {
    const store = useUploadWorkbenchStore()
    await store.addFiles([pngFile()])
    await store.addFiles([pngFile()])
    expect(store.rows).toHaveLength(1)
  })

  it('reset clears everything the page had', async () => {
    const store = useUploadWorkbenchStore()
    await store.addFiles([pngFile()])
    store.setBatchVisit(VISIT)
    store.reset()
    expect(store.rows).toHaveLength(0)
    expect(store.batchVisit).toBeNull()
  })
})
