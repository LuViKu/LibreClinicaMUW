/**
 * DR-033 — sizes and ages as the System Status page shows them.
 *
 * Sizes use 1024-based steps with the labels Windows Explorer and `df -h`
 * readers expect (KB, MB, GB, TB): the people reading this page compare it
 * with exactly those tools on the acquisition PCs and the VM.
 *
 * Ages come from the server as seconds ("last heartbeat 95 s ago"), never
 * as the difference between two clocks, and are phrased by the browser's
 * own Intl.RelativeTimeFormat in the UI language.
 */

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'] as const

function number(v: number, locale: string, digits: number): string {
  return new Intl.NumberFormat(locale, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(v)
}

/**
 * `1536` → `1,5 KB` (de) / `1.5 KB` (en); null → em-dash. The next unit
 * starts at 1000, not 1024, so a size never needs four digits (`1,0 GB`,
 * not `1008 MB`) and a table column stays narrow.
 */
export function formatBytes(n: number | null | undefined, locale = 'de'): string {
  if (n == null || !Number.isFinite(n) || n < 0) return '—'
  if (n < 1000) return `${number(n, locale, 0)} B`
  let v = n
  let i = 0
  while (v >= 1000 && i < UNITS.length - 1) {
    v /= 1024
    i++
  }
  return `${number(v, locale, v < 10 ? 1 : 0)} ${UNITS[i]}`
}

/** A change in size: `+1,2 GB`, `−300 MB` (a real minus sign), `±0 B`. */
export function formatBytesDelta(delta: number | null | undefined, locale = 'de'): string {
  if (delta == null || !Number.isFinite(delta)) return '—'
  if (delta === 0) return '±0 B'
  const sign = delta > 0 ? '+' : '−'
  return `${sign}${formatBytes(Math.abs(delta), locale)}`
}

/** `95` → `vor 2 Minuten` / `2 minutes ago`; 0 → `jetzt` / `now`. */
export function formatAgo(seconds: number | null | undefined, locale = 'de'): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return '—'
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' })
  if (seconds < 60) return rtf.format(-Math.round(seconds), 'second')
  if (seconds < 3600) return rtf.format(-Math.round(seconds / 60), 'minute')
  if (seconds < 86400) return rtf.format(-Math.round(seconds / 3600), 'hour')
  return rtf.format(-Math.round(seconds / 86400), 'day')
}

/** Seconds since an ISO instant, measured against `now` (ms). Null-safe. */
export function secondsSince(iso: string | null | undefined, now = Date.now()): number | null {
  if (!iso) return null
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return null
  return Math.max(0, Math.round((now - t) / 1000))
}

/** An ISO instant in the browser's zone: `24.09.2026, 10:15`. */
export function formatInstant(iso: string | null | undefined, locale = 'de'): string {
  if (!iso) return '—'
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return '—'
  return new Intl.DateTimeFormat(locale, { dateStyle: 'short', timeStyle: 'short' }).format(t)
}

/** `84.9` → `84,9` (de) / `84.9` (en); one decimal at most. */
export function formatPercent(p: number | null | undefined, locale = 'de'): string {
  if (p == null || !Number.isFinite(p)) return '—'
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(p)
}
