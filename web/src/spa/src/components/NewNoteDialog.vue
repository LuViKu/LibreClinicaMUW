<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'
import UserAutocomplete from '@/components/UserAutocomplete.vue'

import { useNotesStore } from '@/stores/notes'
import { useAuthStore } from '@/stores/auth'
import { canCreateNoteType, type DiscrepancyNote, type NoteType } from '@/types/note'
import type { UserRole } from '@/types/auth'

/**
 * Phase E.6 discrepancy-full — NewNoteDialog.
 *
 * Drives the "Add query" / "Capture failed-validation" / "Annotate"
 * shortcuts from the CRF entry view. Mirrors the legacy Add Query
 * popup but is role-aware: the type select hides options the backend
 * would 403 (e.g. {@code reason-for-change} for non-DM/Admin).
 *
 * The dialog is intentionally dumb — it doesn't know which item or
 * which event-CRF instance it's attached to. The parent passes
 * {@code subjectId} + {@code itemOid} + {@code eventCrfOid} so the
 * created note pins to the right repeating-event row. The optional
 * {@code prefill} lets the CrfItemWidget failed-validation shortcut
 * pre-stage type + description; the operator can still edit before
 * submitting.
 */
const NOTE_TYPES: NoteType[] = [
  'query',
  'failed-validation',
  'annotation',
  'reason-for-change',
]

const TYPE_OPTION_KEY: Record<NoteType, string> = {
  'query': 'query',
  'failed-validation': 'failedValidation',
  'annotation': 'annotation',
  'reason-for-change': 'reasonForChange',
}

const DESCRIPTION_MAX = 4000

interface Prefill {
  type?: NoteType
  description?: string
}

interface Props {
  open: boolean
  subjectId: string
  itemOid: string
  eventCrfOid: string
  itemLabel?: string
  prefill?: Prefill | null
}

const props = withDefaults(defineProps<Props>(), {
  itemLabel: undefined,
  prefill: null,
})

const emit = defineEmits<{
  'update:open': [value: boolean]
  close: []
  created: [note: DiscrepancyNote]
}>()

const { t } = useI18n()
const notes = useNotesStore()
const auth = useAuthStore()

const description = ref<string>('')
const type = ref<NoteType>('query')
const assignedTo = ref<string>('')

function hydrate(): void {
  description.value = props.prefill?.description ?? ''
  type.value = props.prefill?.type ?? 'query'
  assignedTo.value = ''
}

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      hydrate()
    }
  },
  { immediate: true },
)

const role = computed<UserRole>(() => auth.user?.role ?? 'Investigator')

const availableTypes = computed<NoteType[]>(() =>
  NOTE_TYPES.filter((t) => canCreateNoteType(role.value, t)),
)

const descriptionError = computed<string | null>(() => {
  const trimmed = description.value.trim()
  if (trimmed.length === 0) return t('crfEntry.noteDialog.errorRequired')
  if (description.value.length > DESCRIPTION_MAX) {
    return t('crfEntry.noteDialog.errorTooLong')
  }
  return null
})

const canSubmit = computed(() => descriptionError.value === null && !notes.isSubmitting)

function close(): void {
  emit('update:open', false)
  emit('close')
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  const created = await notes.createNote({
    subjectId: props.subjectId,
    itemOid: props.itemOid,
    eventCrfOid: props.eventCrfOid,
    description: description.value.trim(),
    type: type.value,
    assignedTo: assignedTo.value ? assignedTo.value : null,
  })
  if (created) {
    emit('created', created)
    close()
  }
}
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="new-note-dialog-title"
    panel-class="max-w-lg"
    @update:open="(v: boolean) => emit('update:open', v)"
    @close="close"
  >
    <template #header>
      <h2 id="new-note-dialog-title" class="text-base font-semibold text-slate-800">
        {{ t('crfEntry.noteDialog.heading', { itemLabel: props.itemLabel ?? props.itemOid }) }}
      </h2>
    </template>

    <div class="space-y-4">
      <div>
        <FieldLabel for="new-note-description" :required="true">
          {{ t('crfEntry.noteDialog.description') }}
        </FieldLabel>
        <textarea
          id="new-note-description"
          v-model="description"
          rows="4"
          :maxlength="DESCRIPTION_MAX + 1"
          :placeholder="t('crfEntry.noteDialog.descriptionPlaceholder')"
          class="w-full px-3 py-2 border border-slate-300 rounded-md text-sm focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100"
        />
        <ErrorText v-if="descriptionError">{{ descriptionError }}</ErrorText>
      </div>

      <div>
        <FieldLabel for="new-note-type">
          {{ t('crfEntry.noteDialog.type') }}
        </FieldLabel>
        <SelectInput id="new-note-type" v-model="type">
          <option
            v-for="opt in availableTypes"
            :key="opt"
            :value="opt"
          >
            {{ t(`crfEntry.noteDialog.typeOption.${TYPE_OPTION_KEY[opt]}`) }}
          </option>
        </SelectInput>
      </div>

      <div>
        <FieldLabel for="new-note-assignedto">
          {{ t('crfEntry.noteDialog.assignTo') }}
        </FieldLabel>
        <UserAutocomplete
          id="new-note-assignedto"
          v-model="assignedTo"
        />
      </div>

      <ErrorText v-if="notes.error">{{ notes.error }}</ErrorText>
    </div>

    <template #footer>
      <button
        type="button"
        class="text-xs text-slate-600 hover:text-slate-800"
        @click="close"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        type="button"
        class="px-3 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 disabled:bg-slate-300 disabled:cursor-not-allowed font-medium"
        :disabled="!canSubmit"
        @click="submit"
      >
        {{ t('crfEntry.noteDialog.submit') }}
      </button>
    </template>
  </Modal>
</template>
