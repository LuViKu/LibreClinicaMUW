/**
 * Phase E hardening — A5 (2026-06-10).
 *
 * Pins the load-bearing contract for the `errors` store: ring-buffer
 * cap, dismiss-by-id, and the normalisation of arbitrary thrown
 * values (plain Error / ApiError / ApiNetworkError / opaque non-Error)
 * into a `TrackedError` shape. The A4 worktree adds a `reqId` field
 * to ApiError + ApiNetworkError; until that lands the field is
 * undefined and these tests defend the "fall back to empty string"
 * branch.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useErrorsStore } from '../errors'
import { ApiError, ApiNetworkError } from '@/api/client'

describe('useErrorsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('push() returns the tracked entry and adds it to recent', () => {
    const store = useErrorsStore()
    const entry = store.push(new Error('boom'))
    expect(entry.message).toBe('boom')
    expect(entry.kind).toBe('render')
    expect(store.recent).toHaveLength(1)
    expect(store.recent[0].id).toBe(entry.id)
  })

  it('exposes the most-recent entry via `latest`', () => {
    const store = useErrorsStore()
    store.push(new Error('first'))
    store.push(new Error('second'))
    expect(store.latest?.message).toBe('second')
  })

  it('latest is null when no errors have been pushed', () => {
    const store = useErrorsStore()
    expect(store.latest).toBeNull()
  })

  it('caps the ring buffer at 20 entries (oldest discarded)', () => {
    const store = useErrorsStore()
    for (let i = 0; i < 25; i++) {
      store.push(new Error(`err-${i}`))
    }
    expect(store.recent).toHaveLength(20)
    // The 5 oldest (err-0 .. err-4) should have rolled off; err-24 is
    // the newest.
    expect(store.recent[0].message).toBe('err-5')
    expect(store.recent[store.recent.length - 1].message).toBe('err-24')
  })

  it('dismiss(id) removes the matching entry only', () => {
    const store = useErrorsStore()
    const a = store.push(new Error('keep'))
    const b = store.push(new Error('drop'))
    store.dismiss(b.id)
    expect(store.recent).toHaveLength(1)
    expect(store.recent[0].id).toBe(a.id)
  })

  it('dismiss(id) is a no-op when the id is unknown', () => {
    const store = useErrorsStore()
    store.push(new Error('x'))
    store.dismiss(99999)
    expect(store.recent).toHaveLength(1)
  })

  it('clear() resets the buffer', () => {
    const store = useErrorsStore()
    store.push(new Error('a'))
    store.push(new Error('b'))
    store.clear()
    expect(store.recent).toHaveLength(0)
    expect(store.latest).toBeNull()
  })

  it('normalises a plain Error into a render-kind TrackedError', () => {
    const store = useErrorsStore()
    const e = store.push(new Error('nope'), 'render')
    expect(e.kind).toBe('render')
    expect(e.info).toBe('render')
    expect(e.reqId).toBe('')
    expect(e.when).toBeInstanceOf(Date)
  })

  it('classifies ApiError as kind="api"', () => {
    const store = useErrorsStore()
    const err = new ApiError(500, 'Backend exploded', { message: 'Backend exploded' })
    const entry = store.push(err)
    expect(entry.kind).toBe('api')
    expect(entry.message).toBe('Backend exploded')
  })

  it('classifies ApiNetworkError as kind="network"', () => {
    const store = useErrorsStore()
    const err = new ApiNetworkError('Network down', new Error('ECONNREFUSED'))
    const entry = store.push(err)
    expect(entry.kind).toBe('network')
    expect(entry.message).toBe('Network down')
  })

  it('reads reqId from ApiError when present (A4 future-proofing)', () => {
    // Simulate the post-A4 shape where reqId is a property on the
    // error instance. We can't rely on the constructor signature
    // accepting it yet, so attach it directly — same as A4's filter
    // will once it lands.
    const store = useErrorsStore()
    const err = new ApiError(500, 'Backend exploded')
    ;(err as ApiError & { reqId: string }).reqId = '7f3eabc'
    const entry = store.push(err)
    expect(entry.reqId).toBe('7f3eabc')
  })

  it('defaults reqId to empty string when the error has no such field', () => {
    const store = useErrorsStore()
    const entry = store.push(new Error('plain'))
    expect(entry.reqId).toBe('')
  })

  it('normalises a non-Error throw into an "unknown" entry with a fallback message', () => {
    const store = useErrorsStore()
    const entry = store.push('a string throw')
    expect(entry.kind).toBe('unknown')
    expect(entry.message).toBe('a string throw')
  })

  it('falls back to "Unbekannter Fehler" when the throw has no usable message', () => {
    const store = useErrorsStore()
    const entry = store.push(null)
    expect(entry.message).toBe('Unbekannter Fehler')
    expect(entry.kind).toBe('unknown')
  })

  it('falls back to "Unbekannter Fehler" for an Error with empty message + name', () => {
    const store = useErrorsStore()
    const e = new Error('')
    // Default Error.name is "Error" — the fallback to .name keeps the
    // message non-empty; assert that path.
    const entry = store.push(e)
    expect(entry.message).toBe('Error')
  })

  it('assigns monotonically increasing ids', () => {
    const store = useErrorsStore()
    const a = store.push(new Error('a'))
    const b = store.push(new Error('b'))
    const c = store.push(new Error('c'))
    expect(b.id).toBeGreaterThan(a.id)
    expect(c.id).toBeGreaterThan(b.id)
  })
})
