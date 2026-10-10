import { describe, expect, it } from 'vitest'

import { jobRoute, STARTABLE_TASKS } from '@/lib/retinalJobs'

describe('jobRoute — one address per retinal job', () => {
  it('is the subject and the job id when the job has a subject', () => {
    expect(jobRoute({ jobId: 9, subjectLabel: 'EIAMD150' })).toBe('/subjects/EIAMD150/jobs/9')
  })

  it('encodes the label', () => {
    expect(jobRoute({ jobId: 9, subjectLabel: 'A/B 1' })).toBe('/subjects/A%2FB%201/jobs/9')
  })

  it('falls back to the id address when the job has no visit', () => {
    expect(jobRoute({ jobId: 9 })).toBe('/retinal-jobs/9')
    expect(jobRoute({ jobId: 9, subjectLabel: null })).toBe('/retinal-jobs/9')
    expect(jobRoute({ jobId: 9, subjectLabel: '' })).toBe('/retinal-jobs/9')
  })

  it('offers the server list of tasks, without bm', () => {
    expect([...STARTABLE_TASKS]).toEqual(['fluid', 'ga', 'onl', 'pr', 'layers', 'sdretinanet'])
  })
})
