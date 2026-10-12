<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import SelectInput from '@/components/SelectInput.vue'
import type { LocationOption } from '@/types/location'

/**
 * A select over the locations (Standorte) the caller may place a study or a
 * site at (DR-040 phase 4), v-modeled as the location id (`null` while none
 * is chosen).
 *
 * The parent owns the label, the hint and the error text, and passes the
 * options it loaded from `GET /locations` (the `locationOptions` store).
 * While nothing is chosen the select shows a placeholder; `current` adds the
 * record's own location when it is not among the options (one outside the
 * caller's scope, or one that was disabled since), so an edit form shows
 * what is stored instead of an empty select.
 */
interface Props {
  id: string
  modelValue: number | null
  options: LocationOption[]
  error?: boolean
  disabled?: boolean
  /** The empty option's text; defaults to "— Choose a location —". */
  placeholder?: string
  /** The record's own location, offered even when it is not among the options. */
  current?: { id: number; name: string } | null
}
const props = withDefaults(defineProps<Props>(), {
  error: false,
  disabled: false,
  placeholder: undefined,
  current: null,
})
const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()

const { t } = useI18n()

const extra = computed(() => {
  const c = props.current
  return c && !props.options.some((o) => o.id === c.id) ? c : null
})

function onChange(value: string) {
  const n = Number(value)
  emit('update:modelValue', value === '' || !Number.isFinite(n) ? null : n)
}
</script>

<template>
  <SelectInput
    :id="props.id"
    :model-value="props.modelValue == null ? '' : String(props.modelValue)"
    :error="props.error"
    :disabled="props.disabled"
    @update:model-value="onChange"
  >
    <option v-if="props.modelValue == null" value="">{{ props.placeholder ?? t('locationSelect.placeholder') }}</option>
    <option v-if="extra" :value="String(extra.id)">{{ extra.name }}</option>
    <option v-for="o in props.options" :key="o.id" :value="String(o.id)">{{ o.name }}</option>
  </SelectInput>
</template>
