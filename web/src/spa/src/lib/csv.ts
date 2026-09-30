/**
 * A table as a CSV download, built in the browser from what the page shows.
 * Same conventions as the Subject Matrix export: comma-separated, CRLF line
 * ends, and a UTF-8 BOM so Excel reads umlauts.
 */

/** One cell, quoted when it holds a comma, a quote or a line break. */
export function csvCell(v: unknown): string {
  const s = v == null ? '' : String(v)
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** The rows as CSV text, BOM first. */
export function toCsv(rows: unknown[][]): string {
  return '﻿' + rows.map((r) => r.map(csvCell).join(',')).join('\r\n')
}

/** Offers the rows to the browser as a CSV file. */
export function downloadCsv(filename: string, rows: unknown[][]): void {
  const blob = new Blob([toCsv(rows)], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
