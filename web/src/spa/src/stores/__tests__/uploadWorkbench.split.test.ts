/**
 * DR-041 — the uploader's split mode: one row per E2E file, the subject from
 * the folder, a chunked hash, one commit with split=true, and the series the
 * server made of it. (The de-identifying path is covered by
 * uploadWorkbench.deid.test.ts and is untouched.)
 */
import { createHash } from 'node:crypto'
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

import { MAX_SPLIT_BYTES, useUploadWorkbenchStore } from '@/stores/uploadWorkbench'

const E2E_BYTES = (() => {
  const b = new Uint8Array(4096)
  b.set(Array.from('CMDb', (c) => c.charCodeAt(0)))
  return b
})()

/** Starts with an E2E header magic, so the sniffer names it; the rest is filler. */
function e2e(name = 'EIAMD01D.E2E'): File {
  return new File([E2E_BYTES], name)
}

function found(label: string) {
  return {
    patientId: label,
    state: 'novisit',
    candidates: [{
      studyId: 1, studyName: 'Drusen', studyOid: 'S_1', studySubjectId: 9, subjectLabel: label, siteName: null,
      matchingEvent: null,
    }],
  }
}

const ITEMS = [
  { ingestItemId: 11, seriesKey: 's1', seriesModality: 'OCT', laterality: 'OD', acquisitionDate: '2026-09-30', frames: 97, imagingModalityId: null, status: 'UNBOUND' },
  { ingestItemId: 12, seriesKey: 's2', seriesModality: 'ICGA', laterality: 'OS', acquisitionDate: '2026-09-30', frames: 40, imagingModalityId: null, status: 'UNBOUND' },
  { ingestItemId: 13, seriesKey: 's3', seriesModality: 'QAF', laterality: null, acquisitionDate: null, frames: 9, imagingModalityId: null, status: 'UNBOUND' },
]

function resolveAsFound(): void {
  api.resolveRows.mockImplementation(async (_m: string, scans: Array<{ patientId: string }>) => ({
    scans: scans.map((s) => found(s.patientId)),
  }))
}

beforeEach(() => {
  setActivePinia(createPinia())
  for (const m of Object.values(api)) m.mockReset()
  api.preflight.mockResolvedValue({ exists: false, ingestItemId: null, jobId: null })
  api.resolveRows.mockImplementation(async (_m: string, scans: Array<{ patientId: string }>) => ({
    scans: scans.map((s) => ({ patientId: s.patientId, candidates: [], state: 'nopatient' })),
  }))
  api.commitFile.mockResolvedValue({
    sha256: 'x', headerPatient: 'empty', parked: true, kind: 'e2e', format: 'e2e', status: 'UNBOUND', ingestItemId: 0, jobId: null,
    items: ITEMS,
  })
})

