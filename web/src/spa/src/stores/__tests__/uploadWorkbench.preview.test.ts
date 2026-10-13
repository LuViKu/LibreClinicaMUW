/**
 * DR-041 amendment (b) — the browser preview of a split row and the excludeSeries wiring.
 * Real synthetic fixtures (testdata/e2e-synthetic), mocked API.
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

import { hasNoSeriesLeft, isConfirmable, useUploadWorkbenchStore } from '@/stores/uploadWorkbench'

const FIXTURES = resolve(process.cwd(), '../../../testdata/e2e-synthetic')
function fixture(name: string, asName = `${name}.E2E`): File {
  return new File([new Uint8Array(readFileSync(resolve(FIXTURES, `${name}.e2e`)))], asName)
}

const candidate = (label: string, eventLabel: string | null) => ({
  studyId: 1, studyName: 'Drusen', studyOid: 'S_1', studySubjectId: 9, subjectLabel: label, siteName: null,
  matchingEvent: eventLabel
    ? { studyEventId: 7, eventCrfId: null, definitionLabel: eventLabel, dateStart: '2026-09-30', matchPolicy: 'date' }
    : null,
})

beforeEach(() => {
  setActivePinia(createPinia())
  for (const m of Object.values(api)) m.mockReset()
  api.preflight.mockResolvedValue({ exists: false, ingestItemId: null, jobId: null })
  api.resolveRows.mockImplementation(async (_m: string, scans: Array<{ patientId: string }>) => ({
    scans: scans.map((s) => ({ patientId: s.patientId, state: 'novisit', candidates: [candidate(s.patientId, null)] })),
  }))
  api.commitFile.mockResolvedValue({
    sha256: 'x', headerPatient: 'matches', parked: false, kind: 'e2e', format: 'e2e', status: 'UNBOUND',
    ingestItemId: 0, jobId: null, items: [],
  })
})

async function rowFor(name: string, relativePath: string | null = `iAMD/SYNTH001/${name}.E2E`) {
  const store = useUploadWorkbenchStore()
  await store.addFiles([{ file: fixture(name), relativePath }])
  return { store, row: () => store.rows[0]! }
}

describe('preview: reading the file in the browser', () => {
  it('lists the series of the file on the row, from range reads', async () => {
    const f = fixture('both-eyes-mixed')
    const whole = vi.fn(async () => new ArrayBuffer(0))
    Object.defineProperty(f, 'arrayBuffer', { value: whole })
    const store = useUploadWorkbenchStore()
    await store.addFiles([{ file: f, relativePath: 'SYNTH005/both-eyes-mixed.E2E' }])
    const p = store.rows[0]!.preview!
    expect(p.status).toBe('ready')
    expect(p.series).toHaveLength(9)
    expect(p.headerLabel).toBe('SYNTH005')
    expect(whole).not.toHaveBeenCalled()
  })

  it.each([
    ['oct-volume-localizer', 'SYNTH001', 'matches'], // label SYNTH001 = folder
    ['oct-volume-localizer', 'SYNTH999', 'differs'], // folder differs: the server will park it
    ['enface-multi-image', 'SYNTH001', 'folder-only'], // surname is not a token
    ['oct-line-scan', null, 'none'], // no patient chunk, no folder
  ])('%s in folder %s: label check %s', async (name, folder, expected) => {
    const { row } = await rowFor(name, folder ? `iAMD/${folder}/${name}.E2E` : null)
    expect(row().preview?.labelCheck).toBe(expected)
  })

  it('a file without a folder takes the label from the file', async () => {
    const { row } = await rowFor('oct-volume-localizer', null)
    expect(row().preview?.labelCheck).toBe('file-only')
    expect(row().patientId).toBe('') // still committed without a patientId
  })

  it('a file that cannot be read keeps its row and is sent whole', async () => {
    const bytes = new Uint8Array(4096)
    bytes.set(Array.from('CMDb', (c) => c.charCodeAt(0)))
    const store = useUploadWorkbenchStore()
    await store.addFiles([{ file: new File([bytes], 'x.E2E'), relativePath: 'SYNTH001/x.E2E' }])
    expect(store.rows[0]!.preview?.status).toBe('failed')
    expect(isConfirmable(store.rows[0]!)).toBe(true)
    await store.confirm(store.rows[0]!.rowId)
    expect(api.commitFile.mock.calls[0]![1]).not.toHaveProperty('excludeSeries')
  })

  it('asks /resolve once per file for the visit of each series: subject plus that series date and eye', async () => {
    api.resolveRows.mockImplementation(async (_m: string, scans: Array<{ patientId: string; scanDate: string | null }>) => ({
      scans: scans.map((s) => ({
        patientId: s.patientId,
        state: s.scanDate === '2026-09-30' ? 'suggested' : 'novisit',
        candidates: [candidate(s.patientId, s.scanDate === '2026-09-30' ? 'Baseline' : null)],
      })),
    }))
    const { row } = await rowFor('both-eyes-mixed', 'iAMD/SYNTH005/both-eyes-mixed.E2E')
    const calls = api.resolveRows.mock.calls.map((c) => c[1] as Array<{ patientId: string; scanDate: string | null; laterality: string | null }>)
    const perSeries = calls.find((c) => c.length > 1)!
    expect(perSeries.every((r) => r.patientId === 'SYNTH005')).toBe(true)
    expect(perSeries.map((r) => r.scanDate)).toContain('2026-10-01')
    expect(new Set(perSeries.map((r) => r.laterality))).toEqual(new Set(['OD', 'OS', null]))
    const visits = row().preview!.visits
    const dated = row().preview!.series.find((s) => s.acquiredAt?.startsWith('2026-09-30'))!
    expect(visits[dated.seriesKey]).toMatchObject({ visitLabel: 'Baseline', visitDate: '2026-09-30' })
    const undated = row().preview!.series.find((s) => s.acquiredAt === null)!
    expect(visits[undated.seriesKey]!.visitLabel).toBeNull()
  })

  it('does not ask for visit suggestions when the label differs from the folder', async () => {
    await rowFor('oct-volume-localizer', 'iAMD/SYNTH999/x.E2E')
    expect(api.resolveRows.mock.calls.every((c) => (c[1] as unknown[]).length === 1)).toBe(true)
  })
})

describe('excludeSeries wiring', () => {
  it('sends the unticked series keys, comma-joined on the wire by the API, in file order', async () => {
    const { store, row } = await rowFor('both-eyes-mixed', 'SYNTH005/both-eyes-mixed.E2E')
    const keys = row().preview!.series.map((s) => s.seriesKey)
    store.setSeriesIncluded(row().rowId, [keys[4]!, keys[1]!], false)
    await store.confirm(row().rowId)
    const req = api.commitFile.mock.calls[0]![1] as Record<string, unknown>
    expect(req.excludeSeries).toEqual([keys[1], keys[4]])
    expect(req).toMatchObject({ split: true, device: 'spectralis', patientId: 'SYNTH005' })
  })

  it('sends no excludeSeries when everything stays ticked', async () => {
    const { store, row } = await rowFor('oct-volume-localizer')
    await store.confirm(row().rowId)
    expect(api.commitFile.mock.calls[0]![1]).not.toHaveProperty('excludeSeries')
  })

  it('ticking a series again takes it back out of the list', async () => {
    const { store, row } = await rowFor('both-eyes-mixed', 'SYNTH005/both-eyes-mixed.E2E')
    const k = row().preview!.series[0]!.seriesKey
    store.setSeriesIncluded(row().rowId, [k], false)
    store.setSeriesIncluded(row().rowId, [k], true)
    await store.confirm(row().rowId)
    expect(api.commitFile.mock.calls[0]![1]).not.toHaveProperty('excludeSeries')
  })

  it('all unticked: the row cannot be confirmed, parked or sent in a batch', async () => {
    const { store, row } = await rowFor('enface-multi-image', 'SYNTH001/enface-multi-image.E2E')
    expect(isConfirmable(row())).toBe(true)
    store.setSeriesIncluded(row().rowId, row().preview!.series.map((s) => s.seriesKey), false)
    expect(hasNoSeriesLeft(row())).toBe(true)
    expect(isConfirmable(row())).toBe(false)
    await store.confirm(row().rowId)
    await store.park(row().rowId)
    await store.confirmAll()
    expect(api.commitFile).not.toHaveBeenCalled()
    store.setSeriesIncluded(row().rowId, [row().preview!.series[0]!.seriesKey], true)
    expect(isConfirmable(row())).toBe(true)
  })

  it('after the upload the server list of excluded series is kept (keys or objects)', async () => {
    const { store, row } = await rowFor('both-eyes-mixed', 'SYNTH005/both-eyes-mixed.E2E')
    const keys = row().preview!.series.map((s) => s.seriesKey)
    store.setSeriesIncluded(row().rowId, [keys[0]!], false)
    api.commitFile.mockResolvedValue({
      sha256: 'x', headerPatient: 'matches', parked: false, kind: 'e2e', format: 'e2e', status: 'UNBOUND',
      ingestItemId: 0, jobId: null, items: [], excluded: [{ seriesKey: keys[0]! }],
    })
    await store.confirm(row().rowId)
    expect(row().state).toBe('committed')
    expect(row().excludedKeys).toEqual([keys[0]])
    store.setSeriesIncluded(row().rowId, [keys[0]!], true) // locked after the upload
    expect(row().excludedKeys).toEqual([keys[0]])
  })

  it('the batch confirm skips a row with nothing selected and sends the others', async () => {
    const store = useUploadWorkbenchStore()
    await store.addFiles([
      { file: fixture('enface-multi-image'), relativePath: 'SYNTH001/a.E2E' },
      { file: fixture('time-sequence'), relativePath: 'SYNTH004/b.E2E' },
    ])
    store.setSeriesIncluded(store.rows[0]!.rowId, store.rows[0]!.preview!.series.map((s) => s.seriesKey), false)
    await store.confirmAll()
    expect(api.commitFile).toHaveBeenCalledTimes(1)
    expect((api.commitFile.mock.calls[0]![1] as { file: File }).file).toBe(store.rows[1]!.file)
  })
})
