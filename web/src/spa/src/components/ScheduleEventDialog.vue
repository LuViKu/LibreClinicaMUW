<script setup lang="ts">
/**
 * Phase E.6 — Schedule Event Dialog.
 *
 * Modal that surfaces the long-wired `useEventsStore().schedule()`
 * action (POST /pages/api/v1/events). Until now no view called it —
 * the AddSubjectView "Save & schedule first event" button + the
 * SubjectDetailView's planned-but-never-rendered "Schedule" button
 * both pointed nowhere. This dialog plugs that gap with a three-field
 * form:
 *
 *  - event definition (dropdown of the active study's non-removed
 *    event definitions, eagerly loaded if the store is empty)
 *  - dateStarted (HTML5 date input, defaults to today)
 *  - location (optional free text)
 *
 * On 201 the dialog emits `scheduled` with the new event row and
 * closes; the parent re-fetches the subject to refresh the events
 * table without a manual page reload.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'
import TextInput from '@/components/TextInput.vue'
import DateInput from '@/components/DateInput.vue'
import SelectInput from '@/components/SelectInput.vue'

import { useEventsStore } from '@/stores/events'
import { useEventDefinitionsStore } from '@/stores/eventDefinitions'
import type { StudyEvent } from '@/types/event'

interface Props {
  /** v-model:open — controls dialog visibility. */
  open: boolean
  /** StudySubject.label (e.g. "M-001"). Passed straight to the schedule endpoint. */
  subjectId: string
  /** OID of the currently active study — drives event-definition load. */
  studyOid: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  /** Fired on 201 with the freshly created study_event row. */
  scheduled: [event: StudyEvent]
}>()

const { t } = useI18n()
const events = useEventsStore()
const eventDefinitions = useEventDefinitionsStore()

const today = computed(() => {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
})

const eventDefinitionOid = ref('')
const dateStarted = ref(today.value)
const location = ref('')
const fieldErrors = ref<Record<string, string>>({})
const formError = ref<string | null>(null)
const isSubmitting = ref(false)

/**
 * Drop the soft-deleted slots from the picker — those are no longer
 * scheduleable, even though the legacy store still returns them.
 */
const eligibleDefinitions = computed(() =>
  eventDefinitions.rows.filter((d) => d.status !== 'removed'),
)

function resetForm() {
  eventDefinitionOid.value = ''
  dateStarted.value = today.value
  location.value = ''
  fieldErrors.value = {}
  formError.value = null
}

onMounted(() => {
  if (eventDefinitions.rows.length === 0 && props.studyOid) {
    void eventDefinitions.load(props.studyOid)
  }
})

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      resetForm()
      if (eventDefinitions.rows.length === 0 && props.studyOid) {
        void eventDefinitions.load(props.studyOid)
      }
    }
  },
)

function close() {
  emit('update:open', false)
}

async function onSubmit() {
  fieldErrors.value = {}
  formError.value = null

  if (!eventDefinitionOid.value) {
    fieldErrors.value.eventDefinitionOid = t('scheduleEvent.error.eventDefinitionRequired')
    return
  }
  if (!dateStarted.value) {
    fieldErrors.value.dateStarted = t('scheduleEvent.error.dateRequired')
    return
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dateStarted.value)) {
    fieldErrors.value.dateStarted = t('scheduleEvent.error.dateInvalid')
    return
  }

  isSubmitting.value = true
  try {
    const trimmedLocation = location.value.trim()
    const created = await events.schedule({
      subjectId: props.subjectId,
      eventDefinitionOid: eventDefinitionOid.value,
      dateStarted: dateStarted.value,
      ...(trimmedLocation ? { location: trimmedLocation } : {}),
    })
    if (created) {
      emit('scheduled', created)
      close()
    } else {
      // store.schedule sets events.error on failure — surface it as the
      // form-level error so the user knows what blew up.
      formError.value = events.error ?? t('scheduleEvent.error.generic')
    }
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <Modal
    :open="open"
    labelled-by="schedule-event-dialog-title"
    panel-class="max-w-lg"
    @update:open="(v) => emit('update:open', v)"
  >
    <template #header>
      <h2 id="schedule-event-dialog-title" class="text-base font-semibold">
        {{ t('scheduleEvent.title') }}
      </h2>
      <p class="text-xs text-slate-500 mt-0.5">
        {{ t('scheduleEvent.subtitle', { id: subjectId }) }}
      </p>
    </template>

    <form class="space-y-4" novalidate @submit.prevent="onSubmit">
      <div>
        <FieldLabel for="schedule-event-def" required>
          {{ t('scheduleEvent.field.eventDefinition') }}
        </FieldLabel>
        <SelectInput
          id="schedule-event-def"
          v-model="eventDefinitionOid"
          :error="!!fieldErrors.eventDefinitionOid"
          :disabled="eventDefinitions.isLoading"
        >
          <option value="" disabled>{{ t('scheduleEvent.placeholder.eventDefinition') }}</option>
          <option
            v-for="def in eligibleDefinitions"
            :key="def.oid"
            :value="def.oid"
          >
            {{ def.name }}
          </option>
        </SelectInput>
        <ErrorText v-if="fieldErrors.eventDefinitionOid">
          {{ fieldErrors.eventDefinitionOid }}
        </ErrorText>
        <p
          v-else-if="!eventDefinitions.isLoading && eligibleDefinitions.length === 0"
          class="mt-1 text-[11px] text-slate-500"
        >
          {{ t('scheduleEvent.empty') }}
        </p>
      </div>

      <div>
        <FieldLabel for="schedule-event-date" required>
          {{ t('scheduleEvent.field.dateStarted') }}
        </FieldLabel>
        <DateInput
          id="schedule-event-date"
          v-model="dateStarted"
          required
          :error="!!fieldErrors.dateStarted"
        />
        <ErrorText v-if="fieldErrors.dateStarted">{{ fieldErrors.dateStarted }}</ErrorText>
      </div>

      <div>
        <FieldLabel for="schedule-event-location">
          {{ t('scheduleEvent.field.location') }}
        </FieldLabel>
        <TextInput
          id="schedule-event-location"
          v-model="location"
          :placeholder="t('scheduleEvent.placeholder.location')"
        />
      </div>

      <p v-if="formError" class="text-xs text-rose-700" role="alert">{{ formError }}</p>
    </form>

    <template #footer>
      <div class="flex justify-end gap-2 w-full">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
          :disabled="isSubmitting"
          @click="close"
        >
          {{ t('common.cancel') }}
        </button>
        <button
          type="button"
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 disabled:opacity-50"
          :disabled="isSubmitting || eligibleDefinitions.length === 0"
          @click="onSubmit"
        >
          {{ isSubmitting ? t('common.saving') : t('scheduleEvent.submit') }}
        </button>
      </div>
    </template>
  </Modal>
</template>