describe('split mode: selecting and classifying', () => {
  it('makes one row per E2E file, labelled by the folder that directly contains it', async () => {
    resolveAsFound()
    const store = useUploadWorkbenchStore()
    const b = new File([E2E_BYTES, new Uint8Array([7])], 'b.E2E') // different bytes: not a re-drop
    await store.addFiles([
      { file: e2e('a.E2E'), relativePath: 'iAMD/EIAMD005/a.E2E' },
      { file: b, relativePath: 'iAMD/EIAMD006/b.E2E' },
    ])
    expect(store.rows.map((r) => [r.split, r.patientId, r.file.name])).toEqual([
      [true, 'EIAMD005', 'a.E2E'], [true, 'EIAMD006', 'b.E2E'],
    ])
  })

  it('resolves by the folder label alone, without a date or an eye, and is ready to send', async () => {
    resolveAsFound()
    const store = useUploadWorkbenchStore()
    await store.addFiles([{ file: e2e('a.E2E'), relativePath: 'iAMD/EIAMD005/a.E2E' }])
    expect(store.rows.map((r) => [r.split, r.patientId])).toEqual([[true, 'EIAMD005']])
    expect(api.resolveRows).toHaveBeenCalledWith('public', [{ patientId: 'EIAMD005', scanDate: null, laterality: null }])
    // Subject known, no visit: the server resolves each series, so the row is ready.
    expect(store.rows[0].state).toBe('suggested')
    expect(store.rows[0].selectedEvent).toBeNull()
  })

  it('takes the folder from webkitRelativePath of a plain File too; a file without one is sent without a patientId', async () => {
    const inFolder = e2e('a.E2E')
    Object.defineProperty(inFolder, 'webkitRelativePath', { value: 'EIAMD005/a.E2E' })
    const loose = new File([E2E_BYTES, new Uint8Array([1])], 'loose.E2E')
    const store = useUploadWorkbenchStore()
    await store.addFiles([inFolder, loose])
    expect(store.rows.map((r) => r.patientId)).toEqual(['EIAMD005', ''])
    // No folder: not stuck. The server reads the label from the file, or parks it.
    expect(store.rows[1].state).toBe('suggested')
    expect(store.rows[1].state).not.toBe('nopatient')
    expect(store.rows[1].selectedCandidate).toBeUndefined()
    expect(api.resolveRows).toHaveBeenCalledWith('public', [{ patientId: 'EIAMD005', scanDate: null, laterality: null }])
  })

  it('a typed patient label does not turn into the folder of a split row', async () => {
    const store = useUploadWorkbenchStore()
    await store.addFiles([e2e('loose.E2E')])
    store.setPatientIdHint('HAE-001')
    await store.applyPatientIdHint()
    expect(store.rows[0].patientId).toBe('')
    expect(store.rows[0].state).toBe('suggested')
  })

  it('reads only the first bytes of the file to classify it', async () => {
    const f = e2e()
    const whole = vi.fn(async () => new ArrayBuffer(0))
    Object.defineProperty(f, 'arrayBuffer', { value: whole })
    const store = useUploadWorkbenchStore()
    await store.addFiles([{ file: f, relativePath: 'EIAMD005/x.E2E' }])
    expect(whole).not.toHaveBeenCalled()
    expect(store.rows[0].kind).toBe('e2e')
  })

  it('hashes in slices (not through the whole-file hash) and sends the digest to a split preflight', async () => {
    const expected = createHash('sha256').update(E2E_BYTES).digest('hex')
    const store = useUploadWorkbenchStore()
    await store.addFiles([{ file: e2e(), relativePath: 'EIAMD005/x.E2E' }])
    expect(api.sha256OfFile).not.toHaveBeenCalled()
    expect(api.preflight).toHaveBeenCalledWith('public', expected, null, { split: true })
    expect(store.rows[0].fileHash).toBe(expected)
  })

  it('marks a file the server already split as duplicate, before it travels', async () => {
    api.preflight.mockResolvedValue({ exists: true, ingestItemId: null, jobId: null, existingIngestItemIds: [11, 12] })
    const store = useUploadWorkbenchStore()
    await store.addFiles([{ file: e2e(), relativePath: 'EIAMD005/x.E2E' }])
    expect(store.rows[0].state).toBe('duplicate')
    expect(store.rows[0].existingIngestItemId).toBe(11)
    expect(api.resolveRows).not.toHaveBeenCalled()
    expect(api.commitFile).not.toHaveBeenCalled()
  })

  it('refuses a file over 2 GiB before hashing or uploading', async () => {
    const f = e2e('huge.E2E')
    Object.defineProperty(f, 'size', { value: MAX_SPLIT_BYTES + 1 })
    const store = useUploadWorkbenchStore()
    await store.addFiles([{ file: f, relativePath: 'EIAMD005/huge.E2E' }])
    expect(store.rows).toHaveLength(1)
    expect(store.rows[0].state).toBe('error')
    expect(store.rows[0].error).toBe('tooLarge')
    expect(api.preflight).not.toHaveBeenCalled()
    expect(api.commitFile).not.toHaveBeenCalled()
  })
})

