import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'

import type { RetinalJobSummary } from '@/api/retinal'

const listSubjectJobsMock = vi.fn<(id: number) => Promise<RetinalJobSummary[]>>()
const streamSegmentationMock = vi.fn()

vi.mock('@/api/retinal', () => ({
  listSubjectJobs: (id: number) => listSubjectJobsMock(id),
  /* unused by the composable but the import surface must match */
  streamSegmentation: () => streamSegmentationMock(),
}))

vi.mock('@/composables/useSegmentationEnvelope', () => ({
  useSegmentationEnvelope: () => ({ envelope: ref(null), reload: vi.fn(), status: ref('idle') }),
  clearSegmentationEnvelopeCache: vi.fn(),
}))

import {
  clearSiblingLayersJobCache,
  useSiblingLayersJob,
} from '@/studyModules/nAMD/composables/useSiblingLayersJob'

function summary(o: Partial<RetinalJobSummary> & { jobId: number; task: string; status?: string }): RetinalJobSummary {
  return {
    jobId: o.jobId,
    eventCrfId: 0,
    task: o.task as RetinalJobSummary['task'],
    laterality: (o.laterality ?? 'OD') as RetinalJobSummary['laterality'],
    status: (o.status ?? 'done') as RetinalJobSummary['status'],
    studyEventId: o.studyEventId ?? null,
    visitDate: o.visitDate ?? null,
    acquisitionDate: o.acquisitionDate ?? null,
    completedAt: o.completedAt ?? null,
    enqueuedAt: o.enqueuedAt ?? null,
    modelVersion: o.modelVersion ?? null,
    e2eUuid: o.e2eUuid ?? null,
    primaryMetric: o.primaryMetric ?? null,
    subjectArm: o.subjectArm ?? null,
  } as RetinalJobSummary
}

describe('useSiblingLayersJob', () => {
  beforeEach(() => {
    listSubjectJobsMock.mockReset()
    clearSiblingLayersJobCache(42)
  })
  afterEach(() => {
    clearSiblingLayersJobCache(42)
  })

  it('prefers a `layers` sibling over a `bm` sibling when both exist', async () => {
    listSubjectJobsMock.mockResolvedValueOnce([
      summary({ jobId: 10, task: 'fluid', studyEventId: 100, laterality: 'OD' }),
      summary({ jobId: 11, task: 'bm', studyEventId: 100, laterality: 'OD' }),
      summary({ jobId: 12, task: 'layers', studyEventId: 100, laterality: 'OD' }),
    ])
    const { siblingJobId, siblingTask } = useSiblingLayersJob({
      studySubjectId: ref(42),
      currentJobId: ref(10),
    })
    await vi.waitFor(() => expect(siblingJobId.value).toBe(12))
    expect(siblingTask.value).toBe('layers')
  })

  it('falls back to `bm` when no `layers` sibling exists', async () => {
    listSubjectJobsMock.mockResolvedValueOnce([
      summary({ jobId: 10, task: 'fluid', studyEventId: 100, laterality: 'OS' }),
      summary({ jobId: 11, task: 'bm', studyEventId: 100, laterality: 'OS' }),
    ])
    const { siblingJobId, siblingTask } = useSiblingLayersJob({
      studySubjectId: ref(42),
      currentJobId: ref(10),
    })
    await vi.waitFor(() => expect(siblingJobId.value).toBe(11))
    expect(siblingTask.value).toBe('bm')
  })

  it('requires laterality match against the current job', async () => {
    listSubjectJobsMock.mockResolvedValueOnce([
      summary({ jobId: 10, task: 'fluid', studyEventId: 100, laterality: 'OD' }),
      summary({ jobId: 11, task: 'layers', studyEventId: 100, laterality: 'OS' }),
    ])
    const { siblingJobId } = useSiblingLayersJob({
      studySubjectId: ref(42),
      currentJobId: ref(10),
    })
    // Allow the resolver microtask to run before asserting null.
    await new Promise<void>((r) => setTimeout(r, 0))
    await nextTick()
    expect(siblingJobId.value).toBeNull()
  })

  it('returns null when the current job is not in the summary list', async () => {
    listSubjectJobsMock.mockResolvedValueOnce([
      summary({ jobId: 11, task: 'layers', studyEventId: 100, laterality: 'OD' }),
    ])
    const { siblingJobId } = useSiblingLayersJob({
      studySubjectId: ref(42),
      currentJobId: ref(999),
    })
    await new Promise<void>((r) => setTimeout(r, 0))
    await nextTick()
    expect(siblingJobId.value).toBeNull()
  })

  it('skips non-`done` siblings', async () => {
    listSubjectJobsMock.mockResolvedValueOnce([
      summary({ jobId: 10, task: 'fluid', studyEventId: 100, laterality: 'OD' }),
      summary({ jobId: 11, task: 'layers', studyEventId: 100, laterality: 'OD', status: 'failed' }),
    ])
    const { siblingJobId } = useSiblingLayersJob({
      studySubjectId: ref(42),
      currentJobId: ref(10),
    })
    await new Promise<void>((r) => setTimeout(r, 0))
    await nextTick()
    expect(siblingJobId.value).toBeNull()
  })

  it('clears state when inputs go null', async () => {
    listSubjectJobsMock.mockResolvedValue([
      summary({ jobId: 10, task: 'fluid', studyEventId: 100, laterality: 'OD' }),
      summary({ jobId: 12, task: 'layers', studyEventId: 100, laterality: 'OD' }),
    ])
    const subj = ref<number | null>(42)
    const cur = ref<number | null>(10)
    const { siblingJobId } = useSiblingLayersJob({ studySubjectId: subj, currentJobId: cur })
    await vi.waitFor(() => expect(siblingJobId.value).toBe(12))
    subj.value = null
    await nextTick()
    expect(siblingJobId.value).toBeNull()
  })
})
