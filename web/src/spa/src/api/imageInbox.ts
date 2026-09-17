/**
 * DR-025 — reconciliation-inbox client (authenticated).
 *
 * Backs the staff inbox where inbound fundus images (Optomed C-STORE + Remidio
 * upload) are bound to a subject/event/CRF or dismissed. Uses the session-cookie
 * `apiGet`/`apiPost` client (context-path aware), unlike the public upload
 * portal which is credentials:'omit'.
 */
import { apiGet, apiPost } from '@/api/client'

export interface ImageSuggestion {
  state: string
  studySubjectId: number
  subjectLabel: string
  studyId: number
  studyName: string
  studyEventId: number | null
  eventCrfId: number | null
}

export interface ImageInboxRow {
  id: number
  sourceKind: string
  patientId: string | null
  laterality: string | null
  studyDate: string | null
  modality: string | null
  originalFilename: string | null
  receivedAt: string | null
  /** Backend path WITHOUT the context prefix; prepend CONTEXT_PATH for an <img>. */
  previewUrl: string
  hasPreview: boolean
  suggestion: ImageSuggestion | null
}

export function listImageInbox(): Promise<{ images: ImageInboxRow[] }> {
  return apiGet<{ images: ImageInboxRow[] }>('/pages/api/v1/image-ingest/inbox')
}

export interface BindPayload {
  studySubjectId: number
  studyEventId?: number | null
  eventCrfId?: number | null
}

export function bindImage(id: number, payload: BindPayload): Promise<{ imageIngestId: number; status: string }> {
  return apiPost(`/pages/api/v1/image-ingest/${id}/bind`, payload)
}

export function dismissImage(id: number, reason?: string): Promise<{ imageIngestId: number; status: string }> {
  return apiPost(`/pages/api/v1/image-ingest/${id}/dismiss`, { reason: reason ?? null })
}
