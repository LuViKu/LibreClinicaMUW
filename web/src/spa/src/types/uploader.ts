/**
 * Uploaders and their tokens — wire types for the Uploaders page.
 *
 * An uploader is the program on an acquisition PC (the Export Watcher, the
 * Optomed Bridge) that brings device files into one location's inbox. It
 * identifies itself with a token that belongs to exactly one location, so
 * the place a file came from is never guessed. The token is shown once,
 * when it is issued or replaced, and is never returned again; the server
 * keeps only its hash and a short hint.
 *
 * Not to be confused with the heartbeat entries of the System Status page
 * (`Uploader` in `types/systemHealth.ts`), which describe what a running
 * program reports. These describe the identity that program is allowed to
 * use; the two meet through `instanceUid` and `lastHeartbeatAt`.
 *
 * Hand-typed here, in lockstep with the backend's `UploaderDto` and
 * `IssuedUploaderDto` (phase 2 of the locations). They join the generated
 * `types/api.ts` once it is regenerated against the backend.
 *
 *   GET  /pages/api/v1/uploaders                         → ManagedUploader[]
 *   POST /pages/api/v1/locations/{locationId}/uploaders  → 201 IssuedUploader
 *   PUT  /pages/api/v1/uploaders/{id}                    → ManagedUploader   (rename)
 *   POST /pages/api/v1/uploaders/{id}/replace            → IssuedUploader
 *   POST /pages/api/v1/uploaders/{id}/revoke             → ManagedUploader
 *
 * System administrators (every location) and Standort-Admins (their own):
 * 401 anonymous, 403 for someone who may manage nothing, and 404 for a
 * resource of another location, so that no one can probe what exists.
 */

/** A revoked uploader's token is refused; replacing the token re-activates it. */
export type UploaderStatus = 'active' | 'revoked'

/** One uploader of a location, as the list shows it. */
export interface ManagedUploader {
  id: number
  locationId: number
  locationCode: string
  locationName: string
  /** Unique per location (case-insensitive), at most 64 characters. */
  displayName: string
  /**
   * The first 8 characters of the token after `mwu_`, so that a person can
   * tell tokens apart. Never enough to use the token.
   */
  tokenHint: string | null
  status: UploaderStatus
  /** The username of whoever issued it; null when unknown. */
  createdBy: string | null
  /** ISO-8601, UTC. */
  createdAt: string
  /** The last request that carried the token; updated at most once a minute. */
  lastUsedAt: string | null
  revokedAt: string | null
  /** The program instance this token last reported as (heartbeat). */
  instanceUid: string | null
  /** When that instance last reported; null when it never has. */
  lastHeartbeatAt: string | null
}

/**
 * The answer to issuing or replacing a token: the only place a token is
 * ever returned. `token` is `mwu_` plus 43 base64url characters.
 */
export interface IssuedUploader {
  uploader: ManagedUploader
  token: string
}

/**
 * Body of the create (`POST …/uploaders`) and rename (`PUT /uploaders/{id}`)
 * calls. Required, at most 64 characters, unique within the location; a
 * refusal comes back as 400 with an error on the field `displayName`.
 */
export interface UploaderNameInput {
  displayName: string
}