describe('split mode: commit, series and undo', () => {
  async function readyRow() {
    resolveAsFound()
    const store = useUploadWorkbenchStore()
    await store.addFiles([{ file: e2e(), relativePath: 'iAMD/EIAMD005/EIAMD01D.E2E' }])
    return store
  }

  it('sends split=true with the folder label and device, and no scan date, eye, volume or park', async () => {
    const store = await readyRow()
    await store.confirm(store.rows[0].rowId)
    const req = api.commitFile.mock.calls[0][1] as Record<string, unknown>
    expect(req).toEqual({
      file: store.rows[0].file, patientId: 'EIAMD005', split: true, device: 'spectralis', studyEventId: null, park: false,
    })
  })

  it('names the batch visit as studyEventId', async () => {
    const store = await readyRow()
    store.setBatchVisit({
      studyEventId: 42, eventCrfId: 5, studySubjectId: 9, subjectLabel: 'EIAMD005', eventLabel: 'Baseline',
      studyName: 'Drusen', studyId: 1, dateStart: '2026-09-30',
    })
    await store.confirmAll()
    expect(api.commitFile.mock.calls[0][1]).toMatchObject({ split: true, studyEventId: 42, patientId: 'EIAMD005' })
    expect(api.commitFile.mock.calls[0][1]).not.toHaveProperty('eventCrfId')
  })

  it('keeps the series the server made, and the header comparison', async () => {
    api.commitFile.mockResolvedValue({
      headerPatient: 'differs', parked: true, kind: 'e2e', format: 'e2e', status: 'UNBOUND', ingestItemId: 0, jobId: null, items: ITEMS,
    })
    const store = await readyRow()
    await store.confirm(store.rows[0].rowId)
    const row = store.rows[0]
    expect(row.state).toBe('committed')
    expect(row.items?.map((i) => i.seriesModality)).toEqual(['OCT', 'ICGA', 'QAF'])
    expect(row.headerPatient).toBe('differs')
  })

  it('a 409 answers duplicate with the earlier items', async () => {
    api.commitFile.mockRejectedValue(Object.assign(new Error('dup'), { status: 409, body: { duplicate: true, existingIngestItemIds: [5, 6] } }))
    const store = await readyRow()
    await store.confirm(store.rows[0].rowId)
    expect(store.rows[0].state).toBe('duplicate')
    expect(store.rows[0].existingIngestItemId).toBe(5)
  })

  it('undo deletes every item of the file, one request each', async () => {
    const store = await readyRow()
    await store.confirm(store.rows[0].rowId)
    await store.undo(store.rows[0].rowId)
    expect(api.undoItem.mock.calls.map((c) => c[1])).toEqual([11, 12, 13])
    expect(api.undoJob).not.toHaveBeenCalled()
    expect(store.rows[0].state).toBe('suggested')
    expect(store.rows[0].items).toBeUndefined()
  })

  it('an undo that fails for one item keeps that item listed and the row committed', async () => {
    api.undoItem.mockImplementation(async (_m: string, id: number) => {
      if (id === 12) throw new Error('window over')
    })
    const store = await readyRow()
    await store.confirm(store.rows[0].rowId)
    await store.undo(store.rows[0].rowId)
    expect(store.rows[0].state).toBe('committed')
    expect(store.rows[0].items?.map((i) => i.ingestItemId)).toEqual([12])
    expect(store.rows[0].error).toBe('window over')
  })

  it('commits a file without a folder with no patientId and shows the subject the server matched', async () => {
    api.commitFile.mockResolvedValue({
      sha256: 'x', headerPatient: 'none', subjectLabel: 'EIAMD005', parked: false, kind: 'e2e', format: 'e2e',
      status: 'BOUND', ingestItemId: 0, jobId: null, items: ITEMS,
    })
    const store = useUploadWorkbenchStore()
    await store.addFiles([e2e('loose.E2E')])
    expect(store.rows[0].state).toBe('suggested')
    await store.confirm(store.rows[0].rowId)
    const req = api.commitFile.mock.calls[0][1] as Record<string, unknown>
    expect(req).toEqual({
      file: store.rows[0].file, patientId: null, split: true, device: 'spectralis', studyEventId: null, park: false,
    })
    expect(store.rows[0].state).toBe('committed')
    expect(store.rows[0].subjectLabel).toBe('EIAMD005')
    expect(store.rows[0].headerPatient).toBe('none')
  })

  it('a file the server could not match to a subject is committed with subjectLabel null', async () => {
    api.commitFile.mockResolvedValue({
      sha256: 'x', headerPatient: 'none', subjectLabel: null, parked: true, kind: 'e2e', format: 'e2e',
      status: 'UNBOUND', ingestItemId: 0, jobId: null, items: ITEMS,
    })
    const store = useUploadWorkbenchStore()
    await store.addFiles([e2e('loose.E2E')])
    await store.confirm(store.rows[0].rowId)
    expect(store.rows[0].subjectLabel).toBeNull()
    expect(store.rows[0].parked).toBe(true)
  })

  it('undo of a file without a folder puts it back ready to send, not stuck', async () => {
    const store = useUploadWorkbenchStore()
    await store.addFiles([e2e('loose.E2E')])
    await store.confirm(store.rows[0].rowId)
    await store.undo(store.rows[0].rowId)
    expect(store.rows[0].state).toBe('suggested')
    expect(store.rows[0].subjectLabel).toBeUndefined()
  })

  it('a loose file is parked on request, not bound', async () => {
    const store = useUploadWorkbenchStore()
    await store.addFiles([e2e('loose.E2E')])
    await store.park(store.rows[0].rowId)
    expect(api.commitFile.mock.calls[0][1]).toMatchObject({ split: true, park: true, studyEventId: null, patientId: null })
  })
})
