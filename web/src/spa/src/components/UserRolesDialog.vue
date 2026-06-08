<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'
import RoleDots from '@/components/RoleDots.vue'

import { useUsersStore } from '@/stores/users'
import { useAuthStore } from '@/stores/auth'
import type { RoleBinding, StudyUser, UserRole } from '@/types/user'

/**
 * Phase E A7.5 (multi-role) — Manage role bindings for a single user.
 *
 * Rows are now per (user, study) instead of per (user, study, role).
 * Each study row exposes the full multi-role checkbox group; saving
 * goes through the bulk-replace endpoint
 * {@code PUT /pages/api/v1/users/{username}/roles/{studyOid}} so the
 * server atomically swaps the active role set for the pair.
 *
 * The {@code Administrator} role is intentionally NOT a per-study
 * selectable here — legacy convention: it is a tech-admin / sysadmin
 * projection set elsewhere (user-type), not a per-study grant. We
 * exclude it from the checkbox group; if a study somehow carries an
 * Administrator binding (legacy data) the RoleDots row still shows it.
 *
 * Study picker for "Add study" is sourced from `auth.availableStudies`;
 * we filter to studies the user does not yet have any binding on.
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
const isSubmitting = ref(false)

/* Per-study local edit state, keyed by studyOid. `null` means no
   pending edit on this study (the checkbox group reflects the
   persisted state). On Save we collapse local-edits[oid] back to
   undefined; on dirty change we lazily seed it. */
const localEdits = ref<Record<string, UserRole[]>>({})
const rowErrors = ref<Record<string, string>>({})

const addStudyOid = ref<string>('')
const addStudyDraft = ref<UserRole[]>([])
const addStudyError = ref<string | null>(null)

/* Roles selectable as a per-study grant. Administrator is excluded
   from the multi-select per legacy convention. */
const SELECTABLE_ROLES: UserRole[] = ['Investigator', 'CRC', 'Monitor', 'Data Manager']

const roleLabel = (r: UserRole) => t(`manageUsers.role.${r}`)

async function refresh() {
  if (!props.user) return
  isLoading.value = true
  loadError.value = null
  try {
    bindings.value = await users.listUserRoles(props.user.username)
    localEdits.value = {}
    rowErrors.value = {}
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
      localEdits.value = {}
      rowErrors.value = {}
      addStudyOid.value = ''
      addStudyDraft.value = []
      addStudyError.value = null
      if (auth.availableStudies.length === 0) auth.loadStudies()
      refresh()
    }
  },
)

interface StudyGroup {
  studyOid: string
  studyLabel: string
  siteLabel: string | null
  roles: UserRole[]
}

/** Group active bindings by study. Inactive (revoked) bindings are
 *  skipped — the multi-role surface treats them as "not granted". */
const bindingsByStudy = computed<StudyGroup[]>(() => {
  const map = new Map<string, StudyGroup>()
  for (const b of bindings.value) {
    if (!b.active || !b.studyOid) continue
    const existing = map.get(b.studyOid)
    if (existing) {
      if (!existing.roles.includes(b.role)) existing.roles.push(b.role)
    } else {
      map.set(b.studyOid, {
        studyOid: b.studyOid,
        studyLabel: b.studyName ?? b.studyOid,
        siteLabel: b.siteLabel,
        roles: [b.role],
      })
    }
  }
  return Array.from(map.values())
})

/* Studies the admin may add — exclude any the user already has any
   active binding for. */
const availableForGrant = computed(() => {
  const taken = new Set(bindingsByStudy.value.map((g) => g.studyOid))
  return auth.availableStudies.filter((s) => !taken.has(s.oid))
})

/** Effective selected roles for a study group — local edits win
 *  over the persisted set. */
function selectedRolesFor(group: StudyGroup): UserRole[] {
  const edit = localEdits.value[group.studyOid]
  return edit ?? group.roles
}

function isRowDirty(group: StudyGroup): boolean {
  const edit = localEdits.value[group.studyOid]
  if (!edit) return false
  if (edit.length !== group.roles.length) return true
  const a = [...edit].sort()
  const b = [...group.roles].sort()
  return a.some((r, i) => r !== b[i])
}

