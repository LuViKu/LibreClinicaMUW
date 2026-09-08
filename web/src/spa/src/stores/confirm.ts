import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * UX sweep (#19, 2026-08-12) — promise-based confirmation dialog.
 *
 * <p>Replaces scattered native {@code window.confirm()} calls (blocking,
 * unstyled, untranslatable, inaccessible) with a single themed modal
 * rendered by {@code ConfirmDialog.vue} in {@link App.vue}. Call sites use
 * the {@code useConfirm()} composable:
 *
 * <pre>
 *   const confirm = useConfirm()
 *   if (!(await confirm({ message: t('...'), danger: true }))) return
 * </pre>
 *
 * <p>NOT for regulatory one-way attestations (Sign / Lock / Archive) —
 * those keep {@code ConfirmationWithPreflight} with its checklist. This is
 * the everyday "are you sure?" gate.
 */
export interface ConfirmOptions {
  /** Optional heading; falls back to a generic localized title in the dialog. */
  title?: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  /** Render the confirm button in the destructive (coral) style. */
  danger?: boolean
}

interface PendingConfirm extends ConfirmOptions {
  id: number
  resolve: (value: boolean) => void
}

export const useConfirmStore = defineStore('confirm', () => {
  const current = ref<PendingConfirm | null>(null)
  let seq = 0

  function ask(options: ConfirmOptions): Promise<boolean> {
    // Defensive: if a prior request is somehow still open, resolve it
    // false so its awaiter unblocks before we replace it.
    if (current.value) current.value.resolve(false)
    return new Promise<boolean>((resolve) => {
      current.value = { id: ++seq, ...options, resolve }
    })
  }

  function respond(value: boolean): void {
    const req = current.value
    if (!req) return
    current.value = null
    req.resolve(value)
  }

  return { current, ask, respond }
})
