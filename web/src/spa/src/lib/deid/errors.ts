/**
 * Browser de-identification (layer 1) — the one error type of the pipeline.
 *
 * A file that cannot be proven clean is not uploaded; the reason is a
 * {@code code} the UI maps to a localised sentence. The error NEVER carries an
 * identifying value (a name, an ID, a date of birth): it ends up on screen and,
 * for an unexpected failure, in the client error report.
 */
export type DeidErrorCode =
  /** Not a format the internet-facing deployment accepts (JPEG, PNG, anything but .e2e/DICOM). */
  | 'format'
  /** The study label cannot be written into the file (too long / not printable ASCII / empty). */
  | 'label'
  /** E2E: no patient-data chunk could be located, so its removal cannot be shown. */
  | 'e2eNoPatientChunk'
  /** E2E: a patient-data chunk is cut off before its last field. */
  | 'e2eTruncated'
  /** The file could not be parsed as the format it claims to be. */
  | 'parse'
  /** DICOM: BurnedInAnnotation=YES — identity is in the pixels. */
  | 'burnedIn'
  /** An original identifier is still present somewhere in the stripped bytes. */
  | 'residual'
  /** The stripped output failed its own re-check (tags, or pixel data changed). */
  | 'verify'
  /** The eye is not OD or OS, so the upload file name cannot be formed. */
  | 'laterality'

export class DeidError extends Error {
  readonly code: DeidErrorCode

  constructor(code: DeidErrorCode, detail = '') {
    // The detail is for developers and tests: it never contains a patient value.
    super(detail ? `deid:${code}:${detail}` : `deid:${code}`)
    this.name = 'DeidError'
    this.code = code
  }
}

export function isDeidError(e: unknown): e is DeidError {
  return e instanceof DeidError
}
