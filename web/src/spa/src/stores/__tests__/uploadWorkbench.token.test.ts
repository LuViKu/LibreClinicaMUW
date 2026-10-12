/**
 * The combined uploader's store when the server refuses the uploader token.
 *
 * A refused token is not the file's fault. The store must therefore keep the
 * files exactly as they were, stop the attempt where it is without a retry of
 * its own, and send them once more only when a person brings a new link.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'

import { stubWebStorage } from '@/test/memoryStorage'

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
import {
  UploadTokenError,
  clearUploaderToken,
  reportTokenProblem,
  setUploaderToken,
  tokenProblem,
} from '@/lib/uploaderToken'

const TOKEN = 'mwu_' + 'abcXYZ019-_'.repeat(5).slice(0, 43)

const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0, 0, 0, 0, 0])
const png = (name: string) => new File([PNG_BYTES], name, { type: 'image/png' })

const VISIT: BatchVisit = {
  studyEventId: 42, eventCrfId: null, studySubjectId: 5, subjectLabel: 'HAE-001',
  eventLabel: 'Baseline', studyName: 'HealthAEye', studyId: 106, dateStart: '2026-09-18',
}

function suggested(label: string) {
  return {
    scans: [{
      patientId: label,
      state: 'suggested',
      candidates: [{
        studyId: 1, studyName: 'Study', studyOid: 'S_1', studySubjectId: 9, subjectLabel: label, siteName: null,
        matchingEvent: { studyEventId: 7, eventCrfId: null, definitionLabel: 'V1', dateStart: '2026-09-18', matchPolicy: 'exact' },
      }],
    }],
  }
}

const required = () => new UploadTokenError('required', { message: 'uploader token required' })
const rejected = () => new UploadTokenError('rejected', { message: 'uploader token rejected' })

beforeEach(() => {
  setActivePinia(createPinia())
  stubWebStorage()
  clearUploaderToken()
  reportTokenProblem(null)
  for (const m of Object.values(api)) m.mockReset()
  hints.readDicomHints.mockReset()
  api.sha256OfFile.mockImplementation(async (f: File) => `hash-of-${f.name}`.padEnd(64, '0'))
  api.preflight.mockResolvedValue({ exists: false, ingestItemId: null, jobId: null })
  api.resolveRows.mockImplementation(async (_m: string, scans: Array<{ patientId: string }>) => ({
    scans: scans.map((s) => ({ patientId: s.patientId, candidates: [], state: 'nopatient' })),
  }))
  api.commitFile.mockResolvedValue({ ingestItemId: 100, jobId: null, kind: 'image', format: 'png', status: 'UNBOUND' })
})

afterEach(() => {
  // The store watches the token for the life of its pinia; a test's store must not outlive the test.
  useUploadWorkbenchStore().$dispose()
  vi.unstubAllGlobals()
})

/** The row as it is now: the store replaces a row on every change, so a held reference goes stale. */
const rowOf = (store: ReturnType<typeof useUploadWorkbenchStore>, rowId: string) =>
  store.rows.find((r) => r.rowId === rowId)!

describe('when the token is refused at the upload', () => {
  async function readyRows(...names: string[]) {
    const store = useUploadWorkbenchStore()
    store.setMode('public')
    await store.addFiles(names.map(png))
    store.setBatchVisit(VISIT)
    return store
  }

  it('puts the row back as it was, ready to be sent again, and not as a failure', async () => {
    const store = await readyRows('a.png')
    const { rowId } = store.rows[0]
    expect(rowOf(store, rowId).state).toBe('suggested')
    api.commitFile.mockRejectedValueOnce(rejected())

    await store.confirm(rowId)

    expect(rowOf(store, rowId).state).toBe('suggested')
    expect(rowOf(store, rowId).error).toBeUndefined()
    expect(store.counts.error).toBe(0)
    expect(store.uploadPct.size).toBe(0)
    expect(api.commitFile).toHaveBeenCalledTimes(1)
  })

  it('sends it again when the person presses the button again, and only then', async () => {
    const store = await readyRows('a.png')
    const { rowId } = store.rows[0]
    api.commitFile.mockRejectedValueOnce(required())
    await store.confirm(rowId)
    expect(api.commitFile).toHaveBeenCalledTimes(1)
    await nextTick()
    await new Promise((r) => setTimeout(r, 20))
    expect(api.commitFile).toHaveBeenCalledTimes(1)
    expect(rowOf(store, rowId).state).toBe('suggested')

    await store.confirm(rowId)
    expect(api.commitFile).toHaveBeenCalledTimes(2)
    expect(rowOf(store, rowId).state).toBe('committed')
  })

  it('stops a batch at the first refusal and leaves every file where it was', async () => {
    const store = await readyRows('a.png', 'b.png', 'c.png')
    api.commitFile.mockRejectedValue(rejected())

    await store.confirmAll()

    expect(api.commitFile).toHaveBeenCalledTimes(1)
    expect(store.rows.map((r) => r.state)).toEqual(['suggested', 'suggested', 'suggested'])
    expect(store.rows.every((r) => r.error === undefined)).toBe(true)
  })

  it('does not stop a batch for an ordinary failure of one file', async () => {
    const store = await readyRows('a.png', 'b.png')
    api.commitFile.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }))

    await store.confirmAll()

    expect(api.commitFile).toHaveBeenCalledTimes(2)
    expect(store.rows.map((r) => r.state)).toEqual(['error', 'committed'])
  })

  it('keeps a row sent without a visit, too', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('public')
    await store.addFiles([png('a.png')])
    const { rowId } = store.rows[0]
    expect(rowOf(store, rowId).state).toBe('nopatient')
    api.commitFile.mockRejectedValueOnce(required())

    await store.park(rowId)

    expect(rowOf(store, rowId).state).toBe('nopatient')
    expect(store.counts.error).toBe(0)
  })

  it('tells an undo that was refused in the row, and keeps the upload done', async () => {
    const store = await readyRows('a.png')
    const { rowId } = store.rows[0]
    await store.confirm(rowId)
    expect(rowOf(store, rowId).state).toBe('committed')
    api.undoItem.mockRejectedValueOnce(rejected())

    await store.undo(rowId)

    expect(rowOf(store, rowId).state).toBe('committed')
    expect(rowOf(store, rowId).error).toBe(rejected().message)
  })
})

