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
import { apiGet, apiPost } from '@/api/client'

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

export function getIngestItem(id: number): Promise<IngestItem> {
  return apiGet(`/pages/api/v1/ingest/${id}`)
}

export interface BindPayload {
  studySubjectId: number
  studyEventId?: number | null
  eventCrfId?: number | null
  modalityCode?: string | null
  laterality?: string | null
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
): Promise<{ bound: number[]; skipped: Array<{ id: number; reason: string }> }> {
  return apiPost('/pages/api/v1/ingest/bulk-bind', { ids, ...payload })
}

/**
 * Return a bound file to the inbox.
 *
 * This also undoes what the bind caused: the visit's "modality performed" tick
 * goes with it, unless another file from the same device still evidences it.
 */
export function unbindIngestItem(id: number): Promise<{ ingestItemId: number; status: string }> {
  return apiPost(`/pages/api/v1/ingest/${id}/unbind`, {})
}

export function dismissIngestItem(
  id: number,
  reason?: string,
): Promise<{ ingestItemId: number; status: string }> {
  return apiPost(`/pages/api/v1/ingest/${id}/dismiss`, { reason: reason ?? null })
}
