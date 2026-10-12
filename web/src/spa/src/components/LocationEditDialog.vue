<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import HelperText from '@/components/HelperText.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useAdminLocationsStore, type MutationFailure } from '@/stores/adminLocations'
import type { CreateLocationInput, Location } from '@/types/location'

/**
 * Create and edit one location (Standort), for the system administrator.
 *
 * One form for both modes: with `existing` it edits, without it creates.
 * The code is the location's natural key and is fixed once created (the
 * server ignores one in an edit), so it is typed on create and shown
 * read-only afterwards. Code and name are required; the address fields
 * are optional.
 *
 * The dialog sends the request itself, because only it can place the
 * server's answers: a 400 comes back as one message per field and is
 * shown under that field (and gone again once the field is edited); any
 * other refusal is a message of the form. On success it emits `saved`
 * and closes; the page confirms with a notice.
 *
 * On edit every optional field is sent, a blank one as an empty string,
 * so clearing a field works whether the server reads an absent field as
 * "unchanged" or as "cleared". On create the blank ones are left out.
 *
 * Whether a second location may exist is the deployment's choice
 * (`LIBRECLINICA_DEPLOYMENT_LOCATIONS`), not this dialog's: in single mode
 * the page does not offer to create one, and a refusal that still comes
 * back is shown as a message of the form.
 */
interface Props {
  open: boolean
  /** The location to edit; absent to create one. */
  existing?: Location | null
}
const props = withDefaults(defineProps<Props>(), { existing: null })
const emit = defineEmits<{
  'update:open': [value: boolean]
  saved: [location: Location, mode: 'create' | 'edit']
}>()

const { t } = useI18n()
const store = useAdminLocationsStore()

const FIELDS = ['code', 'name', 'street', 'zip', 'city', 'country'] as const
type FieldName = (typeof FIELDS)[number]
type Form = Record<FieldName, string>

function blank(): Form {
  return { code: '', name: '', street: '', zip: '', city: '', country: '' }
}

function fromLocation(l: Location): Form {
  return {
    code: l.code,
    name: l.name,
    street: l.street ?? '',
    zip: l.zip ?? '',
    city: l.city ?? '',
    country: l.country ?? '',
  }
}

const form = ref<Form>(blank())
const fieldErrors = ref<Partial<Record<FieldName, string>>>({})
const formError = ref<string | null>(null)
const submitting = ref(false)

const isEdit = computed(() => props.existing != null)

/** Code and name are required; the code only on create, where it is typed. */
const canSubmit = computed(
  () =>
    !submitting.value &&
    form.value.name.trim() !== '' &&
    (isEdit.value || form.value.code.trim() !== ''),
)

// Seed the form each time the dialog opens, and put the cursor on the first
// field that can be typed in. (The Modal parks focus on its panel in a
// microtask; the field is focused after the next tick, so it wins.)
watch(
  () => [props.open, props.existing] as const,
  async ([isOpen]) => {
    if (!isOpen) return
    form.value = props.existing ? fromLocation(props.existing) : blank()
    fieldErrors.value = {}
    formError.value = null
    submitting.value = false
    await nextTick()
    document.getElementById(isEdit.value ? 'location-name' : 'location-code')?.focus()
  },
  { immediate: true },
)

// A message belongs to what was typed when it was shown: editing the field
// takes it away.
for (const name of FIELDS) {
  watch(
    () => form.value[name],
    () => {
      if (fieldErrors.value[name] === undefined) return
      const { [name]: _gone, ...rest } = fieldErrors.value
      fieldErrors.value = rest
    },
  )
}

function createInput(f: Form): CreateLocationInput {
  const input: CreateLocationInput = { code: f.code.trim(), name: f.name.trim() }
  for (const name of ['street', 'zip', 'city', 'country'] as const) {
    const value = f[name].trim()
    if (value !== '') input[name] = value
  }
  return input
}

function showFailure(failure: MutationFailure) {
  const own: Partial<Record<FieldName, string>> = {}
  const strays: string[] = []
  for (const [field, message] of Object.entries(failure.fieldErrors)) {
    if ((FIELDS as readonly string[]).includes(field)) own[field as FieldName] = message
    else strays.push(message)
  }
  fieldErrors.value = own
  // A 400 is told by its fields; its generic "Validation failed" would only repeat them.
  formError.value =
    strays.length > 0 ? strays.join(' ') : Object.keys(failure.fieldErrors).length === 0 ? failure.message : null
}

