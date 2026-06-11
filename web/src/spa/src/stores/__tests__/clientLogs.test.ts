/**
 * Phase E follow-up (2026-06-11) — clientLogs ring-buffer contract.
 *
 * Pins the shape the bug-report dialog consumes: timestamp stamping,
 * ring-buffer cap at 100, and the `recent(n)` slice the dialog reads
 * just before submit.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useClientLogsStore } from '../clientLogs'

describe('useClientLogsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('push() adds an entry stamped with an ISO-8601 timestamp', () => {
    const store = useClientLogsStore()
    const entry = store.push({ level: 'error', message: 'boom' })
    expect(entry.level).toBe('error')
    expect(entry.message).toBe('boom')
    // Loose check: the timestamp parses + matches the ISO-8601 shape
    // the backend record reads.
    expect(Date.parse(entry.timestamp)).not.toBeNaN()
    expect(entry.timestamp).toMatch(/^\d{4}-\d{2}-\d{2}T/)
    expect(store.entries).toHaveLength(1)
  })

  it('trims the ring buffer to 100 entries on overflow (oldest discarded)', () => {
    const store = useClientLogsStore()
    for (let i = 0; i < 120; i++) {
      store.push({ level: 'warn', message: `msg-${i}` })
    }
    expect(store.entries).toHaveLength(100)
    // The 20 oldest (msg-0 .. msg-19) should have rolled off; msg-119
    // is the newest.
    expect(store.entries[0].message).toBe('msg-20')
    expect(store.entries[store.entries.length - 1].message).toBe('msg-119')
  })

  it('recent(50) returns the last 50 entries', () => {
    const store = useClientLogsStore()
    for (let i = 0; i < 80; i++) {
      store.push({ level: 'error', message: `m-${i}` })
    }
    const slice = store.recent(50)
    expect(slice).toHaveLength(50)
    expect(slice[0].message).toBe('m-30')
    expect(slice[slice.length - 1].message).toBe('m-79')
  })

  it('recent() defaults to 50', () => {
    const store = useClientLogsStore()
    for (let i = 0; i < 60; i++) {
      store.push({ level: 'warn', message: `m-${i}` })
    }
    expect(store.recent()).toHaveLength(50)
  })

  it('recent(n) returns the entire buffer when n >= buffer size', () => {
    const store = useClientLogsStore()
    store.push({ level: 'error', message: 'a' })
    store.push({ level: 'warn', message: 'b' })
    expect(store.recent(50)).toHaveLength(2)
  })

  it('clear() empties the buffer', () => {
    const store = useClientLogsStore()
    store.push({ level: 'error', message: 'x' })
    store.push({ level: 'uncaught', message: 'y' })
    store.clear()
    expect(store.entries).toHaveLength(0)
  })
})

import { redactPii } from '../clientLogs'

describe('redactPii', () => {
  it('replaces subject labels (M-001, DF-001, GA-001) with [REDACTED:SUBJECT-ID]', () => {
    expect(redactPii('failed for M-001')).toBe('failed for [REDACTED:SUBJECT-ID]')
    expect(redactPii('subject DF-001 not found')).toBe('subject [REDACTED:SUBJECT-ID] not found')
    expect(redactPii('GA-001-V1 query stale')).toBe('[REDACTED:SUBJECT-ID] query stale')
  })

  it('does NOT eat git SHA-7 or version strings', () => {
    expect(redactPii('built from abc1234')).toBe('built from abc1234') // lowercase
    expect(redactPii('vite 3.4.10 ready')).toBe('vite 3.4.10 ready')
  })

  it('replaces ISO / German / US dates of birth', () => {
    expect(redactPii('dob 1970-03-15')).toBe('dob [REDACTED:DOB]')
    expect(redactPii('Geburtsdatum 15.03.1970')).toBe('Geburtsdatum [REDACTED:DOB]')
    expect(redactPii('DOB 03/15/1970')).toBe('DOB [REDACTED:DOB]')
  })

  it('does NOT redact other dotted/slashed tokens that aren’t plausible DOBs', () => {
    expect(redactPii('rgba 0.0.0.1')).toBe('rgba 0.0.0.1')
    expect(redactPii('path foo/bar/baz')).toBe('path foo/bar/baz')
  })

  it('replaces email addresses with [REDACTED:EMAIL]', () => {
    expect(redactPii('mail to lukas.test@meduniwien.ac.at')).toBe(
      'mail to [REDACTED:EMAIL]',
    )
  })

  it('chains across multiple PII tokens in a single message', () => {
    const input = 'M-001 dob 1970-03-15 email pat@example.com failed'
    expect(redactPii(input)).toBe(
      '[REDACTED:SUBJECT-ID] dob [REDACTED:DOB] email [REDACTED:EMAIL] failed',
    )
  })
})
