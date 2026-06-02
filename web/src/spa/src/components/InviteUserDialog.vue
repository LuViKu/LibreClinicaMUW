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
import type { UserRole } from '@/types/user'

/**
 * Phase E A7.1 — Invite User dialog.
 *
 * Captures the minimum fields legacy {@code CreateUserAccountServlet}
 * requires (username, name, email, institutional affiliation) plus
 * the initial study/role binding (the legacy form bundles these via
 * the {@code activeStudy} + {@code role} radios).
 *
 * Per the A7.1 plan the welcome email is deferred to the MailService
 * extraction follow-up — when {@code sendEmail} is false the response
 * carries the one-time password, which we surface in a copy-to-clipboard
 * panel on success. The default is to NOT send (since email isn't wired
 * yet), so the admin always gets the password to distribute manually.
 */
interface Props {
  open: boolean
}
const props = defineProps<Props>()
const emit = defineEmits<{ 'update:open': [v: boolean]; close: [] }>()

const { t } = useI18n()
const users = useUsersStore()
const auth = useAuthStore()

interface Form {
  username: string
  firstName: string
  lastName: string
  email: string
  institutionalAffiliation: string
  phone: string
  role: UserRole
}
const form = ref<Form>(initialForm())
const fieldErrors = ref<Record<string, string>>({})
const formError = ref<string | null>(null)
const isSubmitting = ref(false)
const generatedPassword = ref<string | null>(null)

function initialForm(): Form {
  return {
    username: '',
    firstName: '',
    lastName: '',
    email: '',
    institutionalAffiliation: '',
    phone: '',
    role: 'Investigator',
  }
}

function resetState() {
  form.value = initialForm()
  fieldErrors.value = {}
  formError.value = null
  generatedPassword.value = null
}

// Reset when the dialog opens so a previous result doesn't bleed in.
watch(
  () => props.open,
  (next) => { if (next) resetState() },
)

const studyId = computed(() => {
  // We bind the new user to the admin's currently active study. The
  // store mirrors backend session state — if for some reason the
  // admin hasn't picked a study, we surface a guard message.
  const active = auth.user?.activeStudy
  return active?.id && active.id > 0 ? active.id : null
})

const canSubmit = computed(() => {
  return (
    form.value.username.trim().length > 0 &&
    form.value.firstName.trim().length > 0 &&
    form.value.lastName.trim().length > 0 &&
    form.value.email.trim().length > 0 &&
    form.value.institutionalAffiliation.trim().length > 0 &&
    studyId.value != null
  )
})

const roleOptions: { v: UserRole; l: () => string }[] = [
  { v: 'Investigator',  l: () => t('manageUsers.role.Investigator') },
  { v: 'CRC',           l: () => t('manageUsers.role.CRC') },
  { v: 'Monitor',       l: () => t('manageUsers.role.Monitor') },
  { v: 'Data Manager',  l: () => t('manageUsers.role.Data Manager') },
  { v: 'Administrator', l: () => t('manageUsers.role.Administrator') },
]