describe('when the token is refused at the lookup', () => {
  it('keeps the files and marks them as waiting, with no retry of its own', async () => {
    api.resolveRows.mockRejectedValue(required())
    const store = useUploadWorkbenchStore()
    store.setMode('public')
    store.setPatientIdHint('HAE-001')
    await store.addFiles([png('a.png'), png('b.png')])
    await store.applyPatientIdHint()

    const waiting = store.rows.filter((r) => r.waitingForToken)
    expect(waiting.length).toBe(2)
    expect(waiting.every((r) => r.state === 'parsing')).toBe(true)
    expect(store.counts.error).toBe(0)
    const calls = api.resolveRows.mock.calls.length
    await nextTick()
    await new Promise((r) => setTimeout(r, 20))
    expect(api.resolveRows.mock.calls.length).toBe(calls)
  })

  it('does not fail the other kinds of refusal into waiting', async () => {
    api.resolveRows.mockRejectedValue(new Error('Server Error'))
    const store = useUploadWorkbenchStore()
    store.setMode('public')
    store.setPatientIdHint('HAE-001')
    await store.addFiles([png('a.png')])
    await store.applyPatientIdHint()
    expect(store.rows[0].state).toBe('error')
    expect(store.rows[0].waitingForToken).toBeUndefined()
  })

  it('asks the duplicate check no more once the token is refused', async () => {
    api.preflight.mockRejectedValue(required())
    const store = useUploadWorkbenchStore()
    store.setMode('public')
    await store.addFiles([png('a.png'), png('b.png'), png('c.png')])
    expect(api.preflight).toHaveBeenCalledTimes(1)
    expect(store.rows).toHaveLength(3)
    expect(store.counts.error).toBe(0)
  })

  it('resolves the waiting rows once when a new token arrives, and settles them', async () => {
    api.resolveRows.mockRejectedValueOnce(required())
    const store = useUploadWorkbenchStore()
    store.setMode('public')
    store.setPatientIdHint('HAE-001')
    await store.addFiles([png('a.png')])
    await store.applyPatientIdHint()
    expect(store.rows[0].waitingForToken).toBe(true)
    expect(api.resolveRows).toHaveBeenCalledTimes(1)

    api.resolveRows.mockResolvedValueOnce(suggested('HAE-001'))
    setUploaderToken(TOKEN)
    await vi.waitFor(() => expect(store.rows[0].state).toBe('suggested'))

    expect(api.resolveRows).toHaveBeenCalledTimes(2)
    expect(store.rows[0].waitingForToken).toBeUndefined()
  })

  it('leaves them waiting, and asks only once more, when the new token is refused too', async () => {
    api.resolveRows.mockRejectedValue(required())
    const store = useUploadWorkbenchStore()
    store.setMode('public')
    store.setPatientIdHint('HAE-001')
    await store.addFiles([png('a.png')])
    await store.applyPatientIdHint()
    expect(api.resolveRows).toHaveBeenCalledTimes(1)

    setUploaderToken(TOKEN)
    await vi.waitFor(() => expect(api.resolveRows).toHaveBeenCalledTimes(2))
    await new Promise((r) => setTimeout(r, 20))

    expect(api.resolveRows).toHaveBeenCalledTimes(2)
    expect(store.rows[0].waitingForToken).toBe(true)
    expect(store.rows[0].state).toBe('parsing')
  })

  it('does not touch rows that were not waiting when a token arrives', async () => {
    const store = useUploadWorkbenchStore()
    store.setMode('public')
    await store.addFiles([png('a.png')])
    const calls = api.resolveRows.mock.calls.length
    setUploaderToken(TOKEN)
    await nextTick()
    await new Promise((r) => setTimeout(r, 20))
    expect(api.resolveRows.mock.calls.length).toBe(calls)
    expect(store.rows[0].state).toBe('nopatient')
  })
})

describe('the problem the page shows', () => {
  it('is not held in the store, which is serialised by devtools and reporters', () => {
    const store = useUploadWorkbenchStore()
    reportTokenProblem('required')
    setUploaderToken(TOKEN)
    expect(JSON.stringify(store.$state)).not.toContain('required')
    expect(JSON.stringify(store.$state)).not.toContain(TOKEN)
    expect(tokenProblem.value).toBeNull() // a token settles the complaint about its absence
  })
})
