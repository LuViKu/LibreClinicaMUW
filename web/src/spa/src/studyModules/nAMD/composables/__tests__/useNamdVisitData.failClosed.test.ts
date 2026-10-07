/**
 * 2026-10 — useNamdVisitData fails closed: every input the recommendation
 * needs is null when it is missing or its fetch failed, and the resulting
 * recommendation is an explicit insufficient-data result — never EXTEND.
 */
import { computed, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  listSubjectJobs: vi.fn(),
  listSubjectBcvaTimeline: vi.fn(),
  listSubjectCrtTimeline: vi.fn(),
  listSubjectNamdClinicalFlags: vi.fn(),
  getJob: vi.fn(),
}))
vi.mock('@/api/retinal', () => api)
vi.mock('@/api/client', () => ({ apiGet: vi.fn(async () => ({ groupAssignments: [] })) }))

import { mm3ToNl, useNamdVisitData } from '../useNamdVisitData'

const RING = { irf: 0, srf: 0, ped: 0, total: 0 }

function summary(jobId: number, studyEventId: number, date: string, modelVersion: string | null = 'retinsight-v1.2') {
  return {
    jobId, task: 'fluid', laterality: 'OD', status: 'done', modelVersion,
    completedAt: date, visitDate: date, acquisitionDate: date, studyEventId,
  }
}

/** A fully dry, fully known fluid job detail. */
function detail(jobId: number, over: Record<string, unknown> = {}) {
  return {
    jobId, eventCrfId: 100 + jobId, task: 'fluid', laterality: 'OD', status: 'done',
    modelVersion: 'retinsight-v1.2', subjectArm: 'AI_SHOWN',
    outputPayload: {
      biomarkers: { irf_mm3: 0, srf_mm3: 0, ped_mm3: 0, total_mm3: 0 },
      etdrs_mm3: { central_1mm: RING, central_3mm: RING, central_6mm: RING },
    },
    ...over,
  }
}

const bcvaRow = (studyEventId: number) => ({
  studyEventId, eventDate: null,
  od: { letters: 75, decimal: null, partial: null },
  os: { letters: null, decimal: null, partial: null },
})
const flagsRow = (studyEventId: number) => ({
  studyEventId, eventDate: null,
  od: { hemorrhage: false, bcvaLossAttributedToNamd: false },
  os: { hemorrhage: null, bcvaLossAttributedToNamd: null },
})

/** Two dry visits with everything known — the baseline that reaches EXTEND. */
function happyPath() {
  api.listSubjectJobs.mockResolvedValue([summary(1, 11, '2026-01-01'), summary(2, 12, '2026-02-01')])
  api.listSubjectBcvaTimeline.mockResolvedValue([bcvaRow(11), bcvaRow(12)])
  api.listSubjectCrtTimeline.mockResolvedValue([])
  api.listSubjectNamdClinicalFlags.mockResolvedValue([flagsRow(11), flagsRow(12)])
  api.getJob.mockImplementation(async (id: number) => detail(id))
}

async function load() {
  const r = useNamdVisitData({
    studySubjectOid: computed(() => '42'),
    mock: computed(() => false),
  })
  await vi.waitFor(() => expect(r.loading.value).toBe(false))
  await r.refresh()
  await nextTick()
  await nextTick()
  return r
}

beforeEach(() => {
  Object.values(api).forEach((m) => m.mockReset())
  happyPath()
})

describe('mm3ToNl', () => {
  it('keeps unknown as null — it used to become 0 ("no fluid")', () => {
    expect(mm3ToNl(null)).toBeNull()
    expect(mm3ToNl(undefined)).toBeNull()
    expect(mm3ToNl(Number.NaN)).toBeNull()
  })
  it('still converts real values, including a real zero', () => {
    expect(mm3ToNl(0)).toBe(0)
    expect(mm3ToNl(1)).toBe(1000)
  })
})

describe('useNamdVisitData — baseline', () => {
  it('fully known dry data reaches a real recommendation (sanity for the cases below)', async () => {
    const r = await load()
    expect(r.data.value?.ai?.rec).toBe('EXTEND')
    expect(r.data.value?.current?.hemorrhage).toBe(false)
    expect(r.data.value?.current?.bcva).toBe(75)
  })
})

