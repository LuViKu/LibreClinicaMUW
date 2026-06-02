<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import StatusPill from '@/components/StatusPill.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useUsersStore } from '@/stores/users'
import { useAuthStore } from '@/stores/auth'
import type { RoleBinding, StudyUser, UserRole } from '@/types/user'

/**
 * Phase E A7.5 — Manage role bindings for a single user.
 *
 * Lists every (study, role) binding the target user has and lets
 * the sysadmin grant a new one, change the role on an existing
 * binding, or revoke. Mirrors the legacy admin "User study/role"
 * surface (Set/Edit/DeleteStudyUserRoleServlet).
 *
 * Study picker is sourced from `auth.availableStudies` — the studies
 * the current admin is bound to. In a real deployment the sysadmin
 * is typically bound to all studies; for narrower sysadmins only
 * studies they can see can be granted (good defence-in-depth).
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

const bindings = ref<RoleBinding[]>([])
const isLoading = ref(false)
const loadError = ref<string | null>(null)
const formError = ref<string | null>(null)
const fieldErrors = ref<Record<string, string>>({})
const isSubmitting = ref(false)

const addStudyOid = ref<string>('')
const addRole = ref<UserRole>('Investigator')

async function refresh() {
  if (!props.user) return
  isLoading.value = true
  loadError.value = null
  try {
    bindings.value = await users.listUserRoles(props.user.username)
  } catch (e) {
    bindings.value = []
    loadError.value = e instanceof Error ? e.message : 'Unknown error'
  } finally {
    isLoading.value = false
  }
}

watch(
  () => [props.open, props.user] as const,
  ([isOpen]) => {
    if (isOpen) {
      bindings.value = []
      loadError.value = null
      formError.value = null
      fieldErrors.value = {}
      addStudyOid.value = ''
      addRole.value = 'Investigator'
      if (auth.availableStudies.length === 0) auth.loadStudies()
      refresh()
    }
  },
)

/* Studies the admin may pick from — exclude those the target user
   already has an ACTIVE binding for, so the picker only offers
   "new" grants. Updates use the per-row inline picker instead. */
const availableForGrant = computed(() => {
  const taken = new Set(bindings.value.filter((b) => b.active).map((b) => b.studyOid))
  return auth.availableStudies.filter((s) => !taken.has(s.oid))
})

const roleOptions: { v: UserRole; l: () => string }[] = [
  { v: 'Investigator',  l: () => t('manageUsers.role.Investigator') },
  { v: 'CRC',           l: () => t('manageUsers.role.CRC') },
  { v: 'Monitor',       l: () => t('manageUsers.role.Monitor') },
  { v: 'Data Manager',  l: () => t('manageUsers.role.Data Manager') },
  { v: 'Administrator', l: () => t('manageUsers.role.Administrator') },
]

async function onGrant() {
  if (!props.user || addStudyOid.value === '') return
  fieldErrors.value = {}
  formError.value = null
  isSubmitting.value = true
  try {
    const result = await users.grantRole(props.user.username, addStudyOid.value, addRole.value)
    if (result.ok) {
      addStudyOid.value = ''
      await refresh()
    } else {
      fieldErrors.value = result.fieldErrors
      formError.value = result.message ?? null
    }
  } finally {
    isSubmitting.value = false
  }
}

async function onChangeRole(binding: RoleBinding, nextRole: UserRole) {
  if (!props.user || nextRole === binding.role) return
  isSubmitting.value = true
  try {
    const result = await users.updateRole(props.user.username, binding.studyOid ?? '', nextRole)
    if (result.ok) await refresh()
    else formError.value = result.message ?? null
  } finally {
    isSubmitting.value = false
  }
}

