import { useConfirmStore, type ConfirmOptions } from '@/stores/confirm'

/**
 * UX sweep (#19, 2026-08-12) — ergonomic wrapper over {@link useConfirmStore}.
 *
 * <pre>
 *   const confirm = useConfirm()
 *   if (!(await confirm({ message: t('foo.confirm'), danger: true }))) return
 * </pre>
 *
 * Resolves {@code true} when the operator confirms, {@code false} on cancel /
 * Escape / backdrop. A drop-in replacement for {@code window.confirm()}.
 */
export function useConfirm(): (options: ConfirmOptions) => Promise<boolean> {
  const store = useConfirmStore()
  return (options: ConfirmOptions) => store.ask(options)
}
