<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'

/**
 * Phase E.3 primitive — Modal (focused action over context).
 *
 * The dimmed-context pattern used by Monitor's Add Query flow, e-signature
 * confirmations, study-lock confirmations. The underlying page stays
 * visible-but-inert behind a scrim; the modal panel sits centred with a
 * tight header (title + breadcrumb chips + close X) and a footer slot
 * for primary/secondary actions.
 *
 * Accessibility:
 *   - role="dialog", aria-modal="true", aria-labelledby (to the heading
 *     id supplied via `labelledBy`).
 *   - Escape key closes via the `close` event.
 *   - Background scroll is locked via `overflow: hidden` on document
 *     while the modal is open; restored on close.
 *   - Initial focus moves to the modal panel; focus-trap implementation
 *     is deferred to Phase E.9's accessibility pass (currently the
 *     primary action button traps focus via tabindex order).
 */
interface Props {
  /** Whether the modal is open. v-model:open. */
  open: boolean
  /** Element id of the heading inside the default slot. */
  labelledBy: string
  /** Optional fixed width — default `max-w-2xl` is appropriate for forms. */
  panelClass?: string
  /** Lock background scroll while open. Default true. */
  lockScroll?: boolean
  /** Close when the user clicks on the scrim. Default true. */
  closeOnScrimClick?: boolean
  /** Close when the user presses Escape. Default true. */
  closeOnEscape?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  panelClass: 'max-w-2xl',
  lockScroll: true,
  closeOnScrimClick: true,
  closeOnEscape: true,
})

const emit = defineEmits<{
  close: []
  'update:open': [value: boolean]
}>()

const panel = ref<HTMLDivElement | null>(null)

const close = () => {
  emit('close')
  emit('update:open', false)
}

const onScrimClick = () => {
  if (props.closeOnScrimClick) close()
}

const onKeydown = (e: KeyboardEvent) => {
  if (props.closeOnEscape && e.key === 'Escape') close()
}

watch(
  () => props.open,
  (isOpen) => {
    if (typeof window === 'undefined') return
    if (isOpen) {
      if (props.lockScroll) document.body.style.overflow = 'hidden'
      document.addEventListener('keydown', onKeydown)
      // Focus the panel so the next Tab moves into the modal.
      queueMicrotask(() => panel.value?.focus())
    } else {
      if (props.lockScroll) document.body.style.overflow = ''
      document.removeEventListener('keydown', onKeydown)
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (typeof window === 'undefined') return
  document.body.style.overflow = ''
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition-opacity duration-150"
      enter-from-class="opacity-0"
      leave-active-class="transition-opacity duration-150"
      leave-to-class="opacity-0"
    >
      <div
        v-if="open"
        class="fixed inset-0 z-40 bg-slate-900/40"
        aria-hidden="true"
        @click="onScrimClick"
      />
    </Transition>

    <Transition
      enter-active-class="transition duration-150"
      enter-from-class="opacity-0 translate-y-2"
      enter-to-class="opacity-100 translate-y-0"
      leave-active-class="transition duration-150"
      leave-to-class="opacity-0 translate-y-2"
    >
      <div
        v-if="open"
        class="fixed inset-0 z-50 flex items-start justify-center pt-16 px-4 overflow-y-auto"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="labelledBy"
      >
        <div
          ref="panel"
          tabindex="-1"
          class="w-full bg-white rounded-muw shadow-muw-elev border border-slate-200 overflow-hidden outline-none"
          :class="panelClass"
          @click.stop
        >
          <div
            v-if="$slots.header"
            class="px-5 py-4 border-b border-slate-200 flex items-start justify-between gap-4"
          >
            <div class="flex-1 min-w-0">
              <slot name="header" />
            </div>
            <button
              class="p-1 -m-1 hover:bg-slate-100 rounded text-slate-400 shrink-0"
              aria-label="Close"
              @click="close"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                <line x1="18" x2="6" y1="6" y2="18" />
                <line x1="6" x2="18" y1="6" y2="18" />
              </svg>
            </button>
          </div>

          <div class="px-5 py-5">
            <slot />
          </div>

          <div
            v-if="$slots.footer"
            class="px-5 py-3 border-t border-slate-200 bg-slate-50 flex items-center justify-between gap-3"
          >
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