async function onRevoke(binding: RoleBinding) {
  if (!props.user || !binding.studyOid) return
  if (!confirm(t('manageUsers.roles.revokeConfirm', {
    username: props.user.username,
    study: binding.studyName ?? binding.studyOid,
  }))) return
  isSubmitting.value = true
  try {
    const result = await users.revokeRole(props.user.username, binding.studyOid)
    if (result.ok) await refresh()
    else formError.value = result.message ?? null
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
  <Modal :open="props.open" labelled-by="user-roles-title" panel-class="max-w-3xl" @update:open="(v) => emit('update:open', v)" @close="close">
    <template #header>
      <div>
        <h2 id="user-roles-title" class="text-lg font-semibold tracking-tight">
          {{ t('manageUsers.roles.title') }}
        </h2>
        <p v-if="props.user" class="text-xs text-slate-500 mt-0.5">{{ props.user.username }}</p>
      </div>
    </template>

    <div v-if="props.user" class="space-y-5">
      <!-- Bindings list -->
      <section>
        <h3 class="text-xs font-semibold uppercase tracking-wide text-slate-500 mb-2">
          {{ t('manageUsers.roles.currentHeading') }}
        </h3>
        <p v-if="isLoading" class="text-xs italic text-slate-500">{{ t('common.loading') }}</p>
        <p v-else-if="loadError" class="text-xs text-rose-600">{{ loadError }}</p>
        <p v-else-if="bindings.length === 0" class="text-xs italic text-slate-500">
          {{ t('manageUsers.roles.empty') }}
        </p>
        <ul v-else class="space-y-1.5">
          <li
            v-for="b in bindings"
            :key="b.studyOid ?? ''"
            class="flex items-center gap-3 px-3 py-2 rounded-md border border-slate-200 bg-white"
          >
            <div class="flex-1">
              <div class="text-sm font-medium text-slate-800">{{ b.studyName ?? b.studyOid }}</div>
              <div v-if="b.siteLabel" class="text-xs text-slate-500">{{ b.siteLabel }}</div>
            </div>
            <SelectInput
              v-if="b.active"
              :id="`role-${b.studyOid ?? 'unknown'}`"
              :model-value="b.role"
              class="w-40"
              :disabled="isSubmitting"
              @update:model-value="(v) => onChangeRole(b, v as UserRole)"
            >
              <option v-for="opt in roleOptions" :key="opt.v" :value="opt.v">{{ opt.l() }}</option>
            </SelectInput>
            <StatusPill v-else variant="neutral">{{ t('manageUsers.roles.revoked') }}</StatusPill>
            <button
              v-if="b.active"
              class="px-2 py-1 text-xs text-rose-600 hover:underline disabled:opacity-50"
              :disabled="isSubmitting"
              @click="onRevoke(b)"
            >
              {{ t('manageUsers.roles.revoke') }}
            </button>
          </li>
        </ul>
      </section>

      <!-- Grant form -->
      <section v-if="availableForGrant.length > 0">
        <h3 class="text-xs font-semibold uppercase tracking-wide text-slate-500 mb-2">
          {{ t('manageUsers.roles.grantHeading') }}
        </h3>
        <div class="flex items-end gap-3">
          <div class="flex-1">
            <FieldLabel for="grant-role-study" required>{{ t('manageUsers.roles.study') }}</FieldLabel>
            <SelectInput id="grant-role-study" v-model="addStudyOid" :disabled="isSubmitting">
              <option value="">{{ t('manageUsers.roles.studyPlaceholder') }}</option>
              <option v-for="s in availableForGrant" :key="s.oid" :value="s.oid">{{ s.name }}</option>
            </SelectInput>
            <ErrorText v-if="fieldErrors.studyOid">{{ fieldErrors.studyOid }}</ErrorText>
          </div>
          <div class="w-44">
            <FieldLabel for="grant-role-role" required>{{ t('manageUsers.roles.role') }}</FieldLabel>
            <SelectInput id="grant-role-role" v-model="addRole" :disabled="isSubmitting">
              <option v-for="opt in roleOptions" :key="opt.v" :value="opt.v">{{ opt.l() }}</option>
            </SelectInput>
            <ErrorText v-if="fieldErrors.role">{{ fieldErrors.role }}</ErrorText>
          </div>
          <button
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
            :disabled="addStudyOid === '' || isSubmitting"
            @click="onGrant"
          >
            {{ t('manageUsers.roles.grant') }}
          </button>
        </div>
        <ErrorText v-if="formError">{{ formError }}</ErrorText>
      </section>
    </div>

    <template #footer>
      <div class="text-xs text-slate-500">
        {{ t('manageUsers.roles.auditNote') }}
      </div>
      <button
        class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
        @click="close"
      >
        {{ t('common.cancel') }}
      </button>
    </template>
  </Modal>
</template>
