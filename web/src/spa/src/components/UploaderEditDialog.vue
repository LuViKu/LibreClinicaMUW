<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import HelperText from '@/components/HelperText.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useUploadersStore } from '@/stores/uploaders'
import type { MutationFailure } from '@/lib/mutationFailure'
import type { ManageableLocation } from '@/types/auth'
import type { ManagedUploader } from '@/types/uploader'

/**
 * Create and rename one uploader, for the system administrator and for a
 * location's Standort-Admin.
 *
 * One form for both modes: with `existing` it renames, without it creates an
 * uploader at `location`. The name is the only field: required, at most 64
 * characters, unique within the location. A new uploader gets its first
 * token with it, so create hands the page what rename does not: the token,
 * which the page shows once in its own dialog.
 *
 * The dialog sends the request itself, because only it can place the
 * server's answers: a 400 comes back as a message under the name (and goes
 * again once the name is edited); any other refusal (the location is no
 * longer available, 409; it is not this person's, 404) is a message of the
 * form. On success it emits `saved`, with the token when one was issued,
 * and closes; the page confirms with a notice.
 */
interface Props {
  open: boolean
  /** The uploader to rename; absent to create one. */
  existing?: ManagedUploader | null
  /** Where a new uploader is created. Unused when renaming. */
  location?: ManageableLocation | null
}
const props = withDefaults(defineProps<Props>(), { existing: null, location: null })
const emit = defineEmits<{
  'update:open': [value: boolean]
  saved: [result: { uploader: ManagedUploader; token: string | null }, mode: 'create' | 'rename']
}>()

const MAX_NAME = 64

const { t } = useI18n()
const store = useUploadersStore()

const name = ref('')
const fieldError = ref<string | null>(null)
const formError = ref<string | null>(null)
const submitting = ref(false)

const isRename = computed(() => props.existing != null)
const trimmed = computed(() => name.value.trim())
const tooLong = computed(() => trimmed.value.length > MAX_NAME)

/** The name is required and fits; a create also needs the place it is made at. */
const canSubmit = computed(
  () =>
    !submitting.value &&
    trimmed.value !== '' &&
    !tooLong.value &&
    (isRename.value || props.location != null),
)

// Seed the form each time the dialog opens, and put the cursor in the name.
// (The Modal parks focus on its panel in a microtask; the field is focused
// after the next tick, so it wins.)
watch(
  () => [props.open, props.existing] as const,
  async ([isOpen]) => {
    if (!isOpen) return
    name.value = props.existing?.displayName ?? ''
    fieldError.value = null
    formError.value = null
    submitting.value = false
    await nextTick()
    document.getElementById('uploader-name')?.focus()
  },
  { immediate: true },
)

// A message belongs to what was typed when it was shown: editing the name
// takes it away.
watch(name, () => { fieldError.value = null })

function showFailure(failure: MutationFailure) {
  const own = failure.fieldErrors.displayName
  const strays = Object.entries(failure.fieldErrors)
    .filter(([field]) => field !== 'displayName')
    .map(([, message]) => message)
  fieldError.value = own ?? null
  // A 400 is told by its fields; its generic "Validation failed" would only repeat them.
  formError.value =
    strays.length > 0 ? strays.join(' ') : Object.keys(failure.fieldErrors).length === 0 ? failure.message : null
}

async function submit() {
  if (!canSubmit.value) return
  fieldError.value = null
  formError.value = null
  submitting.value = true
  try {
    const existing = props.existing
    if (existing) {
      const result = await store.rename(existing.id, trimmed.value)
      if (result.ok) {
        emit('saved', { uploader: result.uploader, token: null }, 'rename')
        close()
      } else {
        showFailure(result)
      }
    } else if (props.location) {
      const result = await store.create(props.location.id, trimmed.value)
      if (result.ok) {
        emit('saved', { uploader: result.uploader, token: result.token }, 'create')
        close()
      } else {
        showFailure(result)
      }
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
    labelled-by="uploader-dialog-title"
    panel-class="max-w-lg"
    @update:open="(v) => emit('update:open', v)"
  >
    <template #header>
      <h2 id="uploader-dialog-title" class="text-lg font-semibold tracking-tight" data-testid="uploader-dialog-title">
        {{ isRename ? t('uploaders.dialog.renameTitle') : t('uploaders.dialog.createTitle') }}
      </h2>
    </template>

    <form id="uploader-form" novalidate class="space-y-3" @submit.prevent="submit">
      <p
        v-if="!isRename && props.location"
        class="text-xs text-slate-600"
        data-testid="uploader-dialog-location"
      >
        {{ t('uploaders.location.single', { name: props.location.name }) }}
      </p>

      <div>
        <FieldLabel for="uploader-name" required>{{ t('uploaders.dialog.field.name') }}</FieldLabel>
        <TextInput
          id="uploader-name"
          v-model="name"
          autocomplete="off"
          :error="fieldError !== null || tooLong"
        />
        <ErrorText v-if="fieldError" data-testid="field-error-displayName">{{ fieldError }}</ErrorText>
        <ErrorText v-else-if="tooLong" data-testid="field-error-displayName">{{ t('uploaders.dialog.tooLong') }}</ErrorText>
        <HelperText v-else>{{ t('uploaders.dialog.nameHelp') }}</HelperText>
      </div>

      <ErrorText v-if="formError" data-testid="uploader-form-error">{{ formError }}</ErrorText>
    </form>

    <template #footer>
      <div />
      <div class="flex items-center gap-2">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700 muw-focus"
          :disabled="submitting"
          data-testid="uploader-form-cancel"
          @click="close"
        >
          {{ t('common.cancel') }}
        </button>
        <!-- The footer sits outside the form element, so the button names its form. -->
        <button
          type="submit"
          form="uploader-form"
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50 muw-focus"
          :disabled="!canSubmit"
          data-testid="uploader-form-submit"
        >
          {{
            submitting
              ? t('common.saving')
              : isRename
                ? t('uploaders.dialog.renameSubmit')
                : t('uploaders.dialog.createSubmit')
          }}
        </button>
      </div>
    </template>
  </Modal>
</template>