describe('useNamdVisitData — fail-closed', () => {
  it('failed job-detail fetch → visit biomarkers null → insufficient data', async () => {
    api.getJob.mockImplementation(async (id: number) => {
      if (id === 2) throw new Error('boom')
      return detail(id)
    })
    const r = await load()
    const cur = r.data.value!.current!
    expect(cur.irf).toBeNull()
    expect(cur.srf).toBeNull()
    expect(cur.ped).toBeNull()
    expect(cur.fluidByRegion).toBeNull()
    expect(cur.fetchFailures).toContain('jobDetail')
    const ai = r.data.value!.ai!
    expect(ai.rec).toBeNull()
    expect(ai.reason).toBe('INSUFFICIENT_DATA')
    expect(ai.missing).toContain('current.irf')
    expect(ai.fetchFailures).toContain('jobDetail')
  })

  it('missing BCVA (no timeline row) → bcva null, not 0 → insufficient data', async () => {
    api.listSubjectBcvaTimeline.mockResolvedValue([bcvaRow(11)]) // none for the current visit
    const r = await load()
    expect(r.data.value!.current!.bcva).toBeNull()
    const ai = r.data.value!.ai!
    expect(ai.rec).toBeNull()
    expect(ai.missing).toEqual(['current.bcva'])
  })

  it('BCVA fetch failure → insufficient data, flagged as a failure', async () => {
    api.listSubjectBcvaTimeline.mockRejectedValue(new Error('500'))
    const r = await load()
    expect(r.data.value!.current!.bcva).toBeNull()
    const ai = r.data.value!.ai!
    expect(ai.rec).toBeNull()
    expect(ai.fetchFailures).toContain('bcva')
  })

  it('clinical-flag fetch failure → flags null (NOT false) → insufficient data', async () => {
    api.listSubjectNamdClinicalFlags.mockRejectedValue(new Error('404'))
    const r = await load()
    const cur = r.data.value!.current!
    expect(cur.hemorrhage).toBeNull()
    expect(cur.bcvaAttributableToNamd).toBeNull()
    expect(cur.fetchFailures).toContain('clinicalFlags')
    const ai = r.data.value!.ai!
    expect(ai.rec).toBeNull()
    expect(ai.reason).toBe('INSUFFICIENT_DATA')
    expect(ai.missing).toEqual(expect.arrayContaining(['current.hemorrhage', 'current.bcvaAttributableToNamd']))
    expect(ai.fetchFailures).toContain('clinicalFlags')
  })

  it('flags never recorded (no row) → null, distinct from a recorded false', async () => {
    api.listSubjectNamdClinicalFlags.mockResolvedValue([flagsRow(11)]) // none for the current visit
    const r = await load()
    expect(r.data.value!.prev!.hemorrhage).toBe(false) // recorded false
    expect(r.data.value!.current!.hemorrhage).toBeNull() // not recorded
    expect(r.data.value!.ai!.rec).toBeNull()
  })

  it('a flag the backend reports as null stays null (the other flag can still be recorded)', async () => {
    api.listSubjectNamdClinicalFlags.mockResolvedValue([
      flagsRow(11),
      { ...flagsRow(12), od: { hemorrhage: true, bcvaLossAttributedToNamd: null } },
    ])
    const r = await load()
    expect(r.data.value!.current!.hemorrhage).toBe(true)
    expect(r.data.value!.current!.bcvaAttributableToNamd).toBeNull()
  })

  it('payload without the per-ring breakdown → fluidByRegion null → no EXTEND', async () => {
    api.getJob.mockImplementation(async (id: number) => {
      const d = detail(id)
      delete (d.outputPayload as Record<string, unknown>).etdrs_mm3
      return d
    })
    const r = await load()
    expect(r.data.value!.current!.fluidByRegion).toBeNull()
    expect(r.data.value!.ai!.rec).toBeNull()
    expect(r.data.value!.ai!.missing).toContain('current.fluidByRegion')
  })

  it('placeholder model version reaches the visit and blocks the recommendation', async () => {
    api.listSubjectJobs.mockResolvedValue([
      summary(1, 11, '2026-01-01', 'placeholder-v1'),
      summary(2, 12, '2026-02-01', 'placeholder-v1'),
    ])
    api.getJob.mockImplementation(async (id: number) => detail(id, { modelVersion: 'placeholder-v1' }))
    const r = await load()
    expect(r.data.value!.current!.modelVersion).toBe('placeholder-v1')
    const ai = r.data.value!.ai!
    expect(ai.rec).toBeNull()
    expect(ai.placeholderModel).toBe(true)
  })
})
