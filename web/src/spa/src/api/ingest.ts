/**
 * P3.2 — client for the unified ingest inbox.
 *
 * Replaces `api/imageInbox.ts`. The platform had two queues for one activity —
 * a file arrives from a device, somebody says whose visit it belongs to — with
 * an OCT volume going to the retinal "parked" list and a fundus photo to the
 * image inbox. This talks to the single queue at `/api/v1/ingest`.
 *
 * Session-cookie `apiGet`/`apiPost` (context-path aware), unlike the public
 * upload portals which are `credentials: 'omit'`.
 */
import { ApiError, apiGet, apiPost } from '@/api/client'

/** What a file IS, as opposed to how it arrived. */
export type IngestKind = 'e2e' | 'dicom' | 'image' | 'other'

export type IngestStatus = 'UNBOUND' | 'BOUND' | 'DISMISSED'

/** The one-click hint beside an unreconciled file. */
export interface IngestSuggestion {
  state: string
  studySubjectId: number
  subjectLabel: string
  studyId: number
  studyName: string
  studyEventId: number | null
  eventCrfId: number | null
}

export interface IngestItem {
  id: number
  kind: IngestKind
  sourceKind: string
  device: string | null
  patientId: string | null
  laterality: string | null
  acquisitionDate: string | null
  /**
   * Where `acquisitionDate` came from. Only `'file'` is evidence of when the
   * scan was taken — `'operator'` is the day whoever uploaded it typed into
   * the workbench, which is also the key the visit list was searched by, so it
   * agrees with the chosen visit whatever the file contains. `'unknown'` is a
   * row written before the distinction existed. Null when there is no date.
   */
  acquisitionDateSource: 'file' | 'device' | 'operator' | 'unknown' | null
  modality: string | null
  originalFilename: string | null
  byteSize: number | null
  scanIndex: number | null
  receivedAt: string | null
  /** Backend path WITHOUT the context prefix; prepend CONTEXT_PATH for an <img>. */
  previewUrl: string
  hasPreview: boolean
  suggestion: IngestSuggestion | null
}

export interface InboxFilters {
  kind?: IngestKind | null
  source?: string | null
  device?: string | null
  q?: string | null
  candidateStudySubjectId?: number | null
  status?: IngestStatus | null
  limit?: number | null
}

function query(filters: InboxFilters): string {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(filters)) {
    if (v !== null && v !== undefined && v !== '') p.set(k, String(v))
  }
  const s = p.toString()
  return s ? `?${s}` : ''
}

export function listIngestInbox(
  filters: InboxFilters = {},
): Promise<{ items: IngestItem[]; limit: number; status: IngestStatus }> {
  return apiGet(`/pages/api/v1/ingest/inbox${query(filters)}`)
}

/** Per-kind counts, so the filter chips can be labelled without listing rows. */
export function ingestInboxCounts(): Promise<{
  unbound: number
  byKind: Record<string, number>
}> {
  return apiGet('/pages/api/v1/ingest/inbox/counts')
}

/**
 * The images filed against one visit — every BOUND ingest item whose
 * binding names this study event. What the visit page shows under
 * "Bilder dieser Visite" (2026-09-24). Same row shape as the inbox, so the
 * preview URL and the eye/device/date columns need no second mapping.
 */
export interface VisitImages {
  items: IngestItem[]
  /**
   * Unbound files in the inbox that carry this visit's subject label — what
   * "remove from visit: wrong visit" leaves behind, or a capture the resolver
   * could not place. Shown on the visit page so nothing is out of sight.
   */
  pendingForSubject: number
}

export function listIngestByEvent(studyEventId: number | string): Promise<VisitImages> {
  return apiGet<{ items: IngestItem[]; pendingForSubject?: number }>(
    `/pages/api/v1/ingest/by-event/${encodeURIComponent(String(studyEventId))}`,
  ).then((r) => ({ items: r.items ?? [], pendingForSubject: r.pendingForSubject ?? 0 }))
}

export function getIngestItem(id: number): Promise<IngestItem> {
  return apiGet(`/pages/api/v1/ingest/${id}`)
}

export interface BindPayload {
  studySubjectId: number
  studyEventId?: number | null
  eventCrfId?: number | null
  modalityCode?: string | null
  laterality?: string | null
  /**
   * Proceed even though the file's own acquisition date disagrees with the
   * visit's date. Without it the backend answers 409 `date_mismatch`; the
   * caller shows the two dates and re-sends with this set if the operator
   * confirms. Only ever set from an explicit confirmation.
   */
  acknowledgeDateMismatch?: boolean
}

/** The 409 body a bind comes back with when the two dates disagree. */
export interface DateMismatch {
  code: 'date_mismatch'
  ingestItemId: number
  fileDate: string
  visitDate: string
  message: string
}

/**
 * True when this rejection is the date question rather than a real failure.
 *
 * The backend refuses a mismatched bind once so it cannot happen by accident;
 * it is not an error state, and the caller is expected to ask and retry.
 */
export function isDateMismatch(e: unknown): e is ApiError & { body: DateMismatch } {
  if (!(e instanceof ApiError)) return false
  const body = e.body as { code?: string } | null
  return body?.code === 'date_mismatch'
}

export function bindIngestItem(
  id: number,
  payload: BindPayload,
): Promise<{ ingestItemId: number; status: string }> {
  return apiPost(`/pages/api/v1/ingest/${id}/bind`, payload)
}

/**
 * Bind several files to one visit.
 *
 * Each is applied independently, so one row somebody already dealt with does
 * not discard the rest of the selection — `skipped` says which and why.
 */
export function bulkBindIngestItems(
  ids: number[],
  payload: BindPayload,
): Promise<{
  bound: number[]
  skipped: Array<{ id: number; reason: string; fileDate?: string; visitDate?: string }>
}> {
  return apiPost('/pages/api/v1/ingest/bulk-bind', { ids, ...payload })
}

/**
 * Return a bound file to the inbox.
 *
 * This also undoes what the bind caused: the visit's "modality performed" tick
 * goes with it, unless another file from the same device still evidences it.
 */
/**
 * Take a file off its visit. Plain: back to the inbox (wrong visit). With
 * `dismiss`: unbind and dismiss in one request (not study data), with the
 * reason — so the file never sits in the inbox unreviewed between two clicks.
 * The backend refuses (409, `reason: 'VISIT_SEALED'`) when the visit is
 * signed or locked.
 */
export function unbindIngestItem(
  id: number,
  opts?: { dismiss?: boolean; reason?: string },
): Promise<{ ingestItemId: number; status: string }> {
  return apiPost(`/pages/api/v1/ingest/${id}/unbind`, {
    dismiss: opts?.dismiss === true,
    reason: opts?.reason ?? null,
  })
}

/** Bring a dismissed file back into the inbox while the retention window is open. */
export function restoreIngestItem(id: number): Promise<{ ingestItemId: number; status: string }> {
  return apiPost(`/pages/api/v1/ingest/${id}/restore`, {})
}

/** Dismiss several unbound files at once; the response says which were refused. */
export function bulkDismissIngestItems(
  ids: number[],
  reason?: string,
): Promise<{ dismissed: number[]; skipped: Array<{ id: number; reason: string }> }> {
  return apiPost('/pages/api/v1/ingest/bulk-dismiss', { ids, reason: reason ?? null })
}

export function dismissIngestItem(
  id: number,
  reason?: string,
): Promise<{ ingestItemId: number; status: string }> {
  return apiPost(`/pages/api/v1/ingest/${id}/dismiss`, { reason: reason ?? null })
}
