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
