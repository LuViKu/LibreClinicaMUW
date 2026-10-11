/**
 * Ingest sources — wire types for the "Quellen" section of System → Standorte.
 *
 * An ingest source is where device files come from: an uploader on an
 * acquisition PC, a DICOM sender (identified by its calling AE title), the
 * Remidio cloud account, or the upload page. Each has a location, or none
 * yet. A file from a source without a location is visible to system
 * administrators only, until one is assigned; assigning a location moves
 * the files that wait for it into that location's inbox at once.
 *
 * Hand-typed here, in lockstep with the backend's `IngestSourceDto` (phase 2
 * of the locations). It joins the generated `types/api.ts` once that is
 * regenerated against the backend.
 *
 *   GET /pages/api/v1/admin/ingest-sources                    → IngestSource[]
 *   PUT /pages/api/v1/admin/ingest-sources/{id}/location      → IngestSource
 *
 * System administrators only: 401 anonymous, 403 anyone else. Assigning a
 * location that is no longer available answers 409.
 */

/**
 * `uploader` has a token and is managed on the Uploaders page; `dicom_ae`
 * is a DICOM sender's calling AE title; `remidio` is the cloud account;
 * `portal` is reserved for the upload page. A kind the SPA does not know
 * (a newer server) is shown as the server named it.
 */
export type IngestSourceKind = 'uploader' | 'dicom_ae' | 'remidio' | 'portal'

export type IngestSourceStatus = 'active' | 'revoked'

export interface IngestSource {
  id: number
  kind: IngestSourceKind
  /** The AE title of a `dicom_ae`, the account of a `remidio`; null for an uploader. */
  sourceKey: string | null
  displayName: string
  /** Null while the source has no location. */
  locationId: number | null
  locationName: string | null
  /**
   * Files of this source that wait for a person to bind them to a subject
   * (the server counts the unbound ones with this source). For a source
   * without a location these are the files only administrators can see.
   */
  pendingItemCount: number
  /** ISO-8601, UTC; null when the source has not been seen yet. */
  lastSeenAt: string | null
  status: IngestSourceStatus
}

/** Body of `PUT /admin/ingest-sources/{id}/location`; `null` takes the location away. */
export interface AssignSourceLocationInput {
  locationId: number | null
}
