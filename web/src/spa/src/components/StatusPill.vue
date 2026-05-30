<script setup lang="ts">
import { computed } from 'vue'

/**
 * Phase E.3 primitive — Status pill.
 *
 * Used everywhere a row carries a clinical state (subject status, CRF
 * status, discrepancy type, SDV verification, role chip, etc.).
 * Variants match the MUW palette + the validated/intentionally-out-of-palette
 * amber/rose semantic colours (MUW has no unambiguous red — see
 * [[phase-e-design-system]] memory note).
 *
 * Accessibility — the label is always rendered alongside the dot so colour
 * alone never carries meaning.
 */
type Variant =
  | 'investigator'
  | 'monitor'
  | 'data-manager'
  | 'success'
  | 'info'
  | 'warning'
  | 'danger'
  | 'neutral'

interface Props {
  variant?: Variant
  /** Compact mode — used for stacked role tags (Inv / Mon / DM). */
  compact?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'neutral',
  compact: false,
})

const variantClasses = computed<{ wrap: string; dot: string }>(() => {
  switch (props.variant) {
    case 'investigator':
      return { wrap: 'bg-muw-teal-50 text-muw-teal-700', dot: 'bg-muw-teal-500' }
    case 'monitor':
      return { wrap: 'bg-muw-sky-50 text-muw-sky-700', dot: 'bg-muw-sky-500' }
    case 'data-manager':
      return { wrap: 'bg-muw-coral-50 text-muw-coral-700', dot: 'bg-muw-coral-500' }
    case 'success':
      return { wrap: 'bg-muw-teal-50 text-muw-teal-700', dot: 'bg-muw-teal-500' }
    case 'info':
      return { wrap: 'bg-muw-sky-50 text-muw-sky-700', dot: 'bg-muw-sky-500' }
    case 'warning':
      return { wrap: 'bg-amber-50 text-amber-800', dot: 'bg-amber-500' }
    case 'danger':
      return { wrap: 'bg-rose-50 text-rose-700', dot: 'bg-rose-500' }
    case 'neutral':
    default:
      return { wrap: 'bg-slate-100 text-slate-600', dot: 'bg-slate-400' }
  }
})
</script>

<template>
  <span
    class="inline-flex items-center gap-1 rounded-full font-medium"
    :class="[
      variantClasses.wrap,
      compact ? 'px-1.5 py-0.5 text-[10px]' : 'px-2 py-0.5 text-xs',
    ]"
  >
    <span class="rounded-full" :class="[variantClasses.dot, compact ? 'w-1 h-1' : 'w-1.5 h-1.5']" aria-hidden="true"></span>
    <slot />
  </span>
</template>
