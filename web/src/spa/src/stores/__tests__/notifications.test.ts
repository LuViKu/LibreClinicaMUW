/**
 * UX sweep (#11/#15, 2026-08-12) — notifications store spec.
 * Pins the success/info toast contract: push returns an id, entries carry
 * their kind, auto-dismiss fires after the TTL, and manual dismiss removes
 * the right entry without disturbing the others.
 */
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useNotificationsStore } from '@/stores/notifications'

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
})
afterEach(() => vi.useRealTimers())

describe('notifications store', () => {
  it('pushes a success toast and returns its id', () => {
    const s = useNotificationsStore()
    const id = s.success('Gespeichert.')
    expect(s.toasts).toHaveLength(1)
    expect(s.toasts[0]).toMatchObject({ id, kind: 'success', message: 'Gespeichert.' })
  })

  it('auto-dismisses after the default TTL', () => {
    const s = useNotificationsStore()
    s.info('Hinweis')
    expect(s.toasts).toHaveLength(1)
    vi.advanceTimersByTime(4000)
    expect(s.toasts).toHaveLength(0)
  })

  it('dismisses one entry without touching the others', () => {
    const s = useNotificationsStore()
    const a = s.success('A', 0) // 0 = no auto-dismiss
    s.success('B', 0)
    expect(s.toasts).toHaveLength(2)
    s.dismiss(a)
    expect(s.toasts.map((t) => t.message)).toEqual(['B'])
  })

  it('clear removes everything and cancels timers', () => {
    const s = useNotificationsStore()
    s.success('A')
    s.info('B')
    s.clear()
    expect(s.toasts).toHaveLength(0)
    vi.advanceTimersByTime(10000) // no late dismissals throw
    expect(s.toasts).toHaveLength(0)
  })
})
