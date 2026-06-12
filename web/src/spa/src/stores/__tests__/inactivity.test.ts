/**
 * Phase E.6 inactivity-timeout (2026-06-12) — unit tests pinning the
 * idle-timeout watcher's three load-bearing behaviours:
 *
 *  1. The watcher fires the onTimeout callback after the configured
 *     idle window — driven by fake timers so the test runs fast.
 *  2. Activity events ({@code mousemove}, {@code keydown}, etc.)
 *     reset the inactivity clock; the watcher does NOT fire as long
 *     as an event arrives within the window.
 *  3. {@link useInactivityStore#stop} cleans up the polling interval
 *     + the global activity listeners (no leak across tests, no
 *     spurious fire after the operator manually signs out).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useInactivityStore } from '../inactivity'

describe('useInactivityStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('fires the onTimeout callback after timeoutMs of no activity', async () => {
    const store = useInactivityStore()
    // Force a 5-second window for the test (the public store accepts
    // 60s minimum but we mutate the ref directly here so the test
    // doesn't spin on real-time delays).
    store.timeoutMs = 5_000
    const onTimeout = vi.fn()

    store.start(onTimeout)
    expect(store.isRunning).toBe(true)

    // 4 seconds in — watcher should NOT have fired.
    vi.advanceTimersByTime(4_000)
    expect(onTimeout).not.toHaveBeenCalled()

    // 6 seconds total — past the timeout. The watcher polls every
    // 30s, so advance enough for one tick to fire.
    vi.advanceTimersByTime(30_000)
    expect(onTimeout).toHaveBeenCalledTimes(1)

    store.stop()
  })

  it('does not fire while activity events keep arriving inside the window', async () => {
    const store = useInactivityStore()
    // Use a 60s timeout so multiple poll ticks (every 30s) fall
    // inside the window. Each poll runs the idle check against the
    // last-activity timestamp; firing a mousemove before each poll
    // resets the clock so the assertion holds.
    store.timeoutMs = 60_000
    const onTimeout = vi.fn()

    store.start(onTimeout)

    // 25s in — dispatch a mousemove, then let the 30s poll fire.
    // Idle since mousemove ≈ 5s < 60s → no fire.
    vi.advanceTimersByTime(25_000)
    window.dispatchEvent(new MouseEvent('mousemove'))
    vi.advanceTimersByTime(30_000)  // poll #1
    expect(onTimeout).not.toHaveBeenCalled()

    // Another active window — keep moving.
    vi.advanceTimersByTime(25_000)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'A' }))
    vi.advanceTimersByTime(30_000)  // poll #2
    expect(onTimeout).not.toHaveBeenCalled()

    store.stop()
  })

  it('stops cleanly — no fire after stop(), no double-listener after restart', async () => {
    const store = useInactivityStore()
    store.timeoutMs = 1_000
    const cb1 = vi.fn()
    store.start(cb1)
    store.stop()
    expect(store.isRunning).toBe(false)

    // After stop, advancing time MUST NOT fire the callback.
    vi.advanceTimersByTime(120_000)
    expect(cb1).not.toHaveBeenCalled()

    // Restarting with a fresh callback fires only the new one.
    const cb2 = vi.fn()
    store.start(cb2)
    vi.advanceTimersByTime(120_000)
    expect(cb1).not.toHaveBeenCalled()
    expect(cb2).toHaveBeenCalledTimes(1)

    store.stop()
  })

  it('touch() resets the activity clock without dispatching an event', () => {
    const store = useInactivityStore()
    store.timeoutMs = 60_000
    const onTimeout = vi.fn()
    store.start(onTimeout)

    // Touch right before the poll runs (which would otherwise see
    // 50s of idleness — still under 60s, but a follow-up advance
    // through a second poll without activity would catch the fire).
    vi.advanceTimersByTime(50_000)
    store.touch()
    // Now poll: idle since touch ≈ 0s < 60s → no fire.
    vi.advanceTimersByTime(30_000)
    expect(onTimeout).not.toHaveBeenCalled()

    store.stop()
  })
})
