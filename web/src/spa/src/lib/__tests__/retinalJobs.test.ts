import { describe, expect, it } from 'vitest'

import { jobRoute, STARTABLE_TASKS } from '@/lib/retinalJobs'

describe('jobRoute — one address per retinal job', () => {
  it('is the subject address when the job has a subject and a number', () => {
    expect(jobRoute({ jobId: 9, subjectLabel: 'EIAMD150', subjectSeq: 3 })).toBe('/subjects/EIAMD150/jobs/3')
  })

  it('encodes the label', () => {
    expect(jobRoute({ jobId: 9, subjectLabel: 'A/B 1', subjectSeq: 1 })).toBe('/subjects/A%2FB%201/jobs/1')
  })

  it('falls back to the id address when the job has no visit', () => {
    expect(jobRoute({ jobId: 9 })).toBe('/retinal-jobs/9')
    expect(jobRoute({ jobId: 9, subjectLabel: 'EIAMD150', subjectSeq: null })).toBe('/retinal-jobs/9')
    expect(jobRoute({ jobId: 9, subjectLabel: null, subjectSeq: 2 })).toBe('/retinal-jobs/9')
  })

  it('offers the server list of tasks, without bm', () => {
    expect([...STARTABLE_TASKS]).toEqual(['fluid', 'ga', 'onl', 'pr', 'layers', 'sdretinanet'])
  })
})
