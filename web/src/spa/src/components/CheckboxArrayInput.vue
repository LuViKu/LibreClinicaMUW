<script setup lang="ts">
/**
 * Phase E.6 — checkbox array primitive for `select-multi` items.
 *
 * Backs CRF items whose schema declares `dataType: 'select-multi'`. The
 * model value is the SPA-side array of selected codes (the wire shape
 * the controller serialises into the legacy comma-joined column).
 *
 * Kept primitive: a fieldset wrapping one labelled checkbox per option.
 * Parent decides label / helper / error chrome — this widget only
 * renders the controls. Disabled-set propagation goes through the
 * surrounding {@code fieldset[disabled]} chain that {@code CrfEntryView}
 * already wires for the read-only / locked path.
 *
 * Accessibility: every checkbox carries an id derived from
 * {@code idPrefix-code} so the parent's external {@code FieldLabel}
 * can target the first one; the fieldset itself carries an
 * {@code aria-describedby} pointer when a helper or error message is
 * rendered (managed by the caller).
 */

import { computed } from 'vue'
import type { ResponseOption } from '@/types/crf'

interface Props {
  /** Currently-selected codes; treated as a Set semantically. */
  modelValue: string[] | null | undefined
  /** Allowed option codes + labels from the schema's response set. */
  options: ResponseOption[]
  /** Used to make each checkbox's id unique within the form. */
  idPrefix: string
  /** Mirrors the parent's error state so the chrome can mark up. */
  error?: boolean
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  error: false,
  disabled: false,
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string[]): void
}>()

const selected = computed<Set<string>>(() => new Set((props.modelValue ?? []).map(String)))

function toggle(code: string, checked: boolean): void {
  const next = new Set(selected.value)
  if (checked) next.add(code)
  else next.delete(code)
  // Preserve schema option order so the comma-joined backend payload is
  // deterministic + the audit log diffs cleanly.
  const ordered = props.options.map((o) => o.code).filter((c) => next.has(c))
  emit('update:modelValue', ordered)
}
</script>

<template>
  <fieldset
    class="space-y-1.5"
    :class="error ? 'rounded-md border border-rose-200 bg-rose-50/30 px-2 py-1.5' : ''"
    :disabled="disabled"
  >
    <div
      v-for="opt in options"
      :key="opt.code"
      class="flex items-start gap-2"
    >
      <input
        :id="`${idPrefix}-${opt.code}`"
        type="checkbox"
        class="mt-0.5 h-4 w-4 rounded border-slate-300 text-muw-blue focus:ring-muw-blue muw-focus"
        :checked="selected.has(opt.code)"
        :value="opt.code"
        @change="toggle(opt.code, ($event.target as HTMLInputElement).checked)"
      />
      <label
        :for="`${idPrefix}-${opt.code}`"
        class="text-xs text-slate-700 leading-5 cursor-pointer"
      >
        {{ opt.label }}
      </label>
    </div>
  </fieldset>
</template>
