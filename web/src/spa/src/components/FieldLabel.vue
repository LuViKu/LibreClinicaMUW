<script setup lang="ts">
/**
 * Phase E.3 primitive — Field label.
 *
 * Pairs every form-control with a label that follows MUW's required-field
 * convention: an inline asterisk (CSS `.req` pseudo-element) when
 * `required` is true. Use the `for` prop to wire to a control's id; the
 * primitive doesn't generate an id of its own — that decision belongs to
 * the parent component (helps with form-state libraries that own ids).
 */
interface Props {
  /** Maps to the standard `for` HTML attribute via Vue's `for`. */
  for: string
  required?: boolean
}
defineProps<Props>()
</script>

<template>
  <label
    :for="for"
    class="block text-xs font-medium text-slate-700 mb-1"
    :class="{ req: required }"
  >
    <slot />
  </label>
</template>
