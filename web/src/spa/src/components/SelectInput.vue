<script setup lang="ts">
import { computed } from 'vue'

/**
 * Phase E.3 primitive — Select input.
 *
 * Native `<select>` styled to match TextInput's surface treatment.
 * v-modeled; same error / readonly / disabled flags as TextInput.
 *
 * Option rendering is slot-driven so the parent owns whatever option
 * shape (grouped, with helpers, etc.) it needs.
 */
interface Props {
  id: string
  modelValue?: string | number | null
  disabled?: boolean
  readonly?: boolean
  error?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: '',
  disabled: false,
  readonly: false,
  error: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const onChange = (e: Event) => {
  emit('update:modelValue', (e.target as HTMLSelectElement).value)
}

const selectClasses = computed(() => {
  const base = 'w-full px-3 py-2 rounded-md text-sm appearance-none cursor-pointer focus:outline-none transition-colors muw-focus pr-8'
  if (props.error) {
    return `${base} border border-rose-400 bg-rose-50/40 focus:border-rose-500 focus:ring-2 focus:ring-rose-100`
  }
  if (props.readonly || props.disabled) {
    return `${base} border border-slate-200 bg-slate-100 text-slate-500 cursor-not-allowed`
  }
  return `${base} border border-slate-300 bg-white focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100`
})
</script>

<template>
  <div class="relative">
    <select
      :id="id"
      :value="modelValue ?? ''"
      :disabled="disabled || readonly"
      :aria-invalid="error || undefined"
      :class="selectClasses"
      @change="onChange"
    >
      <slot />
    </select>
    <span class="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
        <polyline points="6 9 12 15 18 9" />
      </svg>
    </span>
  </div>
</template>
