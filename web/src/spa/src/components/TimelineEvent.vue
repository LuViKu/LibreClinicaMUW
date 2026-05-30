<script setup lang="ts">
import { computed } from 'vue'

/**
 * Phase E.3 primitive — Timeline event card.
 *
 * Each entry in an audit-log-style timeline. The `variant` drives the
 * icon-bullet colour (signed = teal, reason-for-change = coral,
 * SDV = sky, admin = amber, generic data = muw-blue, query = rose).
 * Slot the icon in via the #icon slot; the default content slot is
 * the event card body.
 */
type Variant =
  | 'signed'
  | 'reason-for-change'
  | 'sdv'
  | 'admin'
  | 'data'
  | 'query'

interface Props {
  variant?: Variant
}

const props = withDefaults(defineProps<Props>(), { variant: 'data' })

const bulletClasses = computed(() => {
  switch (props.variant) {
    case 'signed': return 'bg-muw-teal-100 text-muw-teal-700'
    case 'reason-for-change': return 'bg-muw-coral-100 text-muw-coral-700'
    case 'sdv': return 'bg-muw-sky-100 text-muw-sky-700'
    case 'admin': return 'bg-amber-100 text-amber-700'
    case 'query': return 'bg-rose-100 text-rose-700'
    case 'data':
    default: return 'bg-muw-blue-100 text-muw-blue-700'
  }
})
</script>

<template>
  <article class="relative mb-3 -ml-8 flex gap-3">
    <div
      class="w-6 h-6 rounded-full inline-flex items-center justify-center z-10 shrink-0 mt-2"
      :class="bulletClasses"
    >
      <slot name="icon">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <circle cx="12" cy="12" r="6" />
        </svg>
      </slot>
    </div>
    <div class="flex-1 bg-white border border-slate-200 rounded-muw p-3 hover:border-slate-300">
      <slot />
    </div>
  </article>
</template>
