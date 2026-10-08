/**
 * 2026-10-09 — where a retinal job lives in the SPA, and which tasks an
 * operator may start.
 *
 * A job has one canonical address, `/subjects/<label>/jobs/<seq>`: the
 * subject's own numbering, resolved by `GET /subjects/{label}/retinal-jobs/{seq}`.
 * The visit is deliberately not in the URL — a scan can be filed to another
 * visit (DR-035: jobs follow the file), and a scan filed nowhere has no visit.
 * `/retinal-jobs/<id>` stays for old bookmarks and for a job the caller knows
 * only by id; the job page replaces it with the canonical address once it
 * has loaded the job.
 */

/** What a link to a job needs; `subjectLabel` / `subjectSeq` absent when the job has no visit. */
export interface JobRef {
  jobId: number
  subjectLabel?: string | null
  subjectSeq?: number | null
}

/** The one place a job's address is built. */
export function jobRoute(job: JobRef): string {
  if (job.subjectLabel && job.subjectSeq != null && job.subjectSeq > 0) {
    return `/subjects/${encodeURIComponent(job.subjectLabel)}/jobs/${job.subjectSeq}`
  }
  return `/retinal-jobs/${job.jobId}`
}

/**
 * The tasks rerun-as and "Auswertung starten" offer. Mirrors
 * `RetinalJobFollower.STARTABLE_TASKS` on the server. `bm` is absent on
 * purpose: `layers` returns the full surface stack, BM included.
 */
export const STARTABLE_TASKS = ['fluid', 'ga', 'onl', 'pr', 'layers', 'sdretinanet'] as const
export type StartableTask = (typeof STARTABLE_TASKS)[number]
