<script setup lang="ts">
import { computed } from 'vue'

/**
 * Phase E SPA primitive — Date input.
 *
 * One canonical date control for the entire SPA. Wraps a native
 * `<input type="date">` so the browser surfaces its locale-aware
 * picker. `lang="de-AT"` pins the display format to Austrian
 * convention (TT.MM.JJJJ) even when the browser session is en-US,
 * which is what the clinical workflow at MUW expects.
 *
 * Wire format remains ISO `YYYY-MM-DD` — `<input type="date">` reads
 * and writes that natively, so the v-model contract is identical to
 * what the existing callsites had with a raw `<input type="date">`.
 * Migrating from a raw input is a pure rendering refactor; no store
 * or backend change is needed.
 *
 * Visual parity with TextInput.vue — same border, focus ring, error
 * state, disabled greyscale. The `placeholder="TT.MM.JJJJ"` only
 * shows on browsers that fall back to text input for `type="date"`
 * (vanishingly rare in 2026, but cheap insurance).
 */
interface Props {
  id?: string
  /** ISO `YYYY-MM-DD`. Accepts `null` so v-model on nullable fields doesn't need coercion. */
  modelValue?: string | null
  required?: boolean
  error?: boolean
  disabled?: boolean
  /** ISO `YYYY-MM-DD` lower bound. */
  min?: string
  /** ISO `YYYY-MM-DD` upper bound. */
  max?: string
  ariaLabel?: string
  ariaDescribedby?: string
  autocomplete?: string
}

const props = withDefaults(defineProps<Props>(), {
  id: undefined,
  modelValue: '',
  required: false,
  error: false,
  disabled: false,
  min: undefined,
  max: undefined,
  ariaLabel: undefined,
  ariaDescribedby: undefined,
  autocomplete: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  blur: [event: FocusEvent]
  focus: [event: FocusEvent]
}>()

const onInput = (e: Event) => {
  emit('update:modelValue', (e.target as HTMLInputElement).value)
}

const inputClasses = computed(() => {
  const base = 'w-full px-3 py-2 rounded-md focus:outline-none transition-colors muw-focus'
  if (props.error) {
    return `${base} border border-rose-400 bg-rose-50/40 focus:border-rose-500 focus:ring-2 focus:ring-rose-100`
  }
  if (props.disabled) {
    return `${base} border border-slate-200 bg-slate-100 text-slate-500 cursor-not-allowed`
  }
  return `${base} border border-slate-300 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100`
})
</script>

<template>
  <input
    :id="id"
    type="date"
    lang="de-AT"
    :value="modelValue ?? ''"
    :required="required || undefined"
    :disabled="disabled"
    :min="min"
    :max="max"
    :autocomplete="autocomplete"
    :aria-label="ariaLabel"
    :aria-describedby="ariaDescribedby"
    :aria-invalid="error || undefined"
    :aria-required="required || undefined"
    placeholder="TT.MM.JJJJ"
    :class="inputClasses"
    @input="onInput"
    @blur="$emit('blur', $event)"
    @focus="$emit('focus', $event)"
  />
</template>
