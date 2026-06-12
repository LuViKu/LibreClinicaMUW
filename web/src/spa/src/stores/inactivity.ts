/**
 * Phase E.6 inactivity-timeout (2026-06-12) — auto-logout after a
 * window of no operator activity.
 *
 * <p>Rationale. Clinical workstations are often left signed-in and
 * unattended (the operator steps out for a procedure). Without an
 * idle timeout, a passer-by can act under the operator's audit
 * identity. The institutional baseline is 10 minutes — adjustable
 * via {@code VITE_INACTIVITY_TIMEOUT_MS} at build time.
 *
 * <p>Activity signal. Any of:
 *
 *  - {@code mousemove}, {@code mousedown}, {@code click}
 *  - {@code keydown}
 *  - {@code touchstart}, {@code touchmove}
 *  - {@code scroll}, {@code wheel}
 *
 * <p>Each event resets the inactivity clock. The store runs a
 * {@code setInterval} that polls the last-activity timestamp every
 * 30 seconds; when {@code Date.now() - lastActivityAt >
 * timeoutMs} fires, the store calls {@link useAuthStore#logout}
 * and pushes the operator to {@code /login} with a
 * {@code returnTo} query that resolves back to the page they were
 * on. The login view honours that automatically.
 *
 * <p>The 30-second polling is deliberate: the system clock can
 * drift forward (suspend/resume), and a missed-tick model with a
 * single {@code setTimeout} would silently miss the trigger on
 * a long sleep. Polling catches up the next tick.
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Default — 10 minutes (in ms). Override at build time via
 * {@code VITE_INACTIVITY_TIMEOUT_MS}. The store clamps to a sane
 * minimum (60 s) so a misconfigured value can't lock the operator
 * out on every blink.
 */
const DEFAULT_TIMEOUT_MS = 10 * 60 * 1000

/** How often the watcher polls the last-activity timestamp. */
const TICK_INTERVAL_MS = 30 * 1000

/** DOM events that count as "operator activity". */
const ACTIVITY_EVENTS = [
  'mousedown',
  'mousemove',
  'click',
  'keydown',
  'touchstart',
  'touchmove',
  'scroll',
  'wheel',
] as const

function readTimeoutOverride(): number {
  const raw = (import.meta as { env?: Record<string, string> }).env?.VITE_INACTIVITY_TIMEOUT_MS
  if (!raw) return DEFAULT_TIMEOUT_MS
  const parsed = Number(raw)
  if (!Number.isFinite(parsed) || parsed <= 0) return DEFAULT_TIMEOUT_MS
  return Math.max(60_000, parsed)
}

export const useInactivityStore = defineStore('inactivity', () => {
  const timeoutMs = ref(readTimeoutOverride())
  const lastActivityAt = ref<number>(Date.now())
  const isRunning = ref(false)
  /** Set to true while the store is actively logging the operator out. */
  const isLoggingOut = ref(false)

  let tickHandle: number | null = null
  let listenersAttached = false
  /**
   * Callback the watcher invokes when the timeout fires. Wired by
   * {@link start} via a side-channel reference so the store doesn't
   * have to import the auth store + router directly (would tangle
   * the store graph). The App.vue mount provides this.
   */
  let onTimeoutCallback: (() => void | Promise<void>) | null = null

  function handleActivity(): void {
    lastActivityAt.value = Date.now()
  }

  function attachListeners(): void {
    if (listenersAttached || typeof window === 'undefined') return
    for (const e of ACTIVITY_EVENTS) {
      window.addEventListener(e, handleActivity, { passive: true })
    }
    listenersAttached = true
  }

  function detachListeners(): void {
    if (!listenersAttached || typeof window === 'undefined') return
    for (const e of ACTIVITY_EVENTS) {
      window.removeEventListener(e, handleActivity)
    }
    listenersAttached = false
  }

  function tick(): void {
    if (!isRunning.value || isLoggingOut.value) return
    const idleMs = Date.now() - lastActivityAt.value
    if (idleMs >= timeoutMs.value) {
      isLoggingOut.value = true
      const cb = onTimeoutCallback
      // Fire-and-forget. The callback is responsible for resetting
      // isLoggingOut once the auth state has flipped to anonymous;
      // {@link start} re-arms the watcher on the next operator login.
      if (cb) {
        Promise.resolve(cb()).finally(() => {
          isLoggingOut.value = false
        })
      } else {
        isLoggingOut.value = false
      }
    }
  }

  /**
   * Begin watching for inactivity. Idempotent — calling {@code start}
   * twice is a no-op. Provide {@code onTimeout} so the watcher can
   * trigger the logout side-effects without taking a direct dependency
   * on the auth store / router.
   */
  function start(onTimeout: () => void | Promise<void>): void {
    if (isRunning.value) return
    onTimeoutCallback = onTimeout
    lastActivityAt.value = Date.now()
    isRunning.value = true
    attachListeners()
    if (typeof window !== 'undefined') {
      tickHandle = window.setInterval(tick, TICK_INTERVAL_MS)
    }
  }

  /**
   * Stop watching. Called when the operator logs out manually (so
   * the watcher doesn't fire spuriously while /login is mounted) +
   * when the SPA unmounts (test cleanup).
   */
  function stop(): void {
    if (!isRunning.value) return
    isRunning.value = false
    if (tickHandle != null && typeof window !== 'undefined') {
      window.clearInterval(tickHandle)
    }
    tickHandle = null
    detachListeners()
    onTimeoutCallback = null
  }

  /**
   * Force the activity clock to "now". Useful for tests + for the
   * post-login moment where the operator just re-auth'd and the
   * watcher should NOT immediately fire again on a stale timestamp.
   */
  function touch(): void {
    lastActivityAt.value = Date.now()
  }

  return {
    timeoutMs,
    lastActivityAt,
    isRunning,
    isLoggingOut,
    start,
    stop,
    touch,
  }
})
