/**
 * 2026-06-21 user-feedback round 4 — shared date formatter.
 *
 * <p>The SPA accumulated 10+ copies of a local {@code formatDate}
 * helper rendering ISO dates as {@code DD-MMM-YYYY} (e.g.
 * {@code 21-Jun-2026}). The deployed MUW workflow is German and the
 * institutional convention is {@code DD.MM.YYYY} (German short
 * date) — the local English helpers were drift that crept in over
 * the SPA's lifetime.
 *
 * <p>This module exposes the canonical formatters. Views should
 * import from here rather than re-declaring a local helper.
 */

/**
 * Format a calendar date in German short form ({@code DD.MM.YYYY}).
 *
 * @param iso  ISO-8601 date string ({@code YYYY-MM-DD}). Accepts
 *             full timestamps too; the time portion is discarded.
 * @returns    The formatted string, or the em-dash placeholder when
 *             the input is null/undefined/empty.
 */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  const datePart = iso.slice(0, 10)
  const [y, m, d] = datePart.split('-').map((s) => Number.parseInt(s, 10))
  if (!Number.isFinite(y) || !Number.isFinite(m) || !Number.isFinite(d)) return iso
  return `${String(d).padStart(2, '0')}.${String(m).padStart(2, '0')}.${y}`
}

/**
 * Format an ISO-8601 instant as a German date+time string
 * ({@code DD.MM.YYYY HH:MM}). Used by audit-log + change-history
 * displays.
 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const datePart = formatDate(iso)
  if (datePart === '—' || datePart === iso) return datePart
  const timePart = iso.slice(11, 16)
  if (!/^\d{2}:\d{2}$/.test(timePart)) return datePart
  return `${datePart} ${timePart}`
}