function toggleRole(group: StudyGroup, role: UserRole, checked: boolean) {
  const current = selectedRolesFor(group)
  const next = checked
    ? Array.from(new Set([...current, role]))
    : current.filter((r) => r !== role)
  localEdits.value = { ...localEdits.value, [group.studyOid]: next }
}

function cancelRowEdit(group: StudyGroup) {
  const next = { ...localEdits.value }
  delete next[group.studyOid]
  localEdits.value = next
  const errs = { ...rowErrors.value }
  delete errs[group.studyOid]
  rowErrors.value = errs
}

async function onSaveRow(group: StudyGroup) {
  if (!props.user) return
  const rolesToSet = selectedRolesFor(group)
  isSubmitting.value = true
  formError.value = null
  try {
    const result = await users.setStudyRoles(props.user.username, group.studyOid, rolesToSet)
    if (result.ok) {
      bindings.value = result.bindings
      const next = { ...localEdits.value }
      delete next[group.studyOid]
      localEdits.value = next
      const errs = { ...rowErrors.value }
      delete errs[group.studyOid]
      rowErrors.value = errs
    } else {
      rowErrors.value = { ...rowErrors.value, [group.studyOid]: result.message ?? t('manageUsers.roles.saveError') }
    }
  } finally {
    isSubmitting.value = false
  }
}

async function onRemoveStudy(group: StudyGroup) {
  if (!props.user) return
  const msg = t('manageUsers.roles.removeStudyConfirm', {
    username: props.user.username,
    study: group.studyLabel,
  })
  if (!confirm(msg)) return
  isSubmitting.value = true
  formError.value = null
  try {
    const result = await users.setStudyRoles(props.user.username, group.studyOid, [])
    if (result.ok) {
      bindings.value = result.bindings
      const next = { ...localEdits.value }
      delete next[group.studyOid]
      localEdits.value = next
    } else {
      rowErrors.value = { ...rowErrors.value, [group.studyOid]: result.message ?? t('manageUsers.roles.saveError') }
    }
  } finally {
    isSubmitting.value = false
  }
}

function toggleAddRole(role: UserRole, checked: boolean) {
  addStudyDraft.value = checked
    ? Array.from(new Set([...addStudyDraft.value, role]))
    : addStudyDraft.value.filter((r) => r !== role)
}

