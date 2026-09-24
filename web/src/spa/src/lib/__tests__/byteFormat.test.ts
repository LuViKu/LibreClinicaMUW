import { describe, expect, it } from 'vitest'

import { formatAgo, formatBytes, formatBytesDelta, formatInstant, formatPercent, secondsSince } from '../byteFormat'

/**
 * DR-033 — how the System Status page prints sizes and ages. The readers
 * compare these numbers with Windows Explorer and `df -h`, so the steps are
 * 1024 and the labels are theirs; and an age is phrased by the browser in
 * the UI language rather than assembled from fragments.
 */
describe('formatBytes', () => {
  it('uses 1024 steps with one decimal below ten units', () => {
    expect(formatBytes(512, 'en')).toBe('512 B')
    expect(formatBytes(1536, 'en')).toBe('1.5 KB')
    expect(formatBytes(1536, 'de')).toBe('1,5 KB')
    expect(formatBytes(250 * 1024 ** 3, 'en')).toBe('250 GB')
    expect(formatBytes(2.5 * 1024 ** 4, 'en')).toBe('2.5 TB')
  })

  it('moves to the next unit at 1000, so a column never needs four digits', () => {
    expect(formatBytes(1008 * 1024 ** 2, 'en')).toBe('1.0 GB')
    expect(formatBytes(999, 'en')).toBe('999 B')
    expect(formatBytes(1000, 'en')).toBe('1.0 KB')
  })

  it('prints a dash for what is not known', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
    expect(formatBytes(-1)).toBe('—')
  })
})

describe('formatBytesDelta', () => {
  it('signs growth and shrinkage, with a real minus sign', () => {
    expect(formatBytesDelta(1536, 'en')).toBe('+1.5 KB')
    expect(formatBytesDelta(-300 * 1024 ** 2, 'en')).toBe('−300 MB')
    expect(formatBytesDelta(0, 'en')).toBe('±0 B')
    expect(formatBytesDelta(null, 'en')).toBe('—')
  })
})

describe('formatAgo', () => {
  it('picks the unit and lets the browser phrase it', () => {
    expect(formatAgo(0, 'en')).toBe('now')
    expect(formatAgo(45, 'en')).toBe('45 seconds ago')
    expect(formatAgo(95, 'en')).toBe('2 minutes ago')
    expect(formatAgo(2 * 3600, 'de')).toBe('vor 2 Stunden')
    expect(formatAgo(3 * 86400, 'de')).toBe('vor 3 Tagen')
    expect(formatAgo(null, 'en')).toBe('—')
  })
})

describe('secondsSince / formatInstant', () => {
  it('measures from an ISO instant and never goes negative', () => {
    const now = Date.parse('2026-09-24T10:00:00Z')
    expect(secondsSince('2026-09-24T09:58:00Z', now)).toBe(120)
    expect(secondsSince('2026-09-24T10:05:00Z', now)).toBe(0)
    expect(secondsSince(null, now)).toBeNull()
    expect(secondsSince('not a date', now)).toBeNull()
  })

  it('prints a dash for a missing instant', () => {
    expect(formatInstant(null)).toBe('—')
    expect(formatInstant('garbage')).toBe('—')
    expect(formatInstant('2026-09-24T10:00:00Z', 'en')).toMatch(/2026|26/)
  })
})

describe('formatPercent', () => {
  it('uses the language\'s decimal mark and at most one decimal', () => {
    expect(formatPercent(84.94, 'de')).toBe('84,9')
    expect(formatPercent(84.94, 'en')).toBe('84.9')
    expect(formatPercent(88, 'en')).toBe('88')
    expect(formatPercent(null)).toBe('—')
  })
})