async function submit() {
  if (!canSubmit.value || studyId.value == null) return
  fieldErrors.value = {}
  formError.value = null
  isSubmitting.value = true
  try {
    const result = await users.createUser({
      username: form.value.username.trim(),
      firstName: form.value.firstName.trim(),
      lastName: form.value.lastName.trim(),
      email: form.value.email.trim(),
      institutionalAffiliation: form.value.institutionalAffiliation.trim(),
      phone: form.value.phone.trim() === '' ? null : form.value.phone.trim(),
      studyId: studyId.value,
      role: form.value.role,
      sendEmail: false,
    })
    if (result.ok) {
      generatedPassword.value = result.result.generatedPassword
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

async function copyPassword() {
  if (!generatedPassword.value) return
  try {
    await navigator.clipboard.writeText(generatedPassword.value)
  } catch {
    // No-op — older browsers without clipboard API just show the value.
  }
}
</script>

<template>
  <Modal :open="props.open" labelled-by="invite-user-title" panel-class="max-w-2xl" @update:open="(v) => emit('update:open', v)" @close="close">
    <template #header>
      <h2 id="invite-user-title" class="text-lg font-semibold tracking-tight">
        {{ t('manageUsers.invite.title') }}
      </h2>
    </template>

    <!-- Success view: show the one-time password and a copy button. -->
    <div v-if="generatedPassword !== null" class="space-y-3">
      <p class="text-sm text-slate-700">{{ t('manageUsers.invite.successIntro') }}</p>
      <div class="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-xs">
        <div class="flex items-center justify-between gap-3">
          <code class="font-mono text-sm">{{ generatedPassword }}</code>
          <button
            class="px-2 py-1 text-xs border border-emerald-300 rounded-md bg-white text-emerald-800 hover:bg-emerald-100"
            @click="copyPassword"
          >
            {{ t('manageUsers.invite.copyPassword') }}
          </button>
        </div>
      </div>
      <p class="text-xs text-slate-500">{{ t('manageUsers.invite.firstLoginNote') }}</p>
    </div>

    <!-- Form view. -->
    <div v-else class="space-y-4">
      <div v-if="studyId == null" class="rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
        {{ t('manageUsers.invite.studyMissing') }}
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div>
          <FieldLabel for="invite-username" required>{{ t('manageUsers.invite.username') }}</FieldLabel>
          <TextInput id="invite-username" v-model="form.username" autocomplete="off" />
          <ErrorText v-if="fieldErrors.username">{{ fieldErrors.username }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="invite-role" required>{{ t('manageUsers.invite.role') }}</FieldLabel>
          <SelectInput id="invite-role" v-model="form.role">
            <option v-for="opt in roleOptions" :key="opt.v" :value="opt.v">{{ opt.l() }}</option>
          </SelectInput>
          <ErrorText v-if="fieldErrors.role">{{ fieldErrors.role }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="invite-firstname" required>{{ t('manageUsers.invite.firstName') }}</FieldLabel>
          <TextInput id="invite-firstname" v-model="form.firstName" autocomplete="given-name" />
          <ErrorText v-if="fieldErrors.firstName">{{ fieldErrors.firstName }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="invite-lastname" required>{{ t('manageUsers.invite.lastName') }}</FieldLabel>
          <TextInput id="invite-lastname" v-model="form.lastName" autocomplete="family-name" />
          <ErrorText v-if="fieldErrors.lastName">{{ fieldErrors.lastName }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="invite-email" required>{{ t('manageUsers.invite.email') }}</FieldLabel>
          <TextInput id="invite-email" v-model="form.email" type="email" autocomplete="email" />
          <ErrorText v-if="fieldErrors.email">{{ fieldErrors.email }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="invite-phone">{{ t('manageUsers.invite.phone') }}</FieldLabel>
          <TextInput id="invite-phone" v-model="form.phone" type="tel" autocomplete="tel" />
        </div>
        <div class="col-span-2">
          <FieldLabel for="invite-affiliation" required>{{ t('manageUsers.invite.affiliation') }}</FieldLabel>
          <TextInput id="invite-affiliation" v-model="form.institutionalAffiliation" />
          <ErrorText v-if="fieldErrors.institutionalAffiliation">{{ fieldErrors.institutionalAffiliation }}</ErrorText>
        </div>
      </div>

      <ErrorText v-if="formError">{{ formError }}</ErrorText>
    </div>

    <template #footer>
      <div v-if="generatedPassword === null" class="text-xs text-slate-500">
        {{ t('manageUsers.invite.emailNotWiredNote') }}
      </div>
      <div v-else class="text-xs text-slate-500" />
      <div class="flex items-center gap-2">
        <button
          v-if="generatedPassword === null"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          @click="close"
        >
          {{ t('common.cancel') }}
        </button>
        <button
          v-if="generatedPassword === null"
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
          :disabled="!canSubmit || isSubmitting"
          @click="submit"
        >
          {{ isSubmitting ? t('common.saving') : t('manageUsers.invite.submit') }}
        </button>
        <button
          v-else
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium"
          @click="close"
        >
          {{ t('common.cancel') }}
        </button>
      </div>
    </template>
  </Modal>
</template>
