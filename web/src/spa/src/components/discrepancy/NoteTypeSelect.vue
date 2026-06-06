<script setup lang="ts">
/**
 * Phase E.6 discrepancy-full — type selector for the NEW parent-note
 * dialog (distinct from the inline thread-response composer in
 * NotesDiscrepanciesView). The reason-for-change option is hidden
 * when the current role cannot create RFC notes.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import SelectInput from '@/components/SelectInput.vue'
import type { NoteType } from '@/types/note'
import { canCreateNoteType } from '@/types/note'
import type { UserRole } from '@/types/auth'

const { t } = useI18n()

const props = defineProps<{
  modelValue: NoteType
  role: UserRole | null
  id?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: NoteType): void
}>()

const allTypes: NoteType[] = ['query', 'failed-validation', 'annotation', 'reason-for-change']

const availableTypes = computed<NoteType[]>(() => {
  if (!props.role) return allTypes.filter((t) => t !== 'reason-for-change')
  return allTypes.filter((t) => canCreateNoteType(props.role!, t))
})

function onChange(v: string | number | boolean): void {
  emit('update:modelValue', v as NoteType)
}
</script>

<template>
  <SelectInput
    :id="id ?? 'note-type-select'"
    :model-value="modelValue"
    @update:model-value="onChange"
  >
    <option v-for="ty in availableTypes" :key="ty" :value="ty">
      {{ t(`notes.type.${ty}`) }}
    </option>
  </SelectInput>
</template>
