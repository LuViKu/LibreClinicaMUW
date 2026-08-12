import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * UX sweep (#11/#15, 2026-08-12) — transient success/info notifications.
 *
 * <p>Deliberately separate from {@link useErrorsStore}: errors are a
 * durable, dismiss-on-read ring buffer with reqId/audit affordances;
 * these are ephemeral confirmations ("Gespeichert.") that auto-expire.
 * Rendered by the singleton {@code GlobalToast.vue} mounted once in
 * {@code App.vue}, top-right, so they never collide with the bottom-right
 * error toast.
 */
export type ToastKind = 'success' | 'info'

export interface Toast {
  id: number
  kind: ToastKind
  message: string
}

/** Default auto-dismiss window. Long enough to read a short confirmation. */
const DEFAULT_TTL_MS = 4000

export const useNotificationsStore = defineStore('notifications', () => {
  const toasts = ref<Toast[]>([])
  const timers = new Map<number, ReturnType<typeof setTimeout>>()
  let seq = 0

  function push(kind: ToastKind, message: string, ttlMs: number = DEFAULT_TTL_MS): number {
    const id = ++seq
    toasts.value = [...toasts.value, { id, kind, message }]
    if (ttlMs > 0) {
      timers.set(id, setTimeout(() => dismiss(id), ttlMs))
    }
    return id
  }

  function success(message: string, ttlMs?: number): number {
    return push('success', message, ttlMs)
  }

  function info(message: string, ttlMs?: number): number {
    return push('info', message, ttlMs)
  }

  function dismiss(id: number): void {
    const timer = timers.get(id)
    if (timer) {
      clearTimeout(timer)
      timers.delete(id)
    }
    toasts.value = toasts.value.filter((tt) => tt.id !== id)
  }

  function clear(): void {
    for (const timer of timers.values()) clearTimeout(timer)
    timers.clear()
    toasts.value = []
  }

  return { toasts, push, success, info, dismiss, clear }
})