async function onAddStudy() {
  if (!props.user || addStudyOid.value === '' || addStudyDraft.value.length === 0) return
  isSubmitting.value = true
  addStudyError.value = null
  formError.value = null
  try {
    const result = await users.setStudyRoles(
      props.user.username,
      addStudyOid.value,
      addStudyDraft.value,
    )
    if (result.ok) {
      bindings.value = result.bindings
      addStudyOid.value = ''
      addStudyDraft.value = []
    } else {
      addStudyError.value = result.message ?? t('manageUsers.roles.saveError')
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
        <p v-else-if="bindingsByStudy.length === 0" class="text-xs italic text-slate-500">
          {{ t('manageUsers.roles.empty') }}
        </p>
        <ul v-else class="space-y-2">
          <li
            v-for="group in bindingsByStudy"
            :key="group.studyOid"
            class="px-3 py-3 rounded-md border border-slate-200 bg-white"
            :data-testid="`role-row-${group.studyOid}`"
          >
            <div class="flex items-start gap-3">
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2">
                  <span class="text-sm font-medium text-slate-800 truncate">
                    {{ group.studyLabel }}
                  </span>
                  <RoleDots :roles="group.roles" />
                </div>
                <div class="text-xs text-slate-500">
                  <span>{{ group.studyOid }}</span>
                  <span v-if="group.siteLabel"> · {{ group.siteLabel }}</span>
                </div>
              </div>
              <button
                type="button"
                class="px-2 py-1 text-xs text-rose-600 hover:underline disabled:opacity-50"
                :disabled="isSubmitting"
                :aria-label="t('manageUsers.roles.removeStudyAria', { study: group.studyLabel })"
                @click="onRemoveStudy(group)"
              >
                {{ t('manageUsers.roles.removeStudy') }}
              </button>
            </div>
            <fieldset class="mt-2">
              <legend class="sr-only">
                {{ t('manageUsers.roles.rolesForStudy', { study: group.studyLabel }) }}
              </legend>
              <div
                class="flex flex-wrap gap-x-4 gap-y-1.5"
                role="group"
                :aria-label="t('manageUsers.roles.rolesForStudy', { study: group.studyLabel })"
              >
                <label
                  v-for="r in SELECTABLE_ROLES"
                  :key="r"
                  class="inline-flex items-center gap-1.5 text-sm text-slate-700"
                >
                  <input
                    type="checkbox"
                    :checked="selectedRolesFor(group).includes(r)"
                    :disabled="isSubmitting"
                    :aria-label="roleLabel(r)"
                    @change="(e) => toggleRole(group, r, (e.target as HTMLInputElement).checked)"
                  />
                  <span>{{ roleLabel(r) }}</span>
                </label>
              </div>
            </fieldset>
            <div v-if="isRowDirty(group)" class="mt-2 flex items-center gap-2">
              <button
                type="button"
                class="px-3 py-1 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
                :disabled="isSubmitting"
                @click="onSaveRow(group)"
              >
                {{ t('common.save') }}
              </button>
              <button
                type="button"
                class="px-3 py-1 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700 disabled:opacity-50"
                :disabled="isSubmitting"
                @click="cancelRowEdit(group)"
              >
                {{ t('common.cancel') }}
              </button>
            </div>
            <ErrorText v-if="rowErrors[group.studyOid]">{{ rowErrors[group.studyOid] }}</ErrorText>
          </li>
        </ul>
      </section>

      <!-- Add-study form -->
      <section v-if="availableForGrant.length > 0">
        <h3 class="text-xs font-semibold uppercase tracking-wide text-slate-500 mb-2">
          {{ t('manageUsers.roles.addStudyHeading') }}
        </h3>
        <div class="space-y-3 px-3 py-3 rounded-md border border-dashed border-slate-300 bg-slate-50">
          <div>
            <FieldLabel for="add-role-study" required>{{ t('manageUsers.roles.study') }}</FieldLabel>
            <SelectInput id="add-role-study" v-model="addStudyOid" :disabled="isSubmitting">
              <option value="">{{ t('manageUsers.roles.studyPlaceholder') }}</option>
              <option v-for="s in availableForGrant" :key="s.oid" :value="s.oid">{{ s.name }}</option>
            </SelectInput>
          </div>
          <fieldset v-if="addStudyOid !== ''">
            <legend class="block text-xs font-medium text-slate-700 mb-1">
              {{ t('manageUsers.roles.role') }}
            </legend>
            <div
              class="flex flex-wrap gap-x-4 gap-y-1.5"
              role="group"
              :aria-label="t('manageUsers.roles.rolesForNewStudy')"
            >
              <label
                v-for="r in SELECTABLE_ROLES"
                :key="r"
                class="inline-flex items-center gap-1.5 text-sm text-slate-700"
              >
                <input
                  type="checkbox"
                  :checked="addStudyDraft.includes(r)"
                  :disabled="isSubmitting"
                  :aria-label="roleLabel(r)"
                  @change="(e) => toggleAddRole(r, (e.target as HTMLInputElement).checked)"
                />
                <span>{{ roleLabel(r) }}</span>
              </label>
            </div>
          </fieldset>
          <div class="flex items-center gap-2">
            <button
              type="button"
              class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
              :disabled="addStudyOid === '' || addStudyDraft.length === 0 || isSubmitting"
              @click="onAddStudy"
            >
              {{ t('manageUsers.roles.addStudy') }}
            </button>
          </div>
          <ErrorText v-if="addStudyError">{{ addStudyError }}</ErrorText>
        </div>
      </section>

      <ErrorText v-if="formError">{{ formError }}</ErrorText>
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