async function submit() {
  if (!canSubmit.value) return
  fieldErrors.value = {}
  formError.value = null
  submitting.value = true
  try {
    const f = form.value
    const existing = props.existing
    const result = existing
      ? await store.update(existing.id, {
          name: f.name.trim(),
          street: f.street.trim(),
          zip: f.zip.trim(),
          city: f.city.trim(),
          country: f.country.trim(),
        })
      : await store.create(createInput(f))
    if (result.ok) {
      emit('saved', result.location, existing ? 'edit' : 'create')
      close()
    } else {
      showFailure(result)
    }
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:open', false)
}
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="location-dialog-title"
    panel-class="max-w-xl"
    @update:open="(v) => emit('update:open', v)"
  >
    <template #header>
      <h2 id="location-dialog-title" class="text-lg font-semibold tracking-tight" data-testid="location-dialog-title">
        {{ isEdit ? t('adminLocations.dialog.editTitle') : t('adminLocations.dialog.createTitle') }}
      </h2>
    </template>

    <form id="location-form" novalidate class="space-y-3" @submit.prevent="submit">
      <div class="grid grid-cols-2 gap-3">
        <div>
          <FieldLabel for="location-code" required>{{ t('adminLocations.dialog.field.code') }}</FieldLabel>
          <TextInput
            id="location-code"
            v-model="form.code"
            autocomplete="off"
            :readonly="isEdit"
            :error="fieldErrors.code !== undefined"
          />
          <ErrorText v-if="fieldErrors.code" data-testid="field-error-code">{{ fieldErrors.code }}</ErrorText>
          <HelperText v-else>
            {{ isEdit ? t('adminLocations.dialog.codeFixed') : t('adminLocations.dialog.codeHelp') }}
          </HelperText>
        </div>
        <div>
          <FieldLabel for="location-name" required>{{ t('adminLocations.dialog.field.name') }}</FieldLabel>
          <TextInput
            id="location-name"
            v-model="form.name"
            autocomplete="off"
            :error="fieldErrors.name !== undefined"
          />
          <ErrorText v-if="fieldErrors.name" data-testid="field-error-name">{{ fieldErrors.name }}</ErrorText>
        </div>
        <div class="col-span-2">
          <FieldLabel for="location-street">{{ t('adminLocations.dialog.field.street') }}</FieldLabel>
          <TextInput
            id="location-street"
            v-model="form.street"
            autocomplete="off"
            :error="fieldErrors.street !== undefined"
          />
          <ErrorText v-if="fieldErrors.street" data-testid="field-error-street">{{ fieldErrors.street }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="location-zip">{{ t('adminLocations.dialog.field.zip') }}</FieldLabel>
          <TextInput
            id="location-zip"
            v-model="form.zip"
            autocomplete="off"
            :error="fieldErrors.zip !== undefined"
          />
          <ErrorText v-if="fieldErrors.zip" data-testid="field-error-zip">{{ fieldErrors.zip }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="location-city">{{ t('adminLocations.dialog.field.city') }}</FieldLabel>
          <TextInput
            id="location-city"
            v-model="form.city"
            autocomplete="off"
            :error="fieldErrors.city !== undefined"
          />
          <ErrorText v-if="fieldErrors.city" data-testid="field-error-city">{{ fieldErrors.city }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="location-country">{{ t('adminLocations.dialog.field.country') }}</FieldLabel>
          <TextInput
            id="location-country"
            v-model="form.country"
            autocomplete="off"
            :error="fieldErrors.country !== undefined"
          />
          <ErrorText v-if="fieldErrors.country" data-testid="field-error-country">{{ fieldErrors.country }}</ErrorText>
        </div>
      </div>

      <ErrorText v-if="formError" data-testid="location-form-error">{{ formError }}</ErrorText>
    </form>

    <template #footer>
      <div />
      <div class="flex items-center gap-2">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700 muw-focus"
          :disabled="submitting"
          data-testid="location-form-cancel"
          @click="close"
        >
          {{ t('common.cancel') }}
        </button>
        <!-- The footer sits outside the form element, so the button names its form. -->
        <button
          type="submit"
          form="location-form"
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50 muw-focus"
          :disabled="!canSubmit"
          data-testid="location-form-submit"
        >
          {{
            submitting
              ? t('common.saving')
              : isEdit
                ? t('adminLocations.dialog.editSubmit')
                : t('adminLocations.dialog.createSubmit')
          }}
        </button>
      </div>
    </template>
  </Modal>
</template>
