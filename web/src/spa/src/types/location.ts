/**
 * Locations (Standorte) — wire types for the system administrator's
 * "System → Standorte" screen.
 *
 * A location is a global physical place: a clinic, a department or a
 * partner hospital. A study is enabled at a location by having a site
 * there (LibreClinica's study sites stay; each one points at exactly one
 * location). Not to be confused with the per-study Sites screen, whose
 * rows are `StudyIdentity` in `types/study.ts`.
 *
 * Hand-typed here, in lockstep with the backend's `LocationDto` /
 * `LocationDetailDto` (admin locations API, phase 1). They join the
 * generated `types/api.ts` once it is regenerated against the backend.
 *
 *   GET    /pages/api/v1/admin/locations                          → Location[]
 *   POST   /pages/api/v1/admin/locations                          → 201 Location
 *   GET    /pages/api/v1/admin/locations/{id}                     → LocationDetail
 *   PUT    /pages/api/v1/admin/locations/{id}                     → LocationDetail
 *   POST   /pages/api/v1/admin/locations/{id}/disable             → Location
 *   POST   /pages/api/v1/admin/locations/{id}/restore             → Location
 *   PUT    /pages/api/v1/admin/locations/{id}/users/{username}    → LocationDetail
 *          (optional body {@link LocationUserInput}, phase 2)
 *   DELETE /pages/api/v1/admin/locations/{id}/users/{username}    → LocationDetail
 *
 * System administrators only: 401 anonymous, 403 anyone else.
 */

/** A location is `removed` once disabled; it can be restored. */
export type LocationStatus = 'available' | 'removed'

/**
 * One location, with the counts the list shows. The counts are the
 * server's: `siteCount` and `studyCount` leave out removed studies,
 * `ingestItemCount` is every inbox file whose origin is this location,
 * `userCount` the explicit person assignments.
 */
export interface Location {
  id: number
  /** Unique (case-insensitive), `[A-Za-z0-9_-]{2,30}`, fixed once created. */
  code: string
  /** Unique (case-insensitive), at most 100 characters. */
  name: string
  status: LocationStatus
  street: string | null
  city: string | null
  zip: string | null
  country: string | null
  /** Studies of this location that are sites (they have a parent study). */
  siteCount: number
  /** Parent or standalone studies of this location. */
  studyCount: number
  ingestItemCount: number
  userCount: number
}

/** A study or study site enabled at a location. */
export interface LocationStudyRef {
  oid: string
  name: string
  /** True for a site: a sub-study with a parent. */
  isSite: boolean
  /** The parent study's OID for a site, else null. */
  parentOid: string | null
  /** The study status as the server names it: `available`, `pending`, … */
  status: string
}

/** A person explicitly assigned to a location (not derived from a study role). */
export interface LocationUser {
  username: string
  /** "First Last". */
  displayName: string
  /**
   * The Standort-Admin right (phase 2): the person may issue, replace and
   * revoke the uploader tokens of this location and see its uploaders.
   * A system administrator grants and withdraws it per person and location.
   */
  canManageSources: boolean
}

/**
 * Optional body of `PUT /admin/locations/{id}/users/{username}`. Absent or
 * `{}` on an existing assignment keeps the flag; a new assignment starts
 * with the right withdrawn.
 */
export interface LocationUserInput {
  canManageSources?: boolean
}

/** A location with the studies and people behind its counts. */
export interface LocationDetail extends Location {
  studies: LocationStudyRef[]
  /** Ordered by username. */
  users: LocationUser[]
}

/**
 * `POST /admin/locations` body. Strings are trimmed by the server; the
 * optional ones may be left out. Validation answers 400 with one entry
 * per field: `code`, `name`, `street`, `city`, `zip`, `country`.
 */
export interface CreateLocationInput {
  code: string
  name: string
  street?: string
  city?: string
  zip?: string
  country?: string
}

/**
 * `PUT /admin/locations/{id}` body: the code is fixed after creation
 * (the server ignores one if sent), so it is not part of the input. The
 * edit form always sends all four optional fields, a blank one as `""`,
 * so that clearing a field works whether the server reads an absent
 * field as "unchanged" or as "cleared".
 */
export type UpdateLocationInput = Omit<CreateLocationInput, 'code'>

/** One entry of the server's 400 body (`ValidationErrorBody`). */
export interface LocationFieldError {
  field: string
  message: string
}

/**
 * One location the caller may place a study or a site at, from
 * `GET /pages/api/v1/locations` (DR-040 phase 4), ordered by name. A system
 * administrator gets every available location, anybody else the available
 * ones in their own scope; never 403 (an empty list instead). Mirrors the
 * backend's `LocationOptionDto`.
 */
export interface LocationOption {
  id: number
  code: string
  name: string
  street: string | null
  city: string | null
  zip: string | null
  country: string | null
}
