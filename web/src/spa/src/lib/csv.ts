/**
 * One CSV cell (RFC 4180 quoting) that a spreadsheet opens as text.
 *
 * Excel, LibreOffice and Google Sheets evaluate a cell whose text starts with
 * `=`, `+`, `-` or `@` (or a tab or carriage return before one) as a formula,
 * quoted or not. Text such as a subject label typed at a site therefore gets
 * a leading `'`, which shows the value as written. Numbers and booleans are
 * written as they are.
 */
export function csvCell(value: unknown): string {
  if (value == null) return ''
  let s = String(value)
  if (typeof value === 'string' && /^[=+\-@\t\r]/.test(s)) s = `'${s}`
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}
