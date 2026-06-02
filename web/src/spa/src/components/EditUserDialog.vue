<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useUsersStore } from '@/stores/users'
import type { StudyUser } from '@/types/user'

/**
 * Phase E A7.2 — Edit User dialog.
 *
 * Pre-fills with the current {@link StudyUser} row and lets the
 * sysadmin edit firstName / lastName / email / phone /
 * institutionalAffiliation. Per-field diff happens server-side; the
 * dialog forwards every shown field regardless of whether it changed
 * (the backend's null-vs-string distinction means "shown but
 * unchanged" still resolves to a no-op once it sees the same value).
 *
 * Username is read-only (legacy parity — identity rename unsupported).
 * Role, userType and lifecycle (disable/restore) are owned by the
 * sibling A7.3/A7.5 dialogs.
 */
interface Props {
  open: boolean
  user: StudyUser | null
}
const props = defineProps<Props>()
const emit = defineEmits<{ 'update:open': [v: boolean]; close: [] }>()

const { t } = useI18n()
const users = useUsersStore()

interface Form {
  firstName: string
  lastName: string
  email: string
  phone: string
  institutionalAffiliation: string
}

function blankForm(): Form {
  return {
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    institutionalAffiliation: '',
  }
}

const form = ref<Form>(blankForm())
const fieldErrors = ref<Record<string, string>>({})
const formError = ref<string | null>(null)
const isSubmitting = ref(false)
const successFlag = ref(false)

function hydrateFromUser() {
  if (!props.user) {
    form.value = blankForm()
    return
  }
  // The list endpoint returns a slim StudyUser — we have displayName +
  // email, but the legacy first/last split isn't on the wire. Best-effort
  // split on the first space; the user can refine in the form.
  const display = props.user.displayName ?? ''
  const sep = display.indexOf(' ')
  form.value = {
    firstName: sep >= 0 ? display.slice(0, sep) : display,
    lastName: sep >= 0 ? display.slice(sep + 1) : '',
    email: props.user.email ?? '',
    phone: '',
    institutionalAffiliation: '',
  }
}

watch(
  () => [props.open, props.user] as const,
  ([isOpen]) => {
    if (isOpen) {
      hydrateFromUser()
      fieldErrors.value = {}
      formError.value = null
      successFlag.value = false
    }
  },
)

const canSubmit = computed(() => {
  return (
    form.value.firstName.trim().length > 0 &&
    form.value.lastName.trim().length > 0 &&
    form.value.email.trim().length > 0 &&
    props.user != null
  )
})

async function submit() {
  if (!props.user || !canSubmit.value) return
  fieldErrors.value = {}
  formError.value = null
  isSubmitting.value = true
  try {
    const patch = {
      firstName: form.value.firstName.trim(),
      lastName: form.value.lastName.trim(),
      email: form.value.email.trim(),
      // phone / affiliation: only forward when non-empty — empty would
      // clear the column, which is reasonable but ideally a user opt-in.
      ...(form.value.phone.trim() !== '' ? { phone: form.value.phone.trim() } : {}),
      ...(form.value.institutionalAffiliation.trim() !== ''
        ? { institutionalAffiliation: form.value.institutionalAffiliation.trim() }
        : {}),
    }
    const result = await users.updateUser(props.user.username, patch)
    if (result.ok) {
      successFlag.value = true
      // Auto-close after a brief success flash so the operator sees the
      // confirmation without an extra click.
      setTimeout(close, 800)
    } else {
      fieldErrors.value = result.fieldErrors
      formError.value = result.message ?? null
    }
  } finally {
    isSubmitting.value = false
  }
}

function close() {
  emit('update:open', false)
  emit('close')
}
</script>

<template>
  <Modal :open="props.open" labelled-by="edit-user-title" panel-class="max-w-2xl" @update:open="(v) => emit('update:open', v)" @close="close">
    <template #header>
      <div>
        <h2 id="edit-user-title" class="text-lg font-semibold tracking-tight">
          {{ t('manageUsers.edit.title') }}
        </h2>
        <p v-if="props.user" class="text-xs text-slate-500 mt-0.5">
          {{ props.user.username }}
        </p>
      </div>
    </template>

    <div v-if="successFlag" class="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900">
      {{ t('manageUsers.edit.successFlash') }}
    </div>

    <div v-else-if="props.user" class="space-y-4">
      <div class="grid grid-cols-2 gap-3">
        <div>
          <FieldLabel for="edit-user-username">{{ t('manageUsers.edit.username') }}</FieldLabel>
          <TextInput id="edit-user-username" :model-value="props.user.username" disabled />
          <p class="text-xs text-slate-500 mt-1">{{ t('manageUsers.edit.usernameNote') }}</p>
        </div>
        <div>
          <FieldLabel for="edit-user-role">{{ t('manageUsers.edit.role') }}</FieldLabel>
          <TextInput id="edit-user-role" :model-value="props.user.role" disabled />
          <p class="text-xs text-slate-500 mt-1">{{ t('manageUsers.edit.roleNote') }}</p>
        </div>
        <div>
          <FieldLabel for="edit-user-firstname" required>{{ t('manageUsers.edit.firstName') }}</FieldLabel>
          <TextInput id="edit-user-firstname" v-model="form.firstName" autocomplete="given-name" />
          <ErrorText v-if="fieldErrors.firstName">{{ fieldErrors.firstName }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="edit-user-lastname" required>{{ t('manageUsers.edit.lastName') }}</FieldLabel>
          <TextInput id="edit-user-lastname" v-model="form.lastName" autocomplete="family-name" />
          <ErrorText v-if="fieldErrors.lastName">{{ fieldErrors.lastName }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="edit-user-email" required>{{ t('manageUsers.edit.email') }}</FieldLabel>
          <TextInput id="edit-user-email" v-model="form.email" type="email" autocomplete="email" />
          <ErrorText v-if="fieldErrors.email">{{ fieldErrors.email }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="edit-user-phone">{{ t('manageUsers.edit.phone') }}</FieldLabel>
          <TextInput id="edit-user-phone" v-model="form.phone" type="tel" autocomplete="tel" />
        </div>
        <div class="col-span-2">
          <FieldLabel for="edit-user-affiliation">{{ t('manageUsers.edit.affiliation') }}</FieldLabel>
          <TextInput id="edit-user-affiliation" v-model="form.institutionalAffiliation" />
          <ErrorText v-if="fieldErrors.institutionalAffiliation">{{ fieldErrors.institutionalAffiliation }}</ErrorText>
        </div>
      </div>

      <ErrorText v-if="formError">{{ formError }}</ErrorText>
    </div>

    <template #footer>
      <div />
      <div class="flex items-center gap-2">
        <button
          v-if="!successFlag"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          @click="close"
        >
          {{ t('common.cancel') }}
        </button>
        <button
          v-if="!successFlag"
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
          :disabled="!canSubmit || isSubmitting"
          @click="submit"
        >
          {{ isSubmitting ? t('common.saving') : t('manageUsers.edit.submit') }}
        </button>
      </div>
    </template>
  </Modal>
</template>
