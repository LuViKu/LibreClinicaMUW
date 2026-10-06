/**
 * The clinician's LOCAL calendar date as ISO yyyy-MM-dd.
 *
 * {@code new Date().toISOString().slice(0, 10)} is the UTC date, which is
 * "yesterday" for a clinician in Vienna between 00:00 and 01:00/02:00 local
 * time — a treatment decision would be dated the day before it was made.
 */
export function localIsoDate(d: Date = new Date()): string {
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd}`
}
