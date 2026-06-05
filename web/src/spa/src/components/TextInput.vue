<script setup lang="ts">
import { computed } from 'vue'

/**
 * Phase E.3 primitive — Text input.
 *
 * v-model'd; supports the standard `error` flag for inline-validation
 * styling. The MUW focus ring uses the global `.muw-focus` utility from
 * src/style.css.
 *
 * Use the `prefix-icon` slot to inline a leading icon (search box, etc.)
 * and `suffix-icon` slot for trailing actions (clear button, eye toggle).
 */
interface Props {
  id: string
  /** Accepts `null` so v-model bindings against nullable form fields don't need to coerce at the call site. */
  modelValue?: string | null
  type?: 'text' | 'email' | 'password' | 'number' | 'search' | 'tel' | 'url' | 'date'
  placeholder?: string
  disabled?: boolean
  readonly?: boolean
  error?: boolean
  autocomplete?: string
  inputmode?: 'text' | 'email' | 'numeric' | 'decimal' | 'tel' | 'url' | 'search'
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: '',
  type: 'text',
  placeholder: undefined,
  disabled: false,
  readonly: false,
  error: false,
  autocomplete: undefined,
  inputmode: undefined,
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
  if (props.readonly || props.disabled) {
    return `${base} border border-slate-200 bg-slate-100 text-slate-500 cursor-not-allowed`
  }
  return `${base} border border-slate-300 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100`
})
</script>

<template>
  <div class="relative">
    <span
      v-if="$slots['prefix-icon']"
      class="absolute left-2 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none"
    >
      <slot name="prefix-icon" />
    </span>

    <input
      :id="id"
      :type="type"
      :value="modelValue ?? ''"
      :placeholder="placeholder"
      :disabled="disabled"
      :readonly="readonly"
      :autocomplete="autocomplete"
      :inputmode="inputmode"
      :aria-invalid="error || undefined"
      :class="[
        inputClasses,
        $slots['prefix-icon'] ? 'pl-9' : '',
        $slots['suffix-icon'] ? 'pr-9' : '',
      ]"
      @input="onInput"
      @blur="$emit('blur', $event)"
      @focus="$emit('focus', $event)"
    />

    <span
      v-if="$slots['suffix-icon']"
      class="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400"
    >
      <slot name="suffix-icon" />
    </span>
  </div>
</template>
