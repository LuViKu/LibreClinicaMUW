<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useUsersStore } from '@/stores/users'
import { useAuthStore } from '@/stores/auth'
import type { AccountType } from '@/types/auth'
import type { StudyUser, UpdateUserInput } from '@/types/user'

/**
 * Phase E A7.2 — Edit User dialog.
 *
 * Pre-fills with the current {@link StudyUser} row (first and last name,
 * e-mail, phone, institutional affiliation and account type) and lets the
 * sysadmin edit them. Per-field diff happens server-side; the dialog
 * forwards every shown field regardless of whether it changed (the
 * backend's null-vs-string distinction means "shown but unchanged" still
 * resolves to a no-op once it sees the same value).
 *
 * Clearing follows the server's rules: the phone is optional and is sent
 * even when emptied, which clears it; the name, e-mail and affiliation
 * are required and cannot be emptied. An account created without an
 * affiliation keeps none until one is entered.
 *
 * The account type makes an account a user, a business administrator or
 * a technical administrator. Only a technical administrator may make or
 * unmake a technical administrator, so for anyone else that account's
 * type is shown and not offered.
 *
 * Username is read-only (legacy parity — identity rename unsupported).
 * Roles and lifecycle (disable/restore) are owned by the sibling
 * A7.3/A7.5 dialogs.
 */
interface Props {
  open: boolean
  user: StudyUser | null
}
const props = defineProps<Props>()
const emit = defineEmits<{ 'update:open': [v: boolean]; close: [] }>()

const { t } = useI18n()
const users = useUsersStore()
const auth = useAuthStore()

interface Form {
  firstName: string
  lastName: string
  email: string
  phone: string
  institutionalAffiliation: string
  userType: AccountType
}

function blankForm(): Form {
  return {
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    institutionalAffiliation: '',
    userType: 'USER',
  }
}

const form = ref<Form>(blankForm())
/** What the form was opened with, to tell a cleared field from one that was never set. */
const original = ref<Form>(blankForm())
const fieldErrors = ref<Record<string, string>>({})
const formError = ref<string | null>(null)
const isSubmitting = ref(false)
const successFlag = ref(false)

function hydrateFromUser() {
  if (!props.user) {
    form.value = blankForm()
    original.value = blankForm()
    return
  }
  const u = props.user
  // The row carries first and last name. A row without them (from a
  // server before they were on the wire) falls back to splitting the
  // display name on its first space.
  const display = u.displayName ?? ''
  const sep = display.indexOf(' ')
  form.value = {
    firstName: u.firstName ?? (sep >= 0 ? display.slice(0, sep) : display),
    lastName: u.lastName ?? (sep >= 0 ? display.slice(sep + 1) : ''),
    email: u.email ?? '',
    phone: u.phone ?? '',
    institutionalAffiliation: u.institutionalAffiliation ?? '',
    userType: u.userType ?? 'USER',
  }
  original.value = { ...form.value }
}

/** A technical administrator's type is theirs to change only for another technical administrator. */
const canChangeType = computed(() => auth.isTechAdmin || original.value.userType !== 'TECHADMIN')

const userTypeOptions = computed<AccountType[]>(() =>
  auth.isTechAdmin || original.value.userType === 'TECHADMIN'
    ? ['USER', 'SYSADMIN', 'TECHADMIN']
    : ['USER', 'SYSADMIN'],
)

/** The affiliation is required: it may stay empty only if it already was. */
const affiliationCleared = computed(
  () => form.value.institutionalAffiliation.trim() === '' && original.value.institutionalAffiliation.trim() !== '',
)

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
    !affiliationCleared.value &&
    props.user != null
  )
})

async function submit() {
  if (!props.user || !canSubmit.value) return
  fieldErrors.value = {}
  formError.value = null
  isSubmitting.value = true
  try {
    const affiliation = form.value.institutionalAffiliation.trim()
    const patch: UpdateUserInput = {
      firstName: form.value.firstName.trim(),
      lastName: form.value.lastName.trim(),
      email: form.value.email.trim(),
      // Optional: sent even when empty, so emptying the field clears it.
      phone: form.value.phone.trim(),
      // Required: never sent empty (the server refuses a blank one).
      ...(affiliation !== '' ? { institutionalAffiliation: affiliation } : {}),
      ...(canChangeType.value && form.value.userType !== original.value.userType
        ? { userType: form.value.userType }
        : {}),
    }
    const result = await users.updateUser(props.user.username, patch)
    if (result.ok) {
      successFlag.value = true
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
          <ErrorText v-if="fieldErrors.phone">{{ fieldErrors.phone }}</ErrorText>
        </div>
        <div class="col-span-2">
          <FieldLabel for="edit-user-affiliation" required>{{ t('manageUsers.edit.affiliation') }}</FieldLabel>
          <TextInput id="edit-user-affiliation" v-model="form.institutionalAffiliation" />
          <ErrorText v-if="affiliationCleared">{{ t('manageUsers.edit.requiredCleared') }}</ErrorText>
          <ErrorText v-else-if="fieldErrors.institutionalAffiliation">{{ fieldErrors.institutionalAffiliation }}</ErrorText>
        </div>
        <div class="col-span-2">
          <FieldLabel for="edit-user-usertype">{{ t('manageUsers.userType.label') }}</FieldLabel>
          <SelectInput id="edit-user-usertype" v-model="form.userType" :disabled="!canChangeType">
            <option v-for="type in userTypeOptions" :key="type" :value="type">
              {{ t(`manageUsers.userType.${type}`) }}
            </option>
          </SelectInput>
          <p class="text-xs text-slate-500 mt-1">
            {{ canChangeType ? t('manageUsers.userType.help') : t('manageUsers.userType.techOnly') }}
          </p>
          <ErrorText v-if="fieldErrors.userType">{{ fieldErrors.userType }}</ErrorText>
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
